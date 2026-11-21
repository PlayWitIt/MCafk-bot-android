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
        Section[] secs; // full block data (palette-compressed) for layer view and 3D view
        int layerY = Integer.MIN_VALUE; // cached layer slice
        int[] layerColor;
        short[] layerHeight;
        // Light (Play mode only, see keepLight): per light section (index 0 = the section below the world),
        // 2048 bytes of 4-bit values, or null. skyFull marks sections whose sky light wasn't sent (= fully lit).
        byte[][] sky, block;
        boolean[] skyFull;
        volatile int lightVersion;
    }

    /**
     * Keep sky/block light from the server. Only while the Play screen is open: it costs ~100 KB per chunk, which
     * the AFK bot, the map and the light 3D view don't need.
     */
    static volatile boolean keepLight;
    static volatile int lightVersion;

    /** Tells the Play screen's renderer what changed, so it re-meshes only those sections. */
    interface Changes {
        void chunk(int cx, int cz); // loaded, replaced or unloaded

        void block(int x, int y, int z);

        void light(int cx, int cz);
    }

    static volatile Changes changes;
    /** Bumped when everything is dropped (new world / dimension). */
    static volatile int epoch;

    /** World Y to show in layer mode, or Integer.MIN_VALUE for the surface. */
    static volatile int layerY = Integer.MIN_VALUE;

    private static final Map<Long, Chunk> CHUNKS = new ConcurrentHashMap<Long, Chunk>();
    static volatile int version;
    static volatile boolean supported;
    static volatile String status = "";
    private static int[] minStates = new int[0];
    private static int[] blockColor = new int[0];
    private static int[] blockFlags = new int[0]; // 1 = solid (full collision box), 2 = fluid
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
        epoch++;
    }

    static int chunkCount() {
        return CHUNKS.size();
    }

    static void unload(int cx, int cz) {
        if (CHUNKS.remove(key(cx, cz)) != null) version++;
        Changes ch = changes;
        if (ch != null) ch.chunk(cx, cz);
    }

    /** Called once per connection with the table for the server's version. */
    static void setVersion(int tableIdx, int proto) {
        String t = Proto.BLOCKS[tableIdx];
        int n = t.length() / 3;
        int[] ms = new int[n], bc = new int[n], bf = new int[n];
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += t.charAt(i * 3) - 32;
            ms[i] = s;
            bc[i] = Proto.COLORS[t.charAt(i * 3 + 1) - 32];
            bf[i] = t.charAt(i * 3 + 2) - 32;
        }
        minStates = ms;
        blockColor = bc;
        blockFlags = bf;
        lengthPrefix = proto < 770;
        int[] cache = new int[n > 0 ? ms[n - 1] + 4096 : 0];
        Arrays.fill(cache, -2);
        stateCache = cache;
        supported = n > 0;
        clear();
    }

    static void setDimension(String name) {
        nether = name != null && name.contains("nether");
        boolean end = name != null && name.contains("the_end");
        minY = nether || end ? 0 : -64;
        clear();
    }

    private static int[] stateCache = new int[0];

    /** Map colour of a block state (0 = transparent). */
    static int colorOf(int state) {
        return state <= 0 ? 0 : colorOfState(state);
    }

    private static int colorOfState(int state) {
        int[] cache = stateCache;
        if (state >= 0 && state < cache.length) {
            int v = cache[state];
            if (v != -2) return v;
            v = lookupColor(state);
            cache[state] = v;
            return v;
        }
        return lookupColor(state);
    }

    private static int blockIndex(int state) {
        int i = Arrays.binarySearch(minStates, state);
        if (i < 0) i = -i - 2;
        return i;
    }

    /** Block flags at a world position: 1 solid, 2 fluid, 0 passable; unloaded chunks count as solid. */
    static int flagsAt(int wx, int wy, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null) return 1;
        int y = wy - minY;
        if (y < 0 || y >= c.secs.length * 16) return 0;
        int st = blockAt(c.secs, wx & 15, y, wz & 15);
        if (st <= 0) return 0;
        int i = blockIndex(st);
        return i >= 0 && i < blockFlags.length ? blockFlags[i] : 1;
    }

    /** Raw block state at a world position, or -1 if unknown. */
    static int stateAt(int wx, int wy, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null) return -1;
        int y = wy - minY;
        if (y < 0 || y >= c.secs.length * 16) return 0;
        return blockAt(c.secs, wx & 15, y, wz & 15);
    }

    /**
     * Copies a size^3 cube of block states and light (as (sky << 4) | block, -1 unknown) starting at (x0, y0, z0),
     * x fastest then z then y. One chunk lookup per column, so meshing a section is cheap.
     */
    static void copyBlocks(int x0, int y0, int z0, int size, int[] states, int[] light) {
        int minYv = minY;
        for (int dz = 0; dz < size; dz++)
            for (int dx = 0; dx < size; dx++) {
                int wx = x0 + dx, wz = z0 + dz;
                Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
                for (int dy = 0; dy < size; dy++) {
                    int i = (dy * size + dz) * size + dx;
                    int wy = y0 + dy;
                    if (c == null || c.secs == null) {
                        states[i] = -1;
                        light[i] = -1;
                        continue;
                    }
                    int y = wy - minYv;
                    states[i] = y < 0 || y >= c.secs.length * 16 ? 0 : blockAt(c.secs, wx & 15, y, wz & 15);
                    if (c.sky == null) {
                        light[i] = -1;
                        continue;
                    }
                    int li = (y >> 4) + 1;
                    if (y < 0) li = 0;
                    if (li >= c.sky.length) {
                        light[i] = 15 << 4;
                        continue;
                    }
                    int idx = ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15);
                    byte[] sk = c.sky[li], bl = c.block[li];
                    int sv = sk != null ? (sk[idx >> 1] >> ((idx & 1) << 2)) & 15 : c.skyFull[li] ? 15 : 0;
                    int bv = bl != null ? (bl[idx >> 1] >> ((idx & 1) << 2)) & 15 : 0;
                    light[i] = sv << 4 | bv;
                }
            }
    }

    /** Biome id (index into GameState.biomeNames) at a block, or -1 if unknown. Biomes are stored per 4x4x4 cell. */
    static int biomeAt(int wx, int wy, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null) return -1;
        int y = wy - minY;
        int si = y >> 4;
        if (y < 0 || si >= c.secs.length) return -1;
        Section b = c.secs[si].biomes;
        if (b == null) return -1;
        if (b.single >= 0) return b.single;
        int idx = (((y & 15) >> 2) * 4 + ((wz & 15) >> 2)) * 4 + ((wx & 15) >> 2);
        int per = 64 / b.bits;
        long v = (b.data[idx / per] >>> ((idx % per) * b.bits)) & ((1L << b.bits) - 1);
        if (b.palette != null) return v < b.palette.length ? b.palette[(int) v] : -1;
        return (int) v;
    }

    /** True if a chunk section is known to be all air (nothing to mesh). */
    static boolean sectionEmpty(int cx, int sy, int cz) {
        Chunk c = CHUNKS.get(key(cx, cz));
        if (c == null || c.secs == null) return false;
        int si = sy - (minY >> 4);
        if (si < 0 || si >= c.secs.length) return true;
        Section s = c.secs[si];
        return s.direct == null && s.single == 0;
    }

    static boolean loaded(int wx, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        return c != null && c.secs != null;
    }

    /** Applies a block change from the server and updates that column's map colour. */
    static void setBlock(int wx, int wy, int wz, int state) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null) return;
        int y = wy - minY;
        int si = y >> 4;
        if (y < 0 || si >= c.secs.length) return;
        Section s = c.secs[si];
        if (s.direct == null) {
            int[] d = new int[4096];
            for (int ly = 0; ly < 16; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++)
                        d[(ly * 16 + lz) * 16 + lx] = blockAt(c.secs, lx, si * 16 + ly, lz);
            s.direct = d;
        }
        int lx = wx & 15, lz = wz & 15;
        s.direct[((y & 15) * 16 + lz) * 16 + lx] = state;
        // recompute the surface colour of this column
        int i = lz * 16 + lx, col = 0, top = 0;
        int start = nether ? Math.min((int) Math.floor(World.selfY) - minY + 1, c.secs.length * 16 - 1) : c.secs.length * 16 - 1;
        for (int yy = start; yy >= 0; yy--) {
            int k = colorOfState(blockAt(c.secs, lx, yy, lz));
            if (k != 0) {
                col = k == WATER ? waterShade(c.secs, lx, yy, lz) : k;
                top = yy;
                break;
            }
        }
        c.color[i] = col;
        c.height[i] = (short) top;
        c.layerY = Integer.MIN_VALUE;
        version++;
        Changes ch = changes;
        if (ch != null) ch.block(wx, wy, wz);
    }

    /** Frees light data (when the Play screen closes). */
    static void dropLight() {
        for (Chunk c : CHUNKS.values()) {
            c.sky = c.block = null;
            c.skyFull = null;
        }
    }

    private static int lookupColor(int state) {
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

    static Chunk chunk(int cx, int cz) {
        return CHUNKS.get(key(cx, cz));
    }

    /** Colour at a world block position for the current mode (0 = unknown). */
    static int colorAt(int x, int z) {
        Chunk c = CHUNKS.get(key(x >> 4, z >> 4));
        if (c == null) return 0;
        int i = (z & 15) * 16 + (x & 15);
        int ly = layerY;
        if (ly == Integer.MIN_VALUE || c.secs == null) return c.color[i];
        ensureLayer(c, ly);
        return c.layerColor[i];
    }

    static int heightAt(int x, int z) {
        Chunk c = CHUNKS.get(key(x >> 4, z >> 4));
        if (c == null) return Integer.MIN_VALUE;
        int i = (z & 15) * 16 + (x & 15);
        int ly = layerY;
        if (ly == Integer.MIN_VALUE || c.secs == null) return c.height[i];
        ensureLayer(c, ly);
        return c.layerHeight[i];
    }

    /** Computes (once per Y) the first solid block at or below layer Y for every column of the chunk. */
    private static void ensureLayer(Chunk c, int ly) {
        if (c.layerY == ly && c.layerColor != null) return;
        int[] col = new int[256];
        short[] hh = new short[256];
        int rel = ly - minY;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x, found = 0, fy = 0;
                for (int y = Math.min(rel, c.secs.length * 16 - 1); y >= 0 && y >= rel - 96; y--) {
                    int st = blockAt(c.secs, x, y, z);
                    if (st < 0) break;
                    int k = colorOfState(st);
                    if (k != 0) {
                        found = k == WATER ? waterShade(c.secs, x, y, z) : k;
                        fy = y;
                        break;
                    }
                }
                col[i] = found;
                hh[i] = (short) fy;
            }
        }
        c.layerColor = col;
        c.layerHeight = hh;
        c.layerY = ly;
    }

    static final int WATER = 0x3F76E4;

    /**
     * Colour of the block at a world position: RGB, 0 for air/transparent, -1 if unknown (chunk not loaded).
     */
    static int blockColor(int wx, int wy, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null) return -1;
        int y = wy - minY;
        if (y < 0 || y >= c.secs.length * 16) return 0;
        int st = blockAt(c.secs, wx & 15, y, wz & 15);
        return st < 0 ? 0 : colorOfState(st);
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
                            if (k == WATER) col = waterShade(sections, x, y, z);
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
        c.secs = sections;
        if (keepLight && proto >= 764 && sections != null) {
            try {
                skipBlockEntities(p);
                readLight(p, c, sections.length);
            } catch (IOException e) {
                c.sky = c.block = null; // the blocks are fine; the game falls back to flat lighting here
            }
        }
        CHUNKS.put(key(cx, cz), c);
        version++;
        Changes ch = changes;
        if (ch != null) ch.chunk(cx, cz);
        status = sections != null ? "" : "Terrain colours unavailable for this server version (showing height only)";
    }

    // ============================================================ light (Play mode)

    /** Block entities in a chunk packet (1.20.2+): packed xz, y, type, network NBT without a root name. */
    private static void skipBlockEntities(Buf.In p) throws IOException {
        int n = p.varInt();
        if (n < 0 || n > 65536) throw new IOException("bad block entity count");
        for (int i = 0; i < n; i++) {
            p.u8();
            p.bytes(2);
            p.varInt();
            int t = p.u8();
            if (t != 0) skipNbt(p, t);
        }
    }

    private static long[] bitSet(Buf.In p) throws IOException {
        int n = p.varInt();
        if (n < 0 || n > 64) throw new IOException("bad bitset");
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = p.i64();
        return a;
    }

    private static boolean bit(long[] a, int i) {
        return i >> 6 < a.length && (a[i >> 6] >>> (i & 63) & 1) != 0;
    }

    /** The "light data" structure shared by chunk packets and light update packets. */
    private static void readLight(Buf.In p, Chunk c, int sections) throws IOException {
        int n = sections + 2;
        long[] skyMask = bitSet(p), blockMask = bitSet(p), emptySky = bitSet(p), emptyBlock = bitSet(p);
        byte[][] sky = c.sky != null && c.sky.length == n ? c.sky : new byte[n][];
        byte[][] block = c.block != null && c.block.length == n ? c.block : new byte[n][];
        boolean[] full = c.skyFull != null && c.skyFull.length == n ? c.skyFull : new boolean[n];
        if (c.sky == null) java.util.Arrays.fill(full, true);
        int count = p.varInt();
        for (int i = 0, k = 0; i < n && k < count; i++) {
            if (!bit(skyMask, i)) continue;
            int len = p.varInt();
            sky[i] = p.bytes(len);
            full[i] = false;
            k++;
        }
        count = p.varInt();
        for (int i = 0, k = 0; i < n && k < count; i++) {
            if (!bit(blockMask, i)) continue;
            int len = p.varInt();
            block[i] = p.bytes(len);
            k++;
        }
        for (int i = 0; i < n; i++) {
            if (bit(emptySky, i)) {
                sky[i] = null;
                full[i] = false; // sent as empty: really dark (e.g. deep underground)
            }
            if (bit(emptyBlock, i)) block[i] = null;
        }
        c.sky = sky;
        c.block = block;
        c.skyFull = full;
        c.lightVersion++;
        lightVersion++;
    }

    /** A light update packet (after the packet id): new light for some sections of a loaded chunk. */
    static void readLightUpdate(Buf.In p) throws IOException {
        int cx = p.varInt(), cz = p.varInt();
        Chunk c = CHUNKS.get(key(cx, cz));
        if (c == null || c.secs == null || !keepLight) return;
        readLight(p, c, c.secs.length);
        Changes ch = changes;
        if (ch != null) ch.light(cx, cz);
    }

    /**
     * Light at a world position as (sky << 4) | block, each 0..15; -1 if unknown (chunk or its light not loaded).
     * Above the world counts as full sky light, below as none.
     */
    static int lightAt(int wx, int wy, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null || c.secs == null || c.sky == null) return -1;
        int li = ((wy - minY) >> 4) + 1; // light section index
        if (li < 0) return 0;
        if (li >= c.sky.length) return 15 << 4;
        int idx = ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15);
        byte[] s = c.sky[li], b = c.block[li];
        int sky = s != null ? (s[idx >> 1] >> ((idx & 1) << 2)) & 15 : c.skyFull[li] ? 15 : 0;
        int bl = b != null ? (b[idx >> 1] >> ((idx & 1) << 2)) & 15 : 0;
        return sky << 4 | bl;
    }

    private static int waterShade(Section[] s, int x, int y, int z) {
        int depth = 0;
        for (int yy = y - 1; yy >= 0 && depth < 12; yy--, depth++) {
            int st = blockAt(s, x, yy, z);
            if (st < 0 || colorOfState(st) != WATER) break;
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

    static void skipNbt(Buf.In p, int t) throws IOException {
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

    static final class Section {
        Section biomes; // 4x4x4 biome ids (Play screen only), or null
        int[] direct; // set once a block in this section changes
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
            Section bio = readContainer(in, 64, 3, prefix); // biomes: kept only for the Play screen's colours
            if (keepLight) s.biomes = bio;
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
        if (s.direct != null) return s.direct[((y & 15) * 16 + z) * 16 + x];
        if (s.single >= 0) return s.single;
        int idx = ((y & 15) * 16 + z) * 16 + x;
        int per = 64 / s.bits;
        long v = (s.data[idx / per] >>> ((idx % per) * s.bits)) & ((1L << s.bits) - 1);
        if (s.palette != null) return v < s.palette.length ? s.palette[(int) v] : 0;
        return (int) v;
    }
}
