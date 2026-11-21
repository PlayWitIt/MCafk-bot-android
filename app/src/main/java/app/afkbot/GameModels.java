package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minecraft's block models, baked into quads per block state, for the Play screen. Implements the game's model format
 * from its JSON files: blockstate "variants" (property matching) and "multipart" (when/OR/AND), model parents and
 * texture variables, elements (from/to in 1/16 block, per-face uv, face rotation, cullface, tintindex, shade), element
 * rotation (one axis, with rescale) and variant x/y rotation. Platform neutral.
 */
final class GameModels {
    /** One baked quad in block-local coordinates (0..1). Corners are CCW seen from outside. */
    static final class Quad {
        final float[][] pos = new float[4][3];
        final float[][] uv = new float[4][2]; // atlas coordinates 0..1
        int dir = -1; // axis-aligned face direction (0 up 1 down 2 north 3 south 4 east 5 west), or -1 if rotated
        int shadeDir; // direction used for face shading
        int cull = -1; // direction whose neighbour hides this face, or -1
        boolean tint, shade = true, ao = true;
        int alpha; // GameAtlas.OPAQUE / CUTOUT / TRANSLUCENT of its texture
    }

    private static final Quad[] NONE = new Quad[0];
    /** The last model that failed to bake (for diagnostics). */
    volatile Exception lastError;
    private static final String[] DIR_NAMES = {"up", "down", "north", "south", "east", "west"};
    private static final int[][] DIRV = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};

    private final Map<String, byte[]> files;
    private final GameAtlas atlas;
    private final ProtoDef.VersionData vd;
    private final Quad[][] cache;
    private final Map<String, JSONObject> jsonCache = new HashMap<String, JSONObject>();
    private final Map<String, Object[]> modelCache = new HashMap<String, Object[]>();

    GameModels(Map<String, byte[]> files, GameAtlas atlas, ProtoDef.VersionData vd) {
        this.files = files;
        this.atlas = atlas;
        this.vd = vd;
        int states = vd.blockMin.length == 0 ? 0 : vd.blockMin[vd.blockMin.length - 1] + 4096;
        cache = new Quad[states][];
    }

    /** Baked quads of a block state (empty if the block has no model geometry, e.g. chests or air). */
    synchronized Quad[] quads(int state) {
        if (state <= 0 || state >= cache.length) return NONE;
        Quad[] q = cache[state];
        if (q != null) return q;
        try {
            q = bake(state);
        } catch (Exception e) {
            lastError = e;
            q = NONE;
        }
        cache[state] = q;
        return q;
    }

    // ---------------------------------------------------------------- blockstates

    private Quad[] bake(int state) throws Exception {
        int b = vd.blockIndex(state);
        if (b < 0 || b >= vd.blockIds.length) return NONE;
        JSONObject bs = json("assets/minecraft/blockstates/" + vd.blockIds[b] + ".json");
        if (bs == null) return NONE;
        List<Quad> out = new ArrayList<Quad>();
        JSONObject variants = bs.optJSONObject("variants");
        if (variants != null) {
            Iterator<String> it = variants.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (matchesVariant(b, state, key)) {
                    addVariant(first(variants.get(key)), out);
                    break;
                }
            }
        }
        JSONArray parts = bs.optJSONArray("multipart");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                JSONObject when = part.optJSONObject("when");
                if (when == null || matchesWhen(b, state, when)) addVariant(first(part.get("apply")), out);
            }
        }
        return out.toArray(NONE);
    }

    private static JSONObject first(Object v) throws org.json.JSONException {
        return v instanceof JSONArray ? ((JSONArray) v).getJSONObject(0) : (JSONObject) v;
    }

    private String prop(int b, int state, String name) {
        String[] names = vd.propNames[b];
        String[][] vals = vd.propValues[b];
        int idx = state - vd.blockMin[b];
        String found = null;
        for (int p = names.length - 1; p >= 0; p--) { // the first property is the most significant "digit"
            int n = vals[p].length;
            if (names[p].equals(name)) found = vals[p][idx % n];
            idx /= n;
        }
        return found;
    }

    /** "facing=east,half=bottom": every listed property must match ("" matches anything). */
    private boolean matchesVariant(int b, int state, String key) {
        if (key.isEmpty() || key.equals("normal")) return true;
        for (String kv : key.split(",")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            String have = prop(b, state, kv.substring(0, e));
            if (have == null || !have.equals(kv.substring(e + 1))) return false;
        }
        return true;
    }

    private boolean matchesWhen(int b, int state, JSONObject when) throws org.json.JSONException {
        if (when.has("OR")) {
            JSONArray a = when.getJSONArray("OR");
            for (int i = 0; i < a.length(); i++) if (matchesWhen(b, state, a.getJSONObject(i))) return true;
            return false;
        }
        if (when.has("AND")) {
            JSONArray a = when.getJSONArray("AND");
            for (int i = 0; i < a.length(); i++) if (!matchesWhen(b, state, a.getJSONObject(i))) return false;
            return true;
        }
        Iterator<String> it = when.keys();
        while (it.hasNext()) {
            String k = it.next();
            String have = prop(b, state, k);
            if (have == null) return false;
            boolean any = false;
            for (String v : String.valueOf(when.get(k)).split("\\|")) if (v.equals(have)) any = true;
            if (!any) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- models

    private void addVariant(JSONObject v, List<Quad> out) throws Exception {
        Object[] m = model(stripNs(v.getString("model")));
        if (m == null) return;
        @SuppressWarnings("unchecked") Map<String, String> textures = (Map<String, String>) m[0];
        JSONArray elements = (JSONArray) m[1];
        boolean ao = (Boolean) m[2];
        if (elements == null) return;
        int rx = v.optInt("x", 0), ry = v.optInt("y", 0);
        for (int e = 0; e < elements.length(); e++) element(elements.getJSONObject(e), textures, ao, rx, ry, out);
    }

    /** {textures, elements, ambientocclusion} with the parent chain resolved (child values win). */
    private Object[] model(String name) throws Exception {
        if (modelCache.containsKey(name)) return modelCache.get(name);
        Map<String, String> textures = new HashMap<String, String>();
        JSONArray elements = null;
        boolean ao = true;
        String m = name;
        for (int depth = 0; m != null && depth < 16; depth++) {
            JSONObject j = json("assets/minecraft/models/" + stripNs(m) + ".json");
            if (j == null) break;
            JSONObject tx = j.optJSONObject("textures");
            if (tx != null) {
                Iterator<String> it = tx.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!textures.containsKey(k)) textures.put(k, tx.getString(k));
                }
            }
            if (elements == null && j.has("elements")) elements = j.getJSONArray("elements");
            if (j.has("ambientocclusion") && !j.optBoolean("ambientocclusion", true)) ao = false;
            m = j.optString("parent", null);
        }
        Object[] r = {textures, elements, ao};
        modelCache.put(name, r);
        return r;
    }

    private String texture(Map<String, String> textures, String ref) {
        for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) ref = textures.get(ref.substring(1));
        if (ref == null || ref.startsWith("#")) return null;
        return stripNs(ref);
    }

    private void element(JSONObject el, Map<String, String> textures, boolean ao, int rx, int ry, List<Quad> out) throws Exception {
        JSONArray f = el.getJSONArray("from"), t = el.getJSONArray("to");
        float x0 = (float) f.getDouble(0), y0 = (float) f.getDouble(1), z0 = (float) f.getDouble(2);
        float x1 = (float) t.getDouble(0), y1 = (float) t.getDouble(1), z1 = (float) t.getDouble(2);
        JSONObject faces = el.optJSONObject("faces");
        if (faces == null) return;
        JSONObject rot = el.optJSONObject("rotation");
        boolean shade = el.optBoolean("shade", true);
        for (int d = 0; d < 6; d++) {
            JSONObject face = faces.optJSONObject(DIR_NAMES[d]);
            if (face == null) continue;
            String tex = texture(textures, face.optString("texture", ""));
            int tile = tex == null ? -1 : atlas.tile(tex);
            if (tile < 0) tile = 0;
            Quad q = new Quad();
            float[][] c = corners(d, x0, y0, z0, x1, y1, z1); // in 1/16 units
            float[] uv = defaultUv(d, x0, y0, z0, x1, y1, z1);
            JSONArray fu = face.optJSONArray("uv");
            if (fu != null) uv = new float[]{(float) fu.getDouble(0), (float) fu.getDouble(1), (float) fu.getDouble(2), (float) fu.getDouble(3)};
            // uv corners in face order: bottom-left, bottom-right, top-right, top-left (seen from outside)
            float[][] uc = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
            int shift = (face.optInt("rotation", 0) / 90) & 3;
            int tu = tile % GameAtlas.GRID, tv = tile / GameAtlas.GRID;
            for (int k = 0; k < 4; k++) {
                float[] u = uc[(k + shift) & 3];
                q.uv[k][0] = (tu + clamp01(u[0] / 16f)) / GameAtlas.GRID;
                q.uv[k][1] = (tv + clamp01(u[1] / 16f)) / GameAtlas.GRID;
                q.pos[k] = c[k];
            }
            if (rot != null) rotateElement(q, rot);
            int dir = rot != null && rot.optDouble("angle", 0) != 0 ? -1 : d;
            int shadeDir = d;
            String cf = face.optString("cullface", null);
            int cull = cf == null ? -1 : dirIndex(cf);
            // variant rotation: x first, then y, around the block centre
            for (int k = 0; k < 4; k++) q.pos[k] = rotateVariant(q.pos[k], rx, ry);
            if (dir >= 0) dir = rotateDir(dir, rx, ry);
            shadeDir = rotateDir(shadeDir, rx, ry);
            if (cull >= 0) cull = rotateDir(cull, rx, ry);
            for (int k = 0; k < 4; k++) for (int a = 0; a < 3; a++) q.pos[k][a] /= 16f;
            q.dir = dir;
            q.shadeDir = shadeDir;
            q.cull = cull;
            q.tint = face.has("tintindex");
            q.shade = shade;
            q.ao = ao;
            q.alpha = atlas.alpha[tile];
            out.add(q);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int dirIndex(String s) {
        if (s.equals("bottom")) return 1;
        for (int i = 0; i < 6; i++) if (DIR_NAMES[i].equals(s)) return i;
        return -1;
    }

    /** Corners CCW seen from outside, starting bottom-left (same convention as GameMesher). */
    private static float[][] corners(int d, float x0, float y0, float z0, float x1, float y1, float z1) {
        switch (d) {
            case 0: return new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
            case 1: return new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case 2: return new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case 3: return new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case 4: return new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
            default: return new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
        }
    }

    /** Vanilla's default uv rectangle {u0, v0, u1, v1} for a face of an element (1/16 units). */
    private static float[] defaultUv(int d, float x0, float y0, float z0, float x1, float y1, float z1) {
        switch (d) {
            case 0: return new float[]{x0, z0, x1, z1};
            case 1: return new float[]{x0, 16 - z1, x1, 16 - z0};
            case 2: return new float[]{16 - x1, 16 - y1, 16 - x0, 16 - y0};
            case 3: return new float[]{x0, 16 - y1, x1, 16 - y0};
            case 4: return new float[]{16 - z1, 16 - y1, 16 - z0, 16 - y0};
            default: return new float[]{z0, 16 - y1, z1, 16 - y0};
        }
    }

    /** Element rotation: one axis, any angle (vanilla uses multiples of 22.5), optionally rescaled to keep its size. */
    private static void rotateElement(Quad q, JSONObject rot) throws org.json.JSONException {
        double angle = Math.toRadians(rot.optDouble("angle", 0));
        if (angle == 0) return;
        String axis = rot.optString("axis", "y");
        JSONArray o = rot.optJSONArray("origin");
        float ox = o != null ? (float) o.getDouble(0) : 8, oy = o != null ? (float) o.getDouble(1) : 8, oz = o != null ? (float) o.getDouble(2) : 8;
        boolean rescale = rot.optBoolean("rescale", false);
        double c = Math.cos(angle), s = Math.sin(angle), k = rescale ? 1 / Math.cos(angle) : 1;
        for (float[] p : q.pos) {
            double x = p[0] - ox, y = p[1] - oy, z = p[2] - oz, nx = x, ny = y, nz = z;
            switch (axis) {
                case "x":
                    ny = y * c - z * s;
                    nz = y * s + z * c;
                    ny *= k;
                    nz *= k;
                    break;
                case "z":
                    nx = x * c - y * s;
                    ny = x * s + y * c;
                    nx *= k;
                    ny *= k;
                    break;
                default: // y
                    nx = x * c + z * s;
                    nz = -x * s + z * c;
                    nx *= k;
                    nz *= k;
                    break;
            }
            p[0] = (float) (nx + ox);
            p[1] = (float) (ny + oy);
            p[2] = (float) (nz + oz);
        }
    }

    /** Variant rotation (multiples of 90): x first (north goes down), then y (north goes east), around (8, 8, 8). */
    private static float[] rotateVariant(float[] p, int rx, int ry) {
        float x = p[0], y = p[1], z = p[2];
        for (int i = 0; i < ((rx / 90) & 3); i++) {
            float ny = z, nz = 16 - y;
            y = ny;
            z = nz;
        }
        for (int i = 0; i < ((ry / 90) & 3); i++) {
            float nx = 16 - z, nz = x;
            x = nx;
            z = nz;
        }
        return new float[]{x, y, z};
    }

    static int rotateDir(int d, int rx, int ry) {
        int dx = DIRV[d][0], dy = DIRV[d][1], dz = DIRV[d][2];
        for (int i = 0; i < ((rx / 90) & 3); i++) {
            int ny = dz, nz = -dy;
            dy = ny;
            dz = nz;
        }
        for (int i = 0; i < ((ry / 90) & 3); i++) {
            int nx = -dz, nz = dx;
            dx = nx;
            dz = nz;
        }
        for (int i = 0; i < 6; i++) if (DIRV[i][0] == dx && DIRV[i][1] == dy && DIRV[i][2] == dz) return i;
        return d;
    }

    private JSONObject json(String path) {
        if (jsonCache.containsKey(path)) return jsonCache.get(path);
        JSONObject j = null;
        byte[] b = files.get(path);
        if (b != null) {
            try {
                j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            } catch (org.json.JSONException ignored) {
            }
        }
        jsonCache.put(path, j);
        return j;
    }

    private static String stripNs(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }
}
