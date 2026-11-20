package app.afkbot;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-down terrain for the minimap. For each chunk the server sends, only the top visible block of
 * every column is kept (one colour + one height per column), so memory and CPU stay tiny.
 */
final class MapData {
    private MapData() {
    }

    static final class Chunk {
        final int[] color = new int[256]; // RGB, 0 = unknown
        final short[] height = new short[256]; // relative block height of the top block
    }

    private static final Map<Long, Chunk> CHUNKS = new ConcurrentHashMap<Long, Chunk>();
    static volatile int version;
    static volatile boolean supported;
    static volatile String status = "";
    private static int[] minStates = new int[0];
    private static int[] blockColor = new int[0];
    /** Paletted containers have a length prefix before their data array (versions before 1.21.5). */
    private static boolean lengthPrefix = true;
    static volatile int minY = -64;
    static volatile boolean nether;

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    static void clear() {
        CHUNKS.clear();
        version++;
    }

    static int chunkCount() {
        return CHUNKS.size();
    }

    static void unload(int cx, int cz) {
        if (CHUNKS.remove(key(cx, cz)) != null) version++;
    }

    /** Called once per connection with the table for the server's version. */
    static void setVersion(int tableIdx, int proto) {
        String t = Proto.BLOCKS[tableIdx];
        int n = t.length() / 2;
        minStates = new int[n];
        blockColor = new int[n];
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += t.charAt(i * 2) - 32;
            minStates[i] = s;
            blockColor[i] = Proto.COLORS[t.charAt(i * 2 + 1) - 32];
        }
        lengthPrefix = proto < 770;
        supported = n > 0;
        clear();
    }

    static void setDimension(String name) {
        nether = name != null && name.contains("nether");
        boolean end = name != null && name.contains("the_end");
        minY = nether || end ? 0 : -64;
        clear();
    }

    private static int colorOfState(int state) {
        int i = Arrays.binarySearch(minStates, state);
        if (i < 0) i = -i - 2;
        return i >= 0 && i < blockColor.length ? blockColor[i] : 0x8A8A8A;
    }

    /** Drops chunks far from the player so memory stays bounded. */
    static void trim(double px, double pz, int keepChunks) {
        int cx = (int) Math.floor(px) >> 4, cz = (int) Math.floor(pz) >> 4;
        Iterator<Map.Entry<Long, Chunk>> it = CHUNKS.entrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            long k = it.next().getKey();
            int x = (int) (k >> 32), z = (int) k;
            if (Math.abs(x - cx) > keepChunks || Math.abs(z - cz) > keepChunks) {
                it.remove();
                changed = true;
            }
        }
        if (changed) version++;
    }

    /** Colour at a world block position (0 = unknown). */
    static int colorAt(int x, int z) {
        Chunk c = CHUNKS.get(key(x >> 4, z >> 4));
        return c == null ? 0 : c.color[(z & 15) * 16 + (x & 15)];
    }

    static int heightAt(int x, int z) {
        Chunk c = CHUNKS.get(key(x >> 4, z >> 4));
        return c == null ? Integer.MIN_VALUE : c.height[(z & 15) * 16 + (x & 15)];
    }

    // ============================================================ chunk packet parsing

    /** Parses a "chunk data and light" packet body (after the packet id). */
    static void readChunk(Buf.In p, int proto, double playerY) throws IOException {
        int cx = p.i32();
        int cz = p.i32();
        long[] hm = proto >= 770 ? readHeightmapsArray(p) : readHeightmapsNbt(p);
        byte[] data = p.byteArray();
        Chunk c = new Chunk();
        Section[] sections = null;
        try {
            sections = parseSections(data, lengthPrefix);
        } catch (IOException e) {
            try {
                sections = parseSections(data, !lengthPrefix);
                lengthPrefix = !lengthPrefix;
            } catch (IOException e2) {
                sections = null;
            }
        }
        int bits = hm == null ? 0 : heightmapBits(hm.length);
        int playerRel = (int) Math.floor(playerY) - minY;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int top = -1;
                if (!nether && hm != null && bits > 0) top = heightmapValue(hm, bits, i) - 1;
                int col = 0;
                if (sections != null) {
                    int start = nether || top < 0 ? Math.min(playerRel + 1, sections.length * 16 - 1) : top;
                    // walk down past transparent blocks (and from the player's height in the nether)
                    for (int y = start; y >= 0 && y >= start - (nether ? 160 : 24); y--) {
                        int st = blockAt(sections, x, y, z);
                        if (st < 0) break;
                        int k = colorOfState(st);
                        if (k != 0) {
                            col = k;
                            top = y;
                            if (k == 0x3F76E4) col = waterShade(sections, x, y, z);
                            break;
                        }
                    }
                } else if (top >= 0) {
                    // no block data: colour by height only
                    int h = Math.max(0, Math.min(255, (top + minY + 64) * 255 / 384));
                    col = (40 + h / 3) << 16 | (90 + h / 2) << 8 | (40 + h / 4);
                }
                c.color[i] = col;
                c.height[i] = (short) Math.max(0, top);
            }
        }
        CHUNKS.put(key(cx, cz), c);
        version++;
        status = sections != null ? "" : "Terrain colours unavailable for this server version (showing height only)";
    }

    private static int waterShade(Section[] s, int x, int y, int z) {
        int depth = 0;
        for (int yy = y - 1; yy >= 0 && depth < 12; yy--, depth++) {
            int st = blockAt(s, x, yy, z);
            if (st < 0 || colorOfState(st) != 0x3F76E4) break;
        }
        int f = 255 - Math.min(depth, 12) * 9;
        return (0x3F * f / 255) << 16 | (0x76 * f / 255) << 8 | (0xE4 * f / 255);
    }

    private static int heightmapBits(int longs) {
        for (int b = 1; b <= 16; b++) {
            int per = 64 / b;
            if ((256 + per - 1) / per == longs) return b;
        }
        return 0;
    }

    private static int heightmapValue(long[] hm, int bits, int i) {
        int per = 64 / bits;
        long v = hm[i / per] >>> ((i % per) * bits);
        return (int) (v & ((1L << bits) - 1));
    }

    private static long[] readHeightmapsArray(Buf.In p) throws IOException {
        int n = p.varInt();
        long[] motion = null, surface = null;
        for (int i = 0; i < n; i++) {
            int type = p.varInt();
            int len = p.varInt();
            long[] a = new long[len];
            for (int k = 0; k < len; k++) a[k] = p.i64();
            if (type == 4) motion = a;
            if (type == 1) surface = a;
        }
        return motion != null ? motion : surface;
    }

    /** Network NBT compound with long arrays MOTION_BLOCKING / WORLD_SURFACE. */
    private static long[] readHeightmapsNbt(Buf.In p) throws IOException {
        int type = p.u8();
        if (type != 10) return null;
        long[] motion = null, surface = null;
        while (true) {
            int t = p.u8();
            if (t == 0) break;
            String name = new String(p.bytes(p.u16()), "UTF-8");
            if (t == 12) {
                int len = p.i32();
                long[] a = new long[len];
                for (int k = 0; k < len; k++) a[k] = p.i64();
                if (name.equals("MOTION_BLOCKING")) motion = a;
                if (name.equals("WORLD_SURFACE")) surface = a;
            } else {
                skipNbt(p, t);
            }
        }
        return motion != null ? motion : surface;
    }

    private static void skipNbt(Buf.In p, int t) throws IOException {
        switch (t) {
            case 1: p.bytes(1); break;
            case 2: p.bytes(2); break;
            case 3: case 5: p.bytes(4); break;
            case 4: case 6: p.bytes(8); break;
            case 7: p.bytes(p.i32()); break;
            case 8: p.bytes(p.u16()); break;
            case 9: {
                int et = p.u8();
                int n = p.i32();
                for (int i = 0; i < n; i++) skipNbt(p, et);
                break;
            }
            case 10:
                while (true) {
                    int tt = p.u8();
                    if (tt == 0) break;
                    p.bytes(p.u16());
                    skipNbt(p, tt);
                }
                break;
            case 11: p.bytes(p.i32() * 4); break;
            case 12: p.bytes(p.i32() * 8); break;
            default: throw new IOException("bad nbt");
        }
    }

    // ----- chunk sections -----

    private static final class Section {
        int single = -1;
        int[] palette;
        long[] data;
        int bits;
    }

    private static Section[] parseSections(byte[] data, boolean prefix) throws IOException {
        Buf.In in = new Buf.In(data, 0);
        java.util.ArrayList<Section> list = new java.util.ArrayList<Section>();
        while (in.remaining() > 0) {
            if (list.size() > 64) throw new IOException("too many sections");
            in.u16(); // non-empty block count
            Section s = readContainer(in, 4096, 8, prefix);
            readContainer(in, 64, 3, prefix); // biomes (skipped)
            list.add(s);
        }
        if (in.remaining() != 0 || list.isEmpty()) throw new IOException("section size mismatch");
        return list.toArray(new Section[0]);
    }

    private static Section readContainer(Buf.In in, int entries, int maxIndirect, boolean prefix) throws IOException {
        Section s = new Section();
        int bits = in.u8();
        if (bits > 32) throw new IOException("bad bits");
        if (bits == 0) {
            s.single = in.varInt();
            if (prefix && in.varInt() != 0) throw new IOException("bad single");
            return s;
        }
        if (bits <= maxIndirect) {
            int n = in.varInt();
            if (n <= 0 || n > 4096) throw new IOException("bad palette");
            s.palette = new int[n];
            for (int i = 0; i < n; i++) s.palette[i] = in.varInt();
        }
        int per = 64 / bits;
        int longs = (entries + per - 1) / per;
        if (prefix) {
            int declared = in.varInt();
            if (declared != longs) throw new IOException("bad data length");
        }
        if (longs * 8 > in.remaining()) throw new IOException("short data");
        s.data = new long[longs];
        for (int i = 0; i < longs; i++) s.data[i] = in.i64();
        s.bits = bits;
        return s;
    }

    /** Block state id at section-relative y (0 = bottom of the world), or -1 if out of range. */
    private static int blockAt(Section[] secs, int x, int y, int z) {
        int si = y >> 4;
        if (si < 0 || si >= secs.length) return -1;
        Section s = secs[si];
        if (s.single >= 0) return s.single;
        int idx = ((y & 15) * 16 + z) * 16 + x;
        int per = 64 / s.bits;
        long v = (s.data[idx / per] >>> ((idx % per) * s.bits)) & ((1L << s.bits) - 1);
        if (s.palette != null) return v < s.palette.length ? s.palette[(int) v] : 0;
        return (int) v;
    }
}
