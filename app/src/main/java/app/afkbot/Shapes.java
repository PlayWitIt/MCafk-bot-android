package app.afkbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Real block shapes (in block-local coordinates 0..1) for collision, drawing and tapping:
 * doors (open/closed, hinge), trapdoors, gates, slabs, stairs, fences, walls, panes, carpets, snow,
 * beds, chests, buttons, levers, pressure plates. Everything else is a full cube or empty.
 */
final class Shapes {
    private Shapes() {
    }

    static final float[][] FULL = {{0, 0, 0, 1, 1, 1}};
    static final float[][] NONE = {};

    static final int EMPTY = 0, CUBE = 1, SLAB = 2, STAIRS = 3, DOOR = 4, TRAPDOOR = 5, GATE = 6, FENCE = 7, WALL = 8,
            PANE = 9, CARPET = 10, SNOW = 11, BED = 12, CHEST = 13, PATH = 14, BUTTON = 15, LEVER = 16, PLATE = 17,
            SINK = 18, LADDER = 19, HONEY = 20;

    /** Internal block name (e.g. "packed_ice") of a state, or "". */
    static String id(int state) {
        ProtoDef.VersionData vd = data;
        if (vd == null || state <= 0) return "";
        int i = vd.blockIndex(state);
        return i >= 0 && i < vd.blockIds.length ? vd.blockIds[i] : "";
    }

    private static ProtoDef.VersionData data;
    private static float[][][] collisionCache = new float[0][][], outlineCache = new float[0][][];

    static synchronized void setVersion(ProtoDef.VersionData vd) {
        data = vd;
        int states = vd == null || vd.blockMin.length == 0 ? 0 : vd.blockMin[vd.blockMin.length - 1] + 4096;
        collisionCache = new float[states][][];
        outlineCache = new float[states][][];
    }

    /** Shape type of a block state (CUBE if unknown). */
    static int type(int state) {
        ProtoDef.VersionData vd = data;
        if (vd == null || state <= 0) return state <= 0 ? EMPTY : CUBE;
        int i = vd.blockIndex(state);
        return i >= 0 && i < vd.shapes.length ? vd.shapes[i] : CUBE;
    }

    static boolean known() {
        return data != null;
    }

    /** True for blocks that fill the whole cube (used for face culling and ambient occlusion). */
    static boolean isFull(int state) {
        int t = type(state);
        if (t == CUBE) return true;
        if (t == SLAB) return "double".equals(prop(state, "type"));
        if (t == SINK || t == HONEY) return true; // look like full blocks
        return false;
    }

    /** Boxes the player collides with. */
    static float[][] collision(int state) {
        if (state <= 0) return NONE;
        float[][][] c = collisionCache;
        if (state < c.length && c[state] != null) return c[state];
        float[][] r = exact(state);
        if (r == null) r = compute(state, true);
        if (state < c.length) c[state] = r;
        return r;
    }

    /** Minecraft's own collision boxes for this state (from minecraft-data), or null if not known. */
    private static float[][] exact(int state) {
        ProtoDef.VersionData vd = data;
        if (vd == null || vd.collision == null) return null;
        int bi = vd.blockIndex(state);
        if (bi < 0 || bi >= vd.collision.length || vd.collision[bi] == null) return null;
        int[] ids = vd.collision[bi];
        int off = state - vd.blockMin[bi];
        int id = ids.length == 1 ? ids[0] : off >= 0 && off < ids.length ? ids[off] : -1;
        if (id < 0 || id >= vd.collisionShapes.length || vd.collisionShapes[id] == null) return null;
        return vd.collisionShapes[id];
    }

    /** Boxes for drawing and tapping (buttons, levers and plates have an outline but no collision). */
    static float[][] outline(int state) {
        if (state <= 0) return NONE;
        float[][][] c = outlineCache;
        if (state < c.length && c[state] != null) return c[state];
        float[][] r = compute(state, false);
        if (state < c.length) c[state] = r;
        return r;
    }

    /** Value of a block-state property, e.g. prop(state, "open") -> "true". */
    static String prop(int state, String name) {
        ProtoDef.VersionData vd = data;
        if (vd == null) return null;
        int bi = vd.blockIndex(state);
        if (bi < 0 || bi >= vd.propNames.length) return null;
        String[] names = vd.propNames[bi];
        String[][] vals = vd.propValues[bi];
        int idx = state - vd.blockMin[bi];
        // the first property is the most significant "digit"
        String found = null;
        for (int p = names.length - 1; p >= 0; p--) {
            int n = vals[p].length;
            if (names[p].equals(name)) found = vals[p][idx % n];
            idx /= n;
        }
        return found;
    }

    private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[]{x0 / 16f, y0 / 16f, z0 / 16f, x1 / 16f, y1 / 16f, z1 / 16f};
    }

    private static float[][] compute(int state, boolean collision) {
        int t = type(state);
        String facing = prop(state, "facing");
        switch (t) {
            case EMPTY:
                return NONE;
            case CUBE:
                return FULL;
            case SLAB: {
                String ty = prop(state, "type");
                if ("top".equals(ty)) return new float[][]{box(0, 8, 0, 16, 16, 16)};
                if ("bottom".equals(ty)) return new float[][]{box(0, 0, 0, 16, 8, 16)};
                return FULL;
            }
            case STAIRS: {
                boolean top = "top".equals(prop(state, "half"));
                float[] base = top ? box(0, 8, 0, 16, 16, 16) : box(0, 0, 0, 16, 8, 16);
                float y0 = top ? 0 : 8, y1 = top ? 8 : 16;
                float[] step;
                if ("south".equals(facing)) step = box(0, y0, 8, 16, y1, 16);
                else if ("west".equals(facing)) step = box(0, y0, 0, 8, y1, 16);
                else if ("east".equals(facing)) step = box(8, y0, 0, 16, y1, 16);
                else step = box(0, y0, 0, 16, y1, 8);
                return new float[][]{base, step};
            }
            case DOOR: {
                boolean open = "true".equals(prop(state, "open"));
                boolean right = "right".equals(prop(state, "hinge"));
                String side;
                if ("south".equals(facing)) side = !open ? "S" : right ? "E" : "W";
                else if ("west".equals(facing)) side = !open ? "W" : right ? "S" : "N";
                else if ("north".equals(facing)) side = !open ? "N" : right ? "W" : "E";
                else side = !open ? "E" : right ? "N" : "S";
                return new float[][]{plate(side)};
            }
            case TRAPDOOR: {
                boolean open = "true".equals(prop(state, "open"));
                if (!open) return new float[][]{"top".equals(prop(state, "half")) ? box(0, 13, 0, 16, 16, 16) : box(0, 0, 0, 16, 3, 16)};
                if ("south".equals(facing)) return new float[][]{plate("S")};
                if ("west".equals(facing)) return new float[][]{plate("W")};
                if ("east".equals(facing)) return new float[][]{plate("E")};
                return new float[][]{plate("N")};
            }
            case GATE: {
                boolean open = "true".equals(prop(state, "open"));
                if (open && collision) return NONE;
                float h = collision ? 24 : 16;
                boolean ns = "north".equals(facing) || "south".equals(facing);
                if (open) return NONE;
                return new float[][]{ns ? box(0, 0, 6, 16, h, 10) : box(6, 0, 0, 10, h, 16)};
            }
            case FENCE:
                return posts(state, 6, 10, collision ? 24 : 16, false);
            case WALL:
                return posts(state, 5, 11, collision ? 24 : 16, true);
            case PANE:
                return posts(state, 7, 9, 16, false);
            case CARPET:
                return new float[][]{box(0, 0, 0, 16, 1, 16)};
            case SNOW: {
                int layers = 1;
                try {
                    layers = Integer.parseInt(prop(state, "layers"));
                } catch (Exception ignored) {
                }
                int h = (collision ? layers - 1 : layers) * 2;
                return h <= 0 ? NONE : new float[][]{box(0, 0, 0, 16, h, 16)};
            }
            case BED:
                return new float[][]{box(0, 0, 0, 16, 9, 16)};
            case CHEST:
                return new float[][]{box(1, 0, 1, 15, 14, 15)};
            case PATH:
                return new float[][]{box(0, 0, 0, 16, 15, 16)};
            case BUTTON:
            case LEVER: {
                if (collision) return NONE;
                boolean lever = t == LEVER;
                String face = prop(state, "face");
                float a = lever ? 4 : 5, b = lever ? 12 : 11, d = lever ? 6 : 2;
                boolean ns = "north".equals(facing) || "south".equals(facing);
                if ("floor".equals(face)) return new float[][]{ns ? box(a, 0, 6, b, d, 10) : box(6, 0, a, 10, d, b)};
                if ("ceiling".equals(face)) return new float[][]{ns ? box(a, 16 - d, 6, b, 16, 10) : box(6, 16 - d, a, 10, 16, b)};
                float y0 = lever ? 3 : 6, y1 = lever ? 13 : 10;
                if ("north".equals(facing)) return new float[][]{box(a, y0, 16 - d, b, y1, 16)};
                if ("south".equals(facing)) return new float[][]{box(a, y0, 0, b, y1, d)};
                if ("west".equals(facing)) return new float[][]{box(16 - d, y0, a, 16, y1, b)};
                return new float[][]{box(0, y0, a, d, y1, b)};
            }
            case PLATE:
                return collision ? NONE : new float[][]{box(1, 0, 1, 15, 1, 15)};
            case SINK: // soul sand, mud: you sink 2 pixels in
                return collision ? new float[][]{box(0, 0, 0, 16, 14, 16)} : FULL;
            case HONEY:
                return collision ? new float[][]{box(1, 0, 1, 15, 15, 15)} : FULL;
            case LADDER:
                if ("south".equals(facing)) return new float[][]{box(0, 0, 0, 16, 16, 3)};
                if ("west".equals(facing)) return new float[][]{box(13, 0, 0, 16, 16, 16)};
                if ("east".equals(facing)) return new float[][]{box(0, 0, 0, 3, 16, 16)};
                return new float[][]{box(0, 0, 13, 16, 16, 16)};
            default:
                return FULL;
        }
    }

    /** A 3/16-thick plate along one side of the block (N, S, E or W). */
    private static float[] plate(String side) {
        switch (side) {
            case "N":
                return box(0, 0, 13, 16, 16, 16);
            case "S":
                return box(0, 0, 0, 16, 16, 3);
            case "W":
                return box(13, 0, 0, 16, 16, 16);
            default:
                return box(0, 0, 0, 3, 16, 16);
        }
    }

    /** Fence / wall / pane: a centre post plus arms towards connected sides. */
    private static float[][] posts(int state, float lo, float hi, float h, boolean wall) {
        List<float[]> out = new ArrayList<float[]>();
        boolean up = !wall || !"false".equals(prop(state, "up"));
        if (up) out.add(box(wall ? 4 : lo, 0, wall ? 4 : lo, wall ? 12 : hi, h, wall ? 12 : hi));
        if (conn(prop(state, "north"))) out.add(box(lo, 0, 0, hi, h, hi));
        if (conn(prop(state, "south"))) out.add(box(lo, 0, lo, hi, h, 16));
        if (conn(prop(state, "west"))) out.add(box(0, 0, lo, hi, h, hi));
        if (conn(prop(state, "east"))) out.add(box(lo, 0, lo, 16, h, hi));
        if (out.isEmpty()) out.add(box(lo, 0, lo, hi, h, hi));
        return out.toArray(new float[0][]);
    }

    private static boolean conn(String v) {
        return v != null && !"false".equals(v) && !"none".equals(v);
    }
}
