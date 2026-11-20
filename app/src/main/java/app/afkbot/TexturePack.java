package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Turns Minecraft's blockstate/model/texture files into a texture atlas plus a table saying which
 * atlas tile goes on each face of each block. Platform neutral: PNG decoding is supplied by the caller.
 */
final class TexturePack {
    private TexturePack() {
    }

    interface Images {
        /** Decodes a PNG and returns the first 16x16 frame as 256 ARGB pixels, or null. */
        int[] decode16(byte[] png);
    }

    static final int TILE = 16, GRID = 64, SIZE = TILE * GRID; // 1024x1024 atlas, 4096 tiles
    static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, EAST = 4, WEST = 5; // same order as the renderer

    static final class Result {
        int[] atlas; // SIZE*SIZE ARGB
        int[][] faces; // per block: 6 tile ids (-1 = no texture)
        int[] tint; // per block: RGB tint for tinted faces (0 = none)
        int[] tintMask; // per block: bit per face that is tinted
        int[] cross; // per block: tile id for plants drawn as an X (-1 = not a plant)
        int tiles;
    }

    static boolean wanted(String name) {
        return (name.startsWith("assets/minecraft/blockstates/") || name.startsWith("assets/minecraft/models/block/")
                || name.startsWith("assets/minecraft/textures/block/")) && !name.endsWith(".mcmeta");
    }

    static Result build(Map<String, byte[]> files, String[] blockIds, Images img) {
        Result r = new Result();
        int n = blockIds.length;
        r.atlas = new int[SIZE * SIZE];
        r.faces = new int[n][6];
        r.tint = new int[n];
        r.tintMask = new int[n];
        r.cross = new int[n];
        Map<String, Integer> tileOf = new HashMap<String, Integer>();
        Map<String, JSONObject> modelCache = new HashMap<String, JSONObject>();
        // tile 0 = plain white (for untextured faces)
        for (int i = 0; i < TILE; i++) for (int j = 0; j < TILE; j++) r.atlas[i * SIZE + j] = 0xFFFFFFFF;
        int[] next = {1};

        for (int b = 0; b < n; b++) {
            java.util.Arrays.fill(r.faces[b], -1);
            r.cross[b] = -1;
            String id = blockIds[b];
            r.tint[b] = tintFor(id);
            try {
                if (id.equals("water") || id.equals("lava")) {
                    int t = tile(files, img, r, tileOf, next, "block/" + id + "_still");
                    java.util.Arrays.fill(r.faces[b], t);
                    if (id.equals("water")) r.tintMask[b] = 63;
                    continue;
                }
                String model = modelFor(files, id);
                if (model == null) continue;
                // resolve the parent chain: merge textures (child wins), find the nearest elements
                Map<String, String> textures = new HashMap<String, String>();
                JSONArray elements = null;
                boolean cross = false;
                String m = model;
                for (int depth = 0; m != null && depth < 16; depth++) {
                    String key = stripNs(m);
                    if (key.contains("cross")) cross = true;
                    JSONObject j = model(files, modelCache, key);
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
                    m = j.optString("parent", null);
                }
                if (cross) {
                    String t = resolve(textures, "#cross");
                    if (t == null) t = resolve(textures, "#plant");
                    if (t != null) {
                        r.cross[b] = tile(files, img, r, tileOf, next, t);
                        if (r.tint[b] != 0) r.tintMask[b] = 63;
                    }
                    continue;
                }
                if (elements == null) {
                    // no geometry (e.g. some special blocks): use the particle texture everywhere
                    String t = resolve(textures, "#particle");
                    if (t != null) java.util.Arrays.fill(r.faces[b], tile(files, img, r, tileOf, next, t));
                    continue;
                }
                String[] dirs = {"up", "down", "north", "south", "east", "west"};
                for (int e = 0; e < elements.length(); e++) {
                    JSONObject faces = elements.getJSONObject(e).optJSONObject("faces");
                    if (faces == null) continue;
                    for (int f = 0; f < 6; f++) {
                        if (r.faces[b][f] >= 0) continue;
                        JSONObject face = faces.optJSONObject(dirs[f]);
                        if (face == null) continue;
                        String t = resolve(textures, face.optString("texture", ""));
                        if (t == null) continue;
                        r.faces[b][f] = tile(files, img, r, tileOf, next, t);
                        if (face.has("tintindex") && r.tint[b] != 0) r.tintMask[b] |= 1 << f;
                    }
                }
                // fill missing faces (e.g. thin models) from what we found
                int any = -1;
                for (int f = 0; f < 6; f++) if (r.faces[b][f] >= 0) any = r.faces[b][f];
                for (int f = 0; f < 6; f++) if (r.faces[b][f] < 0) r.faces[b][f] = any;
            } catch (Exception ignored) {
                // leave this block untextured (flat colour)
            }
        }
        r.tiles = next[0];
        return r;
    }

    private static String stripNs(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    /** Picks a representative model for a block from its blockstate file. */
    private static String modelFor(Map<String, byte[]> files, String id) throws org.json.JSONException {
        byte[] bs = files.get("assets/minecraft/blockstates/" + id + ".json");
        if (bs == null) return null;
        JSONObject j = new JSONObject(new String(bs, StandardCharsets.UTF_8));
        JSONObject variants = j.optJSONObject("variants");
        if (variants != null) {
            String pick = null;
            Iterator<String> it = variants.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (pick == null || k.isEmpty() || k.contains("axis=y") || (k.contains("half=lower") && !pick.contains("half=lower")))
                    pick = k;
                if (k.isEmpty()) break;
            }
            Object v = variants.get(pick);
            JSONObject o = v instanceof JSONArray ? ((JSONArray) v).getJSONObject(0) : (JSONObject) v;
            return o.getString("model");
        }
        JSONArray parts = j.optJSONArray("multipart");
        if (parts != null && parts.length() > 0) {
            Object a = parts.getJSONObject(0).get("apply");
            JSONObject o = a instanceof JSONArray ? ((JSONArray) a).getJSONObject(0) : (JSONObject) a;
            return o.getString("model");
        }
        return null;
    }

    private static JSONObject model(Map<String, byte[]> files, Map<String, JSONObject> cache, String key) {
        if (cache.containsKey(key)) return cache.get(key);
        JSONObject j = null;
        byte[] b = files.get("assets/minecraft/models/" + key + ".json");
        if (b != null) {
            try {
                j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            } catch (org.json.JSONException ignored) {
            }
        }
        cache.put(key, j);
        return j;
    }

    /** Follows "#var" references to a texture path like "block/stone". */
    private static String resolve(Map<String, String> textures, String ref) {
        for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) ref = textures.get(ref.substring(1));
        if (ref == null || ref.startsWith("#") || ref.isEmpty()) return null;
        return stripNs(ref);
    }

    private static int tile(Map<String, byte[]> files, Images img, Result r, Map<String, Integer> tileOf, int[] next, String tex) {
        Integer have = tileOf.get(tex);
        if (have != null) return have;
        byte[] png = files.get("assets/minecraft/textures/" + tex + ".png");
        int[] px = png == null ? null : img.decode16(png);
        if (px == null || next[0] >= GRID * GRID) {
            tileOf.put(tex, -1);
            return -1;
        }
        int id = next[0]++;
        int tx = (id % GRID) * TILE, ty = (id / GRID) * TILE;
        for (int y = 0; y < TILE; y++) System.arraycopy(px, y * TILE, r.atlas, (ty + y) * SIZE + tx, TILE);
        tileOf.put(tex, id);
        return id;
    }

    /** Fixed "plains" biome tints for grass, foliage and water. */
    static int tintFor(String id) {
        if (id.equals("water") || id.equals("bubble_column")) return 0x3F76E4;
        if (id.equals("birch_leaves")) return 0x80A755;
        if (id.equals("spruce_leaves")) return 0x619961;
        if (id.equals("lily_pad")) return 0x208030;
        if (id.equals("grass_block") || id.equals("short_grass") || id.equals("tall_grass") || id.equals("fern") || id.equals("large_fern")
                || id.equals("sugar_cane") || id.equals("potted_fern")) return 0x91BD59;
        if (id.endsWith("_leaves") || id.equals("vine") || id.equals("mangrove_leaves")) return 0x77AB2F;
        return 0;
    }

    /** Serialises the face table (the atlas image is saved separately as a PNG). */
    static String mapJson(Result r) {
        StringBuilder sb = new StringBuilder("{\"faces\":[");
        for (int b = 0; b < r.faces.length; b++) {
            if (b > 0) sb.append(',');
            sb.append('[');
            for (int f = 0; f < 6; f++) sb.append(f > 0 ? "," : "").append(r.faces[b][f]);
            sb.append(']');
        }
        sb.append("],\"tint\":").append(arr(r.tint)).append(",\"tintMask\":").append(arr(r.tintMask)).append(",\"cross\":").append(arr(r.cross)).append('}');
        return sb.toString();
    }

    private static String arr(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(a[i]);
        return sb.append(']').toString();
    }

    /** Loaded atlas mapping for the renderer. */
    static final class Map3D {
        final int[][] faces;
        final int[] tint, tintMask, cross;

        Map3D(String json) throws org.json.JSONException {
            JSONObject j = new JSONObject(json);
            JSONArray f = j.getJSONArray("faces");
            faces = new int[f.length()][6];
            for (int i = 0; i < f.length(); i++) {
                JSONArray a = f.getJSONArray(i);
                for (int k = 0; k < 6; k++) faces[i][k] = a.getInt(k);
            }
            tint = ints(j.getJSONArray("tint"));
            tintMask = ints(j.getJSONArray("tintMask"));
            cross = ints(j.getJSONArray("cross"));
        }

        private static int[] ints(JSONArray a) throws org.json.JSONException {
            int[] o = new int[a.length()];
            for (int i = 0; i < o.length; i++) o[i] = a.getInt(i);
            return o;
        }
    }

    @SuppressWarnings("unused")
    private static void keep(List<?> l) {
    }
}
