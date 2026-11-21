package app.afkbot;

/**
 * Builds the mesh of one 16x16x16 chunk section for the Play screen from the game's real block models (GameModels).
 * Pure Java (no Android), so the same code runs in the desktop preview. Output is three passes (opaque, cutout,
 * translucent) of quads in a compact vertex format:
 *
 * <pre>
 *  0  short x, y, z   section-local position * 512
 *  6  ushort u, v     atlas coordinates * 65535
 * 10  ubyte r, g, b   tint * face shade * ambient occlusion
 * 13  ubyte a         alpha
 * 14  ubyte sky, blk  light 0..15 * 17 (day/night is applied in the shader, so no re-mesh at dusk)
 * </pre>
 *
 * Lighting follows vanilla: smooth light per vertex averaged over the open cells in front of each corner, ambient
 * occlusion from the three neighbours, and fixed face shading (top 1.0, bottom 0.5, north/south 0.8, east/west 0.6).
 * Fluids and chests (which the game also draws outside the block-model system) are built here directly.
 */
final class GameMesher {
    static final int OPAQUE = 0, CUTOUT = 1, TRANSLUCENT = 2;
    static final int BYTES = 16;
    static final float POS = 512f;

    static final class Mesh {
        final int sx, sy, sz; // section coordinates
        final byte[][] data = new byte[3][];
        final int[] quads = new int[3];

        Mesh(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        boolean empty() {
            return quads[0] + quads[1] + quads[2] == 0;
        }
    }

    // ---- per block state classification (computed once per state) ----
    private static final int F_KNOWN = 1, F_AIR = 2, F_OPAQUE = 4, F_SAMECULL = 8, F_WATER = 16, F_LAVA = 32,
            F_CHEST = 128, F_WATERLOGGED = 256, F_INVISIBLE = 512, F_TRANSLUCENT = 1024;
    private final int[] info, blockOf, tintOf, chestOf, biomeKind;
    /** Biome colours (grass, foliage, water), or null for the fixed plains colours. */
    GameBiomes biomes;
    private int originX, originY, originZ;
    private final java.util.HashMap<Long, Integer> tintCache = new java.util.HashMap<Long, Integer>();
    private final GameAtlas atlas;
    private final GameModels models;
    private final ProtoDef.VersionData vd;
    private final int waterTile, lavaTile;

    GameMesher(ProtoDef.VersionData vd, GameAtlas atlas, GameModels models) {
        this.vd = vd;
        this.atlas = atlas;
        this.models = models;
        int states = vd.blockMin.length == 0 ? 0 : vd.blockMin[vd.blockMin.length - 1] + 4096;
        info = new int[states];
        blockOf = new int[states];
        tintOf = new int[states];
        chestOf = new int[states];
        biomeKind = new int[states];
        waterTile = Math.max(0, atlas.tile("block/water_still"));
        lavaTile = Math.max(0, atlas.tile("block/lava_still"));
    }

    private int info(int st) {
        if (st <= 0) return F_KNOWN | F_AIR;
        if (st >= info.length) return F_KNOWN | F_OPAQUE;
        int i = info[st];
        if (i != 0) return i;
        int b = vd.blockIndex(st);
        blockOf[st] = b;
        String id = b >= 0 && b < vd.blockIds.length ? vd.blockIds[b] : "";
        tintOf[st] = TexturePack.tintFor(id);
        biomeKind[st] = GameBiomes.kindOf(id);
        i = F_KNOWN;
        if (id.equals("air") || id.equals("cave_air") || id.equals("void_air")) i |= F_AIR;
        else if (id.equals("barrier") || id.equals("light") || id.equals("structure_void") || id.equals("moving_piston")) i |= F_INVISIBLE;
        else if (id.equals("water") || id.equals("bubble_column")) i |= F_WATER | F_SAMECULL;
        else if (id.equals("lava")) i |= F_LAVA | F_SAMECULL;
        else {
            if (!seeThroughFull(id) && coversAllSides(models.quads(st))) i |= F_OPAQUE;
            if (seeThroughFull(id)) i |= F_SAMECULL;
            if (translucent(id)) i |= F_TRANSLUCENT;
            String chest = TexturePack.chestTexture(id);
            if (chest != null) {
                int t = atlas.tile("entity/chest/" + chest);
                if (t >= 0) {
                    i |= F_CHEST;
                    chestOf[st] = t;
                }
            }
            if (id.equals("seagrass") || id.equals("tall_seagrass") || id.equals("kelp") || id.equals("kelp_plant")
                    || "true".equals(Shapes.prop(st, "waterlogged"))) i |= F_WATERLOGGED;
        }
        info[st] = i;
        return i;
    }

    /**
     * Like the game's occlusion check: a block hides its neighbours' faces only if its model covers each of the six
     * sides completely with an opaque texture (a lantern or flower pot on grass must not hide the grass top).
     */
    private static boolean coversAllSides(GameModels.Quad[] quads) {
        boolean[] covered = new boolean[6];
        for (GameModels.Quad q : quads) {
            if (q.cull < 0 || q.dir != q.cull || q.alpha != GameAtlas.OPAQUE) continue;
            int axis = q.dir <= 1 ? 1 : q.dir <= 3 ? 2 : 0;
            float a0 = 1, a1 = 0, b0 = 1, b1 = 0;
            int ua = axis == 0 ? 1 : 0, ub = axis == 2 ? 1 : 2;
            for (float[] p : q.pos) {
                a0 = Math.min(a0, p[ua]);
                a1 = Math.max(a1, p[ua]);
                b0 = Math.min(b0, p[ub]);
                b1 = Math.max(b1, p[ub]);
            }
            if (a0 <= 0.001f && a1 >= 0.999f && b0 <= 0.001f && b1 >= 0.999f) covered[q.dir] = true;
        }
        for (boolean c : covered) if (!c) return false;
        return true;
    }

    private static boolean seeThroughFull(String id) {
        return id.contains("glass") || id.endsWith("leaves") || id.equals("ice") || id.equals("frosted_ice") || id.equals("spawner")
                || id.equals("trial_spawner") || id.equals("vault") || id.equals("slime_block") || id.equals("honey_block")
                || id.endsWith("copper_grate") || id.equals("mangrove_roots");
    }

    private static boolean translucent(String id) {
        return id.equals("ice") || id.equals("frosted_ice") || id.contains("stained_glass") || id.equals("tinted_glass")
                || id.equals("slime_block") || id.equals("honey_block") || id.equals("nether_portal");
    }

    // ---- snapshot of the section plus a one-block border ----
    private final int[] st = new int[18 * 18 * 18];
    private final int[] lt = new int[18 * 18 * 18];

    private static int idx(int x, int y, int z) {
        return ((y + 1) * 18 + (z + 1)) * 18 + (x + 1);
    }

    /** Copies blocks and light around a section. Returns false if the section's own chunk isn't loaded. */
    private boolean snapshot(int sx, int sy, int sz) {
        int bx = sx << 4, by = sy << 4, bz = sz << 4;
        if (!MapData.loaded(bx, bz)) return false;
        MapData.copyBlocks(bx - 1, by - 1, bz - 1, 18, st, lt);
        return true;
    }

    // ---- output buffers (one growing byte array per pass) ----
    private byte[][] out = {new byte[1 << 15], new byte[1 << 13], new byte[1 << 13]};
    private final int[] len = new int[3];

    /** Meshes one section. Returns null if its chunk isn't loaded. Not thread safe: one mesher per thread. */
    Mesh build(int sx, int sy, int sz) {
        if (!MapData.loaded(sx << 4, sz << 4)) return null;
        Mesh m = new Mesh(sx, sy, sz);
        if (MapData.sectionEmpty(sx, sy, sz)) return m; // all air: nothing to draw (neighbours draw their own faces)
        if (!snapshot(sx, sy, sz)) return null;
        len[0] = len[1] = len[2] = 0;
        originX = sx << 4;
        originY = sy << 4;
        originZ = sz << 4;
        tintCache.clear();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int s = st[idx(x, y, z)];
                    if (s <= 0) continue;
                    int f = info(s);
                    if ((f & (F_AIR | F_INVISIBLE)) != 0) continue;
                    if ((f & (F_WATER | F_LAVA)) != 0) {
                        fluid(x, y, z, f);
                        continue;
                    }
                    if ((f & F_WATERLOGGED) != 0) fluid(x, y, z, F_WATER);
                    if ((f & F_CHEST) != 0) chest(x, y, z, s);
                    else block(x, y, z, s, f);
                }
        for (int p = 0; p < 3; p++) {
            m.quads[p] = len[p] / (BYTES * 4);
            if (m.quads[p] > 0) m.data[p] = java.util.Arrays.copyOf(out[p], len[p]);
        }
        return m;
    }

    // ---- faces ----
    private static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, EAST = 4, WEST = 5;
    private static final int[][] DIR = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private static final float[] SHADE = {1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};
    private static final float[] AO = {0.42f, 0.6f, 0.8f, 1f};

    /**
     * A block's biome colour, blended over the 3x3 columns around it (like the game's biome blending, so colours fade
     * across biome borders instead of changing in a hard line). kind 0 = a fixed tint.
     */
    private int biomeTint(int kind, int x, int y, int z, int fallback) {
        GameBiomes b = biomes;
        if (kind == 0 || b == null) return fallback;
        long key = ((long) (x + 1) << 20) | ((long) (z + 1) << 12) | ((long) ((y + 4) >> 2) << 4) | kind;
        Integer have = tintCache.get(key);
        if (have != null) return have;
        int wx = originX + x, wy = originY + y, wz = originZ + z, r = 0, g = 0, bl = 0, n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int c = b.color(kind, MapData.biomeAt(wx + dx * 2, wy, wz + dz * 2), fallback);
                r += (c >> 16) & 255;
                g += (c >> 8) & 255;
                bl += c & 255;
                n++;
            }
        int c = (r / n) << 16 | (g / n) << 8 | (bl / n);
        tintCache.put(key, c);
        return c;
    }

    /** Is the face of block s towards direction d hidden by its neighbour? */
    private boolean hidden(int x, int y, int z, int s, int f, int d) {
        int[] v = DIR[d];
        int ns = st[idx(x + v[0], y + v[1], z + v[2])];
        if (ns <= 0) return false;
        int nf = info(ns);
        if ((nf & F_OPAQUE) != 0) return true;
        return (f & F_SAMECULL) != 0 && (nf & F_SAMECULL) != 0 && blockOf[ns] == blockOf[s];
    }

    private void block(int x, int y, int z, int s, int f) {
        GameModels.Quad[] quads = models.quads(s);
        if (quads.length == 0) {
            fallbackCube(x, y, z, s, f);
            return;
        }
        for (GameModels.Quad q : quads) {
            if (q.cull >= 0 && hidden(x, y, z, s, f, q.cull)) continue;
            int pass = (f & F_OPAQUE) != 0 && q.alpha == GameAtlas.OPAQUE ? OPAQUE
                    : (f & F_TRANSLUCENT) != 0 || q.alpha == GameAtlas.TRANSLUCENT ? TRANSLUCENT : CUTOUT;
            int color = q.tint && tintOf[s] != 0 ? biomeTint(biomeKind[s], x, y, z, tintOf[s]) : 0xFFFFFF;
            emit(pass, x, y, z, q.pos, q.uv, q.dir, q.shade ? SHADE[q.shadeDir] : 1f, q.ao, color, 255);
        }
    }

    /** Blocks without model geometry (rare: some entity-drawn blocks): a cube in the block's map colour. */
    private void fallbackCube(int x, int y, int z, int s, int f) {
        int color = MapData.colorOf(s);
        if (color == 0) return;
        float[] unit = {0, 0, 0, 1, 1, 1};
        for (int d = 0; d < 6; d++) {
            if (hidden(x, y, z, s, f, d)) continue;
            emit((f & F_OPAQUE) != 0 ? OPAQUE : CUTOUT, x, y, z, cornersOf(d, unit), whiteUv(), d, SHADE[d], true, color, 255);
        }
    }

    private static float[][] whiteUv() {
        float e = 0.5f / GameAtlas.SIZE * 8; // inside tile 0 (plain white)
        return new float[][]{{e, e}, {e, e}, {e, e}, {e, e}};
    }

    /**
     * Writes one quad. Faces aligned with an axis get smooth light and ambient occlusion from the cells in front of
     * them (the neighbour for faces on the block boundary, else the block itself); rotated faces use the block's light.
     */
    private void emit(int pass, int x, int y, int z, float[][] pos, float[][] uv, int dir, float shade, boolean useAo, int color, int alpha) {
        int[] ao = {3, 3, 3, 3}, sky = new int[4], blk = new int[4];
        if (dir >= 0) {
            int axis = dir <= 1 ? 1 : dir <= 3 ? 2 : 0;
            float plane = pos[0][axis];
            boolean onEdge = DIR[dir][axis] > 0 ? plane >= 0.999f : plane <= 0.001f;
            int[] d = DIR[dir];
            int ox = x + (onEdge ? d[0] : 0), oy = y + (onEdge ? d[1] : 0), oz = z + (onEdge ? d[2] : 0);
            for (int k = 0; k < 4; k++) corner(dir, pos[k], ox, oy, oz, k, ao, sky, blk);
            if (!useAo) ao[0] = ao[1] = ao[2] = ao[3] = 3;
        } else {
            int l = lightOf(x, y, z);
            for (int k = 0; k < 4; k++) {
                sky[k] = (l >> 4) * 17;
                blk[k] = (l & 15) * 17;
            }
        }
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        // flip the quad's diagonal so ambient occlusion interpolates symmetrically
        int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
        ensure(pass, BYTES * 4);
        for (int n = 0; n < 4; n++) {
            int k = (start + n) & 3;
            float m = shade * AO[ao[k]];
            vertex(pass, x + pos[k][0], y + pos[k][1], z + pos[k][2], uv[k][0], uv[k][1], (int) (r * m), (int) (g * m), (int) (b * m), alpha, sky[k], blk[k]);
        }
    }

    /** AO level (0..3) and smooth light for one corner of an axis-aligned face. */
    private void corner(int face, float[] p, int ox, int oy, int oz, int k, int[] ao, int[] sky, int[] blk) {
        int ax1, ax2; // the two axes along the face
        if (face == UP || face == DOWN) {
            ax1 = 0;
            ax2 = 2;
        } else if (face == NORTH || face == SOUTH) {
            ax1 = 0;
            ax2 = 1;
        } else {
            ax1 = 2;
            ax2 = 1;
        }
        int s1 = p[ax1] > 0.5f ? 1 : -1, s2 = p[ax2] > 0.5f ? 1 : -1;
        int[] o = {ox, oy, oz};
        int[] a = o.clone(), b = o.clone(), cc = o.clone();
        a[ax1] += s1;
        b[ax2] += s2;
        cc[ax1] += s1;
        cc[ax2] += s2;
        boolean side1 = solid(a), side2 = solid(b), corner = solid(cc);
        ao[k] = side1 && side2 ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
        int l0 = light(o), ls = l0 >> 4, lb = l0 & 15, n = 1;
        if (!side1) {
            int l = light(a);
            ls += l >> 4;
            lb += l & 15;
            n++;
        }
        if (!side2) {
            int l = light(b);
            ls += l >> 4;
            lb += l & 15;
            n++;
        }
        if (!corner && !(side1 && side2)) {
            int l = light(cc);
            ls += l >> 4;
            lb += l & 15;
            n++;
        }
        sky[k] = ls * 17 / n;
        blk[k] = lb * 17 / n;
    }

    private boolean solid(int[] p) {
        if (p[0] < -1 || p[0] > 16 || p[1] < -1 || p[1] > 16 || p[2] < -1 || p[2] > 16) return false;
        int s = st[idx(p[0], p[1], p[2])];
        return s > 0 && (info(s) & F_OPAQUE) != 0;
    }

    /** Light as (sky << 4) | block; full daylight when the server sent none for that spot. */
    private int light(int[] p) {
        return lightOf(p[0], p[1], p[2]);
    }

    private int lightOf(int x, int y, int z) {
        int l = lt[idx(Math.max(-1, Math.min(16, x)), Math.max(-1, Math.min(16, y)), Math.max(-1, Math.min(16, z)))];
        return l < 0 ? 0xF0 : l;
    }

    // ---- fluids and chests ----

    /** Water and lava: a slightly lowered surface unless more fluid is above; faces only towards open cells. */
    private void fluid(int x, int y, int z, int f) {
        boolean lava = (f & F_LAVA) != 0;
        int above = st[idx(x, y + 1, z)];
        int af = above <= 0 ? F_AIR : info(above);
        boolean fullAbove = lava ? (af & F_LAVA) != 0 : (af & (F_WATER | F_WATERLOGGED)) != 0;
        float top = fullAbove ? 1f : 0.875f;
        int tile = lava ? lavaTile : waterTile;
        float[] box = {0, 0, 0, 1, top, 1};
        for (int d = 0; d < 6; d++) {
            int[] v = DIR[d];
            int ns = st[idx(x + v[0], y + v[1], z + v[2])];
            int nf = ns <= 0 ? F_AIR : info(ns);
            boolean same = lava ? (nf & F_LAVA) != 0 : (nf & (F_WATER | F_WATERLOGGED)) != 0;
            if (same || (nf & F_OPAQUE) != 0) continue;
            if (d == UP && fullAbove) continue;
            float[][] c = cornersOf(d, box);
            emit(lava ? OPAQUE : TRANSLUCENT, x, y, z, c, tileUv(tile, d, box), d, SHADE[d], true, lava ? 0xFFFFFF : biomeTint(GameBiomes.WATER, x, y, z, 0x3F76E4), lava ? 255 : 200);
        }
    }

    /** Tile uvs for a face of a box, using the box's own position on the texture (like the game's default uvs). */
    private static float[][] tileUv(int tile, int d, float[] q) {
        float x0 = q[0], y0 = q[1], z0 = q[2], x1 = q[3], y1 = q[4], z1 = q[5];
        float[][] u;
        switch (d) {
            case UP: u = new float[][]{{x0, z1}, {x1, z1}, {x1, z0}, {x0, z0}}; break;
            case DOWN: u = new float[][]{{x0, 1 - z0}, {x1, 1 - z0}, {x1, 1 - z1}, {x0, 1 - z1}}; break;
            case NORTH: u = new float[][]{{1 - x1, 1 - y0}, {1 - x0, 1 - y0}, {1 - x0, 1 - y1}, {1 - x1, 1 - y1}}; break;
            case SOUTH: u = new float[][]{{x0, 1 - y0}, {x1, 1 - y0}, {x1, 1 - y1}, {x0, 1 - y1}}; break;
            case EAST: u = new float[][]{{1 - z1, 1 - y0}, {1 - z0, 1 - y0}, {1 - z0, 1 - y1}, {1 - z1, 1 - y1}}; break;
            default: u = new float[][]{{z0, 1 - y0}, {z1, 1 - y0}, {z1, 1 - y1}, {z0, 1 - y1}}; break;
        }
        int tu = tile % GameAtlas.GRID, tv = tile / GameAtlas.GRID;
        for (float[] k : u) {
            k[0] = (tu + Math.max(0, Math.min(1, k[0]))) / GameAtlas.GRID;
            k[1] = (tv + Math.max(0, Math.min(1, k[1]))) / GameAtlas.GRID;
        }
        return u;
    }

    /** Minecraft's chest model (body, lid, latch) with the 64x64 chest texture, turned to face the right way. */
    private void chest(int x, int y, int z, int s) {
        int tile = chestOf[s];
        String facing = Shapes.prop(s, "facing");
        float yaw = "west".equals(facing) ? 90 : "north".equals(facing) ? 180 : "east".equals(facing) ? 270 : 0;
        int l = lightOf(x, y, z);
        modelBox(x, y, z, yaw, tile, l, 1, 0, 1, 14, 10, 14, 0, 19);
        modelBox(x, y, z, yaw, tile, l, 1, 9, 1, 14, 5, 14, 0, 0);
        modelBox(x, y, z, yaw, tile, l, 7, 7, 15, 2, 4, 1, 0, 0);
    }

    /** A box in pixel units with Minecraft's "box UV" layout on a 64x64 texture, rotated around the block centre. */
    private void modelBox(int x, int y, int z, float yaw, int tile, int light, float bx, float by, float bz, float w, float h, float d, float u, float v) {
        float[][] rect = {{u + d + w, v, w, d}, {u + d, v, w, d}, {u + d, v + d, w, h}, {u + d + w + d, v + d, w, h},
                {u + d + w, v + d, d, h}, {u, v + d, d, h}};
        double a = Math.toRadians(yaw), cos = Math.cos(a), sin = Math.sin(a);
        int tx = (tile % GameAtlas.GRID) * GameAtlas.TILE, ty = (tile / GameAtlas.GRID) * GameAtlas.TILE;
        int sk = (light >> 4) * 17, bk = (light & 15) * 17;
        float[] unit = {0, 0, 0, 1, 1, 1};
        for (int f = 0; f < 6; f++) {
            float[][] c = cornersOf(f, unit);
            ensure(OPAQUE, BYTES * 4);
            for (int k = 0; k < 4; k++) {
                float fx = c[k][0], fy = c[k][1], fz = c[k][2];
                float uu, vv;
                switch (f) {
                    case 0: uu = fx; vv = fz; break;
                    case 1: uu = fx; vv = 1 - fz; break;
                    case 2: uu = 1 - fx; vv = 1 - fy; break;
                    case 3: uu = fx; vv = 1 - fy; break;
                    case 4: uu = 1 - fz; vv = 1 - fy; break;
                    default: uu = fz; vv = 1 - fy; break;
                }
                double lx = bx + fx * w - 8, lz = bz + fz * d - 8, ly = by + fy * h;
                double rx = lx * cos - lz * sin, rz = lx * sin + lz * cos;
                float[] r = rect[f];
                int m = (int) (255 * SHADE[f]);
                vertex(OPAQUE, x + (float) (rx + 8) / 16f, y + (float) ly / 16f, z + (float) (rz + 8) / 16f,
                        (tx + r[0] + uu * r[2]) / GameAtlas.SIZE, (ty + r[1] + vv * r[3]) / GameAtlas.SIZE, m, m, m, 255, sk, bk);
            }
        }
    }

    static float[][] cornersOf(int face, float[] q) {
        float x0 = q[0], y0 = q[1], z0 = q[2], x1 = q[3], y1 = q[4], z1 = q[5];
        switch (face) {
            case UP: return new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
            case DOWN: return new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case NORTH: return new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case SOUTH: return new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case EAST: return new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
            default: return new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
        }
    }

    // ---- vertex writing ----

    private void ensure(int pass, int more) {
        if (len[pass] + more > out[pass].length) out[pass] = java.util.Arrays.copyOf(out[pass], Math.max(out[pass].length * 2, len[pass] + more));
    }

    private void vertex(int pass, float x, float y, float z, float u, float v, int r, int g, int b, int a, int sky, int blk) {
        byte[] o = out[pass];
        int p = len[pass];
        putShort(o, p, Math.round(x * POS));
        putShort(o, p + 2, Math.round(y * POS));
        putShort(o, p + 4, Math.round(z * POS));
        putShort(o, p + 6, Math.round(u * 65535));
        putShort(o, p + 8, Math.round(v * 65535));
        o[p + 10] = (byte) Math.max(0, Math.min(255, r));
        o[p + 11] = (byte) Math.max(0, Math.min(255, g));
        o[p + 12] = (byte) Math.max(0, Math.min(255, b));
        o[p + 13] = (byte) a;
        o[p + 14] = (byte) Math.min(255, sky);
        o[p + 15] = (byte) Math.min(255, blk);
        len[pass] = p + BYTES;
    }

    /** Little-endian, which is what both Android GPUs and the desktop preview read. */
    private static void putShort(byte[] o, int p, int v) {
        o[p] = (byte) v;
        o[p + 1] = (byte) (v >> 8);
    }

    static int getShort(byte[] o, int p) {
        return (short) ((o[p] & 255) | (o[p + 1] << 8));
    }
}
