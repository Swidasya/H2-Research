/*
 * Copyright 2004-2019 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * A chunk of data, containing one or multiple pages.
 * <p>
 * Chunks are page aligned (each page is usually 4096 bytes).
 * There are at most 67 million (2^26) chunks,
 * each chunk is at most 2 GB large.
 */
public class Chunk {

    /**
     * The maximum chunk id.
     */
    public static final int MAX_ID = (1 << 26) - 1;

    /**
     * The maximum length of a chunk header, in bytes.
     */
    static final int MAX_HEADER_LENGTH = 1024;

    /**
     * The length of the chunk footer. The longest footer is:
     * chunk:ffffffff,block:ffffffffffffffff,
     * version:ffffffffffffffff,fletcher:ffffffff
     */
    static final int FOOTER_LENGTH = 128;

    /**
     * The chunk id.
     */
    public final int id;

    /**
     * The start block number within the file.
     */
    public volatile long block;

    /**
     * The length in number of blocks.
     */
    public int len;

    /**
     * The total number of pages in this chunk.
     */
    int pageCount;

    /**
     * The number of pages still alive.
     */
    int pageCountLive;

    /**
     * The sum of the max length of all pages.
     */
    public long maxLen;

    /**
     * The sum of the max length of all pages that are in use.
     */
    public long maxLenLive;

    /**
     * The garbage collection priority. Priority 0 means it needs to be
     * collected, a high value means low priority.
     */
    int collectPriority;

    /**
     * The position of the meta root.
     */
    long metaRootPos;

    /**
     * The version stored in this chunk.
     */
    public long version;

    /**
     * When this chunk was created, in milliseconds after the store was created.
     */
    public long time;

    /**
     * When this chunk was no longer needed, in milliseconds after the store was
     * created. After this, the chunk is kept alive a bit longer (in case it is
     * referenced in older versions).
     */
    public long unused;

    /**
     * Version of the store at which chunk become unused and therefore can be
     * considered "dead" and collected after this version is no longer in use.
     */
    long unusedAtVersion;

    /**
     * The last used map id.
     */
    public int mapId;

    /**
     * The predicted position of the next chunk.
     */
    public long next;

    /**
     * Number of live pinned pages.
     */
    private int pinCount;


    Chunk(int id) {
        this.id = id;
    }

    /**
     * Read the header from the byte buffer.
     *
     * @param buff the source buffer
     * @param start the start of the chunk in the file
     * @return the chunk
     */
    static Chunk readChunkHeader(ByteBuffer buff, long start) {
        int pos = buff.position();
        byte[] data = new byte[Math.min(buff.remaining(), MAX_HEADER_LENGTH)];
        buff.get(data);
        try {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    // set the position to the start of the first page
                    buff.position(pos + i + 1);
                    String s = new String(data, 0, i, StandardCharsets.ISO_8859_1).trim();
                    return fromString(s);
                }
            }
        } catch (Exception e) {
            // there could be various reasons
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_FILE_CORRUPT,
                    "File corrupt reading chunk at position {0}", start, e);
        }
        throw DataUtils.newIllegalStateException(
                DataUtils.ERROR_FILE_CORRUPT,
                "File corrupt reading chunk at position {0}", start);
    }

    /**
     * Write the chunk header.
     *
     * @param buff the target buffer
     * @param minLength the minimum length
     */
    void writeChunkHeader(WriteBuffer buff, int minLength) {
        long delimiterPosition = buff.position() + minLength - 1; //用空格补够，要minLength - 1是因为下面要写入'\n'
        buff.put(asString().getBytes(StandardCharsets.ISO_8859_1));
        while (buff.position() < delimiterPosition) {
            buff.put((byte) ' ');
        }
        if (minLength != 0 && buff.position() > delimiterPosition) {
            throw DataUtils.newIllegalStateException(
                    DataUtils.ERROR_INTERNAL,
                    "Chunk metadata too long");
        }
        buff.put((byte) '\n');
    }

    /**
     * Get the metadata key for the given chunk id.
     *
     * @param chunkId the chunk id
     * @return the metadata key
     */
    static String getMetaKey(int chunkId) {
        return "chunk." + Integer.toHexString(chunkId);
    }

    /**
     * Build a block from the given string.
     *
     * @param s the string
     * @return the block
     */
    public static Chunk fromString(String s) {
        HashMap<String, String> map = DataUtils.parseMap(s);
        int id = DataUtils.readHexInt(map, "chunk", 0);
        Chunk c = new Chunk(id);
        c.block = DataUtils.readHexLong(map, "block", 0);
        c.len = DataUtils.readHexInt(map, "len", 0);
        c.pageCount = DataUtils.readHexInt(map, "pages", 0);
        c.pageCountLive = DataUtils.readHexInt(map, "livePages", c.pageCount);
        c.mapId = DataUtils.readHexInt(map, "map", 0);
        c.maxLen = DataUtils.readHexLong(map, "max", 0);
        c.maxLenLive = DataUtils.readHexLong(map, "liveMax", c.maxLen);
        c.metaRootPos = DataUtils.readHexLong(map, "root", 0);
        c.time = DataUtils.readHexLong(map, "time", 0);
        c.unused = DataUtils.readHexLong(map, "unused", 0);
        c.unusedAtVersion = DataUtils.readHexLong(map, "unusedAtVersion", 0);
        c.version = DataUtils.readHexLong(map, "version", id);
        c.next = DataUtils.readHexLong(map, "next", 0);
        c.pinCount = DataUtils.readHexInt(map, "pinCount", 0);
        return c;
    }

    /**
     * Calculate the fill rate in %. 0 means empty, 100 means full.
     *
     * @return the fill rate
     */
    int getFillRate() {
        assert maxLenLive <= maxLen : maxLenLive + " > " + maxLen;
        if (maxLenLive <= 0) {
            return 0;
        } else if (maxLenLive == maxLen) {
            return 100;
        }
        return 1 + (int) (98 * maxLenLive / maxLen);
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chunk && ((Chunk) o).id == id;
    }

    /**
     * Get the chunk data as a string.
     *
     * @return the string
     */
    public String asString() {
        StringBuilder buff = new StringBuilder(240);
        DataUtils.appendMap(buff, "chunk", id);
        DataUtils.appendMap(buff, "block", block);
        DataUtils.appendMap(buff, "len", len);
        if (maxLen != maxLenLive) {
            DataUtils.appendMap(buff, "liveMax", maxLenLive);
        }
        if (pageCount != pageCountLive) {
            DataUtils.appendMap(buff, "livePages", pageCountLive);
        }
        DataUtils.appendMap(buff, "map", mapId);
        DataUtils.appendMap(buff, "max", maxLen);
        if (next != 0) {
            DataUtils.appendMap(buff, "next", next);
        }
        DataUtils.appendMap(buff, "pages", pageCount);
        DataUtils.appendMap(buff, "root", metaRootPos);
        DataUtils.appendMap(buff, "time", time);
        if (unused != 0) {
            DataUtils.appendMap(buff, "unused", unused);
        }
        if (unusedAtVersion != 0) {
            DataUtils.appendMap(buff, "unusedAtVersion", unusedAtVersion);
        }
        DataUtils.appendMap(buff, "version", version);
        DataUtils.appendMap(buff, "pinCount", pinCount);
        return buff.toString();
    }

    byte[] getFooterBytes() {
        StringBuilder buff = new StringBuilder(FOOTER_LENGTH);
        DataUtils.appendMap(buff, "chunk", id);
        DataUtils.appendMap(buff, "block", block);
        DataUtils.appendMap(buff, "version", version);
        byte[] bytes = buff.toString().getBytes(StandardCharsets.ISO_8859_1);
        int checksum = DataUtils.getFletcher32(bytes, 0, bytes.length);
        DataUtils.appendMap(buff, "fletcher", checksum);
        while (buff.length() < FOOTER_LENGTH - 1) {
            buff.append(' ');
        }
        buff.append('\n');
        return buff.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    boolean isSaved() {
        return block != Long.MAX_VALUE;
    }

    boolean isLive() {
        return pageCountLive > 0;
    }

    boolean isRewritable() {
        return isSaved()
                && isLive()
                && pageCountLive < pageCount    // not fully occupied
                && isEvacuatable();
    }

    private boolean isEvacuatable() {
        return pinCount == 0;
    }

    /**
     * Read a page of data into a ByteBuffer.
     *
     * @param fileStore to use
     * @param pos page pos
     * @param expectedMapId expected map id for the page
     * @return ByteBuffer containing page data.
     */
    ByteBuffer readBufferForPage(FileStore fileStore, long pos, int expectedMapId) {
        assert isSaved() : this;
        while (true) {
            long originalBlock = block;
            try {
                long filePos = originalBlock * MVStore.BLOCK_SIZE;
                long maxPos = filePos + len * MVStore.BLOCK_SIZE;
                filePos += DataUtils.getPageOffset(pos);
                if (filePos < 0) {
                    throw DataUtils.newIllegalStateException(
                            DataUtils.ERROR_FILE_CORRUPT,
                            "Negative position {0}; p={1}, c={2}", filePos, pos, toString());
                }

                int length = DataUtils.getPageMaxLength(pos);
                if (length == DataUtils.PAGE_LARGE) {
                    // read the first bytes to figure out actual lenght
                    length = fileStore.readFully(filePos, 128).getInt();
                }
                length = (int) Math.min(maxPos - filePos, length);
                if (length < 0) {
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_CORRUPT,
                            "Illegal page length {0} reading at {1}; max pos {2} ", length, filePos, maxPos);
                }

                ByteBuffer buff = fileStore.readFully(filePos, length);

                int offset = DataUtils.getPageOffset(pos);
                int start = buff.position();
                int remaining = buff.remaining();
                int pageLength = buff.getInt();
                if (pageLength > remaining || pageLength < 4) {
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_CORRUPT,
                            "File corrupted in chunk {0}, expected page length 4..{1}, got {2}", id, remaining,
                            pageLength);
                }
                buff.limit(start + pageLength);

                short check = buff.getShort();
                int checkTest = DataUtils.getCheckValue(id)
                        ^ DataUtils.getCheckValue(offset)
                        ^ DataUtils.getCheckValue(pageLength);
                if (check != (short) checkTest) {
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_CORRUPT,
                            "File corrupted in chunk {0}, expected check value {1}, got {2}", id, checkTest, check);
                }

                int mapId = DataUtils.readVarInt(buff);
                if (mapId != expectedMapId) {
                    throw DataUtils.newIllegalStateException(DataUtils.ERROR_FILE_CORRUPT,
                            "File corrupted in chunk {0}, expected map id {1}, got {2}", id, expectedMapId, mapId);
                }

                if (originalBlock == block) {
                    return buff;
                }
            } catch (IllegalStateException ex) {
                if (originalBlock == block) {
                    throw ex;
                }
            }
        }
    }

    /**
     * Modifies internal state to reflect the fact that one more page is stored within this chunk.
     * @param pageLengthOnDisk size of the page
     * @param singleWriter indicates whether page belongs to append mode capable map (single writer map).
     *                     Such pages are "pinned" to the chunk, they can't be evacuated (moved to a different chunk)
     *                     while on-line, but they assumed to be short-lived anyway.
     */
    void accountForWrittenPage(int pageLengthOnDisk, boolean singleWriter) {
        maxLen += pageLengthOnDisk;
        pageCount++;
        maxLenLive += pageLengthOnDisk;
        pageCountLive++;
        if (singleWriter) {
            pinCount++;
        }
    }

    /**
     * Modifies internal state to reflect the fact that one the pages within this chunk was removed from the map.
     * @param pageLength on disk of the removed page
     * @param pinned whether removed page was pinned
     * @param now is a moment in time (since creation of the store), when removal is recorded,
     *            and retention period starts
     * @param version at which page was removed
     * @return true if all of the pages, this chunk contains, were already removed, and false otherwise
     */
    boolean accountForRemovedPage(int pageLength, boolean pinned, long now, long version) {
        assert isSaved() : this;
        maxLenLive -= pageLength;
        pageCountLive--;
        if (pinned) {
            pinCount--;
        }

        if (unusedAtVersion < version) {
            unusedAtVersion = version;
        }

        assert pinCount >= 0 : this;
        assert pageCountLive >= 0 : this;
        assert pinCount <= pageCountLive : this;
        assert maxLenLive >= 0 : this;
        assert (pageCountLive == 0) == (maxLenLive == 0) : this;

        if (!isLive()) {
            assert isEvacuatable() : this;
            unused = now;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        //return asString();

        return asStringMy();
    }

    //我加上的
    private String asStringMy() {
        StringBuilder buff = new StringBuilder();
        appendMap(buff, "chunk", id);
        appendMap(buff, "block", block);
        appendMap(buff, "len", len + "");

        appendMap(buff, "collectPriority", collectPriority);
        appendMap(buff, "fillRate", getFillRate());
        if (maxLen != maxLenLive) {
            appendMap(buff, "liveMax", maxLenLive);
        }
        if (pageCount != pageCountLive) {
            appendMap(buff, "livePages", pageCountLive);
        }
        appendMap(buff, "map", mapId);
        appendMap(buff, "max", maxLen);
        if (next != 0) {
            appendMap(buff, "next", next);
        }
        appendMap(buff, "pages", pageCount);
        appendMap(buff, "root", metaRootPos);
        appendMap(buff, "time", time);
        if (unused != 0) {
            appendMap(buff, "unused", unused);
        }
        appendMap(buff, "version", version);
        return buff.toString();
    }

    //我加上的
    private static void appendMap(StringBuilder buff, String key, Object value) {
        if (buff.length() > 0) {
            buff.append(',');
        }
        buff.append(key).append(':');
        String v;
        //不转成16进制
        //        if (value instanceof Long) {
        //            v = Long.toHexString((Long) value);
        //        } else if (value instanceof Integer) {
        //            v = Integer.toHexString((Integer) value);
        //        } else {
        //            v = value.toString();
        //        }

        v = value.toString();
        if (v.indexOf(',') < 0 && v.indexOf('\"') < 0) {
            buff.append(v);
        } else {
            buff.append('\"');
            for (int i = 0, size = v.length(); i < size; i++) {
                char c = v.charAt(i);
                if (c == '\"') {
                    buff.append('\\');
                }
                buff.append(c);
            }
            buff.append('\"');
        }
    }
}

