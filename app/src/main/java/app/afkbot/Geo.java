package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads Mojang's entity geometry files (the official models published in Mojang's bedrock-samples
 * resource pack) and turns a model into triangles: x, y, z (blocks, origin at the feet), u, v (0..1), shade.
 * Supports the old ("geometry.name:parent") and new ("minecraft:geometry") formats, bone hierarchies with
 * rotation around pivots, cube rotation, inflate, mirror, box UV and per-face UV.
 */
final class Geo {
    private Geo() {
    }

    static final int STRIDE = 6;

    /** Finds a geometry by id in a geometry file; returns {texW, texH, bones JSONArray} merged with its parent, or null. */
    static Object[] find(JSONObject file, String id, Map<String, JSONObject> otherFiles) {
        try {
            // new format
            JSONArray list = file.optJSONArray("minecraft:geometry");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject g = list.getJSONObject(i);
                    JSONObject d = g.getJSONObject("description");
                    if (id.equals(d.optString("identifier"))) {
                        return new Object[]{d.optInt("texture_width", 64), d.optInt("texture_height", 64), g.optJSONArray("bones")};
                    }
                }
            }
            // old format: keys "geometry.name" or "geometry.name:geometry.parent"
            Iterator<String> it = file.keys();
            while (it.hasNext()) {
                String k = it.next();
                String name = k.contains(":") ? k.substring(0, k.indexOf(':')) : k;
                if (!name.equals(id)) continue;
                JSONObject g = file.getJSONObject(k);
                JSONArray bones = g.optJSONArray("bones");
                int tw = g.optInt("texturewidth", 64), th = g.optInt("textureheight", 64);
                if (k.contains(":")) {
                    String parent = k.substring(k.indexOf(':') + 1);
                    Object[] p = find(file, parent, otherFiles);
                    if (p == null && otherFiles != null) for (JSONObject o : otherFiles.values()) {
                        p = find(o, parent, null);
                        if (p != null) break;
                    }
                    if (p != null) {
                        bones = mergeBones((JSONArray) p[2], bones);
                        if (!g.has("texturewidth")) {
                            tw = (Integer) p[0];
                            th = (Integer) p[1];
                        }
                    }
                }
                return new Object[]{tw, th, bones};
            }
        } catch (org.json.JSONException ignored) {
        }
        return null;
    }

    /** Child bones replace parent bones with the same name. */
    private static JSONArray mergeBones(JSONArray parent, JSONArray child) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        Map<String, JSONObject> byName = new HashMap<String, JSONObject>();
        if (child != null) for (int i = 0; i < child.length(); i++) byName.put(child.getJSONObject(i).optString("name"), child.getJSONObject(i));
        if (parent != null) for (int i = 0; i < parent.length(); i++) {
            JSONObject b = parent.getJSONObject(i);
            JSONObject c = byName.remove(b.optString("name"));
            out.put(c != null ? c : b);
        }
        if (child != null) for (int i = 0; i < child.length(); i++) {
            JSONObject b = child.getJSONObject(i);
            if (byName.containsKey(b.optString("name"))) out.put(b);
        }
        return out;
    }

    /** Builds triangles for a model. */
    static float[] mesh(int texW, int texH, JSONArray bones) throws org.json.JSONException {
        List<Float> out = new ArrayList<Float>();
        Map<String, JSONObject> byName = new HashMap<String, JSONObject>();
        for (int i = 0; i < bones.length(); i++) byName.put(bones.getJSONObject(i).optString("name"), bones.getJSONObject(i));
        for (int i = 0; i < bones.length(); i++) {
            JSONObject bone = bones.getJSONObject(i);
            if (bone.optBoolean("neverRender")) continue;
            JSONArray cubes = bone.optJSONArray("cubes");
            if (cubes == null) continue;
            boolean boneMirror = bone.optBoolean("mirror");
            for (int c = 0; c < cubes.length(); c++) {
                JSONObject cube = cubes.getJSONObject(c);
                float[] o = vec(cube.optJSONArray("origin")), sz = vec(cube.optJSONArray("size"));
                float inf = (float) cube.optDouble("inflate", 0);
                boolean mirror = cube.has("mirror") ? cube.optBoolean("mirror") : boneMirror;
                float x0 = o[0] - inf, y0 = o[1] - inf, z0 = o[2] - inf, x1 = o[0] + sz[0] + inf, y1 = o[1] + sz[1] + inf, z1 = o[2] + sz[2] + inf;
                float[][] rects = rects(cube, sz, mirror);
                for (int f = 0; f < 6; f++) {
                    if (rects[f] == null) continue;
                    float[][] q = FACE[f];
                    for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
                        float fx = q[k][0], fy = q[k][1], fz = q[k][2];
                        float[] p = {x0 + fx * (x1 - x0), y0 + fy * (y1 - y0), z0 + fz * (z1 - z0)};
                        // cube rotation, then bone rotations up the parent chain
                        JSONArray crot = cube.optJSONArray("rotation");
                        if (crot != null) rotate(p, vec(cube.optJSONArray("pivot")), vec(crot));
                        JSONObject b = bone;
                        for (int guard = 0; b != null && guard < 32; guard++) {
                            JSONArray r = b.optJSONArray("rotation");
                            if (r != null) rotate(p, vec(b.optJSONArray("pivot")), vec(r));
                            String parent = b.optString("parent", null);
                            b = parent == null ? null : byName.get(parent);
                        }
                        float uu, vv;
                        switch (f) {
                            case 0: uu = fx; vv = fz; break;
                            case 1: uu = fx; vv = 1 - fz; break;
                            case 2: uu = 1 - fx; vv = 1 - fy; break;
                            case 3: uu = fx; vv = 1 - fy; break;
                            case 4: uu = 1 - fz; vv = 1 - fy; break;
                            default: uu = fz; vv = 1 - fy; break;
                        }
                        if (mirror && f >= 2) uu = 1 - uu;
                        float[] r = rects[f];
                        // model units are pixels; Bedrock's +x is the model's left, so flip x for our right-handed world
                        out.add(-p[0] / 16f);
                        out.add(p[1] / 16f);
                        out.add(p[2] / 16f);
                        out.add((r[0] + uu * r[2]) / texW);
                        out.add((r[1] + vv * r[3]) / texH);
                        out.add(SHADE[f]);
                    }
                }
            }
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static final float[][][] FACE = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
    };
    private static final float[] SHADE = {1.0f, 0.55f, 0.82f, 0.82f, 0.68f, 0.68f};

    /** Texture rectangles (u, v, w, h in texture pixels) for up, down, north, south, east, west. */
    private static float[][] rects(JSONObject cube, float[] sz, boolean mirror) throws org.json.JSONException {
        Object uv = cube.opt("uv");
        float[][] r = new float[6][];
        if (uv instanceof JSONObject) {
            String[] names = {"up", "down", "north", "south", "east", "west"};
            for (int f = 0; f < 6; f++) {
                JSONObject face = ((JSONObject) uv).optJSONObject(names[f]);
                if (face == null) continue;
                float[] a = vec(face.optJSONArray("uv")), s = vec(face.optJSONArray("uv_size"));
                r[f] = new float[]{a[0], a[1], s[0], s[1]};
            }
            return r;
        }
        float[] o = uv instanceof JSONArray ? vec((JSONArray) uv) : new float[3];
        float u = o[0], v = o[1];
        float w = (float) Math.floor(sz[0]), h = (float) Math.floor(sz[1]), d = (float) Math.floor(sz[2]);
        r[0] = new float[]{u + d, v, w, d};
        r[1] = new float[]{u + d + w, v + d, w, -d};
        r[2] = new float[]{u + d, v + d, w, h};
        r[3] = new float[]{u + d + w + d, v + d, w, h};
        r[4] = new float[]{u, v + d, d, h};
        r[5] = new float[]{u + d + w, v + d, d, h};
        if (mirror) {
            float[] t = r[4];
            r[4] = r[5];
            r[5] = t;
        }
        return r;
    }

    private static float[] vec(JSONArray a) {
        float[] v = new float[3];
        if (a == null) return v;
        for (int i = 0; i < Math.min(3, a.length()); i++) v[i] = (float) a.optDouble(i, 0);
        return v;
    }

    /** Rotates p around pivot by Bedrock euler angles (degrees), applied Z, then Y, then X. */
    private static void rotate(float[] p, float[] pivot, float[] rot) {
        double x = p[0] - pivot[0], y = p[1] - pivot[1], z = p[2] - pivot[2];
        double rz = Math.toRadians(rot[2]), ry = Math.toRadians(-rot[1]), rx = Math.toRadians(-rot[0]);
        double x1 = x * Math.cos(rz) - y * Math.sin(rz), y1 = x * Math.sin(rz) + y * Math.cos(rz);
        x = x1;
        y = y1;
        double x2 = x * Math.cos(ry) + z * Math.sin(ry), z2 = -x * Math.sin(ry) + z * Math.cos(ry);
        x = x2;
        z = z2;
        double y3 = y * Math.cos(rx) - z * Math.sin(rx), z3 = y * Math.sin(rx) + z * Math.cos(rx);
        p[0] = (float) (x + pivot[0]);
        p[1] = (float) (y3 + pivot[1]);
        p[2] = (float) (z3 + pivot[2]);
    }
}
