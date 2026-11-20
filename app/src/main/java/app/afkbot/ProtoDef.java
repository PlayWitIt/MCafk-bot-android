package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small interpreter for minecraft-data's ProtoDef packet definitions. Used for packets whose layout
 * changes a lot between versions (items with their many "components", container contents), so every
 * supported version is read from its own definition instead of hand-written code.
 */
final class ProtoDef {
    private final JSONObject types;

    ProtoDef(JSONObject types) {
        this.types = types;
    }

    /** Reads a value of the given type; maps become LinkedHashMap, arrays ArrayList. */
    Object read(Object type, Buf.In in) throws IOException {
        return read(type, in, new ArrayList<Map<String, Object>>());
    }

    private Object read(Object type, Buf.In in, List<Map<String, Object>> stack) throws IOException {
        try {
            if (type instanceof String) return readNamed((String) type, null, in, stack);
            if (type instanceof JSONArray) {
                JSONArray a = (JSONArray) type;
                return readNamed(a.getString(0), a.opt(1), in, stack);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad definition: " + e.getMessage());
        }
        throw new IOException("bad type " + type);
    }

    private Object readNamed(String name, Object args, Buf.In in, List<Map<String, Object>> stack) throws IOException, org.json.JSONException {
        switch (name) {
            case "void":
                return null;
            case "bool":
                return in.bool();
            case "u8":
                return in.u8();
            case "i8":
                return (int) (byte) in.u8();
            case "u16":
                return in.u16();
            case "i16":
                return (int) (short) in.u16();
            case "i32":
                return in.i32();
            case "u32":
                return in.i32() & 0xFFFFFFFFL;
            case "i64":
                return in.i64();
            case "f32":
                return (double) in.f32();
            case "f64":
                return in.f64();
            case "varint":
                return in.varInt();
            case "varlong": {
                long v = 0;
                int shift = 0, b;
                do {
                    b = in.u8();
                    v |= (long) (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && shift < 70);
                return v;
            }
            case "UUID":
                return in.uuid().toString();
            case "restBuffer":
                return in.bytes(in.remaining());
            case "pstring": {
                JSONObject o = (JSONObject) args;
                int n = o.has("count") ? o.getInt("count") : ((Number) read(o.get("countType"), in, stack)).intValue();
                return new String(in.bytes(n), StandardCharsets.UTF_8);
            }
            case "buffer": {
                JSONObject o = (JSONObject) args;
                int n;
                if (o.has("count")) {
                    Object c = o.get("count");
                    n = c instanceof Number ? ((Number) c).intValue() : toInt(lookup(String.valueOf(c), stack));
                } else if (o.optBoolean("rest")) {
                    n = in.remaining();
                } else {
                    n = ((Number) read(o.get("countType"), in, stack)).intValue();
                }
                return in.bytes(n);
            }
            case "option":
                return in.bool() ? read(args, in, stack) : null;
            case "anonymousNbt":
                return Nbt.read(in, false);
            case "anonOptionalNbt":
                return Nbt.read(in, true);
            case "container":
                return readContainer((JSONArray) args, in, stack, null);
            case "array": {
                JSONObject o = (JSONObject) args;
                int n;
                if (o.has("count")) {
                    Object c = o.get("count");
                    n = c instanceof Number ? ((Number) c).intValue() : toInt(lookup(String.valueOf(c), stack));
                } else {
                    n = ((Number) read(o.get("countType"), in, stack)).intValue();
                }
                if (n < 0 || n > 100000) throw new IOException("bad array length " + n);
                List<Object> l = new ArrayList<Object>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) l.add(read(o.get("type"), in, stack));
                return l;
            }
            case "mapper": {
                JSONObject o = (JSONObject) args;
                Object v = read(o.get("type"), in, stack);
                JSONObject m = o.getJSONObject("mappings");
                String key = String.valueOf(v);
                return m.has(key) ? m.getString(key) : v;
            }
            case "switch": {
                JSONObject o = (JSONObject) args;
                Object v = lookup(o.getString("compareTo"), stack);
                String key = v == null ? "null" : v instanceof Boolean ? v.toString() : v instanceof Number ? String.valueOf(((Number) v).longValue()) : v.toString();
                JSONObject fields = o.getJSONObject("fields");
                Object t = fields.has(key) ? fields.get(key) : o.has("default") ? o.get("default") : "void";
                return read(t, in, stack);
            }
            case "bitfield": {
                JSONArray parts = (JSONArray) args;
                int total = 0;
                for (int i = 0; i < parts.length(); i++) total += parts.getJSONObject(i).getInt("size");
                byte[] b = in.bytes(total / 8);
                java.math.BigInteger all = new java.math.BigInteger(1, b);
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                int shift = total;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    int size = p.getInt("size");
                    shift -= size;
                    long v = all.shiftRight(shift).longValue() & ((1L << size) - 1);
                    if (p.optBoolean("signed") && (v & (1L << (size - 1))) != 0) v -= 1L << size;
                    out.put(p.getString("name"), v);
                }
                return out;
            }
            case "bitflags": {
                JSONObject o = (JSONObject) args;
                long v = ((Number) read(o.get("type"), in, stack)).longValue();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                JSONArray flags = o.getJSONArray("flags");
                for (int i = 0; i < flags.length(); i++) out.put(flags.getString(i), (v & (1L << i)) != 0);
                out.put("_value", v);
                return out;
            }
            case "registryEntryHolder": {
                // varint id: 0 = inline value follows, otherwise registry id + 1
                JSONObject o = (JSONObject) args;
                int id = in.varInt();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                if (id == 0) {
                    JSONObject other = o.getJSONObject("otherwise");
                    out.put(other.getString("name"), read(other.get("type"), in, stack));
                } else {
                    out.put(o.optString("baseName", "id"), id - 1);
                }
                return out;
            }
            case "registryEntryHolderSet": {
                // varint n: 0 = a tag name follows, otherwise n-1 registry ids
                JSONObject o = (JSONObject) args;
                int n = in.varInt();
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                if (n == 0) {
                    JSONObject base = o.getJSONObject("base");
                    out.put(base.getString("name"), read(base.get("type"), in, stack));
                } else {
                    JSONObject other = o.getJSONObject("otherwise");
                    List<Object> l = new ArrayList<Object>();
                    for (int i = 0; i < n - 1; i++) l.add(read(other.get("type"), in, stack));
                    out.put(other.getString("name"), l);
                }
                return out;
            }
            default:
                if (types.has(name)) {
                    Object def = types.get(name);
                    if ("native".equals(def)) throw new IOException("unsupported native type " + name);
                    return read(def, in, stack);
                }
                throw new IOException("unknown type " + name);
        }
    }

    private Map<String, Object> readContainer(JSONArray fields, Buf.In in, List<Map<String, Object>> stack, Map<String, Object> into)
            throws IOException, org.json.JSONException {
        Map<String, Object> out = into != null ? into : new LinkedHashMap<String, Object>();
        if (into == null) stack.add(out);
        try {
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.getJSONObject(i);
                Object t = f.get("type");
                if (f.optBoolean("anon")) {
                    Object v = read(t, in, stack);
                    if (v instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
                        out.putAll(m);
                    }
                } else {
                    out.put(f.getString("name"), read(t, in, stack));
                }
            }
        } finally {
            if (into == null) stack.remove(stack.size() - 1);
        }
        return out;
    }

    /** Resolves "field", "../field", "../a/b" relative to the container being read. */
    @SuppressWarnings("unchecked")
    private static Object lookup(String path, List<Map<String, Object>> stack) {
        int level = stack.size() - 1;
        String[] parts = path.split("/");
        int i = 0;
        while (i < parts.length && parts[i].equals("..")) {
            level--;
            i++;
        }
        if (level < 0) return null;
        Object cur = stack.get(level);
        for (; i < parts.length; i++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(parts[i]);
        }
        return cur;
    }

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ------------------------------------------------------------------ NBT

    /** Network NBT (no root name) as Java objects. */
    static final class Nbt {
        static Object read(Buf.In in, boolean optional) throws IOException {
            int type = in.u8();
            if (type == 0) return null;
            return tag(in, type);
        }

        static Object tag(Buf.In in, int type) throws IOException {
            switch (type) {
                case 1:
                    return (int) (byte) in.u8();
                case 2:
                    return (int) (short) in.u16();
                case 3:
                    return in.i32();
                case 4:
                    return in.i64();
                case 5:
                    return (double) in.f32();
                case 6:
                    return in.f64();
                case 7:
                    return in.bytes(in.i32());
                case 8:
                    return new String(in.bytes(in.u16()), StandardCharsets.UTF_8);
                case 9: {
                    int et = in.u8();
                    int n = in.i32();
                    List<Object> l = new ArrayList<Object>();
                    for (int i = 0; i < n; i++) l.add(tag(in, et));
                    return l;
                }
                case 10: {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    while (true) {
                        int t = in.u8();
                        if (t == 0) break;
                        String k = new String(in.bytes(in.u16()), StandardCharsets.UTF_8);
                        m.put(k, tag(in, t));
                    }
                    return m;
                }
                case 11: {
                    int n = in.i32();
                    in.bytes(n * 4);
                    return "int[" + n + "]";
                }
                case 12: {
                    int n = in.i32();
                    in.bytes(n * 8);
                    return "long[" + n + "]";
                }
                default:
                    throw new IOException("bad nbt tag " + type);
            }
        }
    }

    // ------------------------------------------------------------------ per-version data

    /** Item names + definitions for one protocol version (from assets/proto/<protocol>.json.gz). */
    static final class VersionData {
        final ProtoDef def;
        final JSONObject packets;
        final String[] itemNames;
        final int[] blockMin;
        final String[] blockNames;
        final int[] shapes; // collision/outline shape type per block (see Shapes)
        final String[][] propNames; // per block: property names
        final String[][][] propValues; // per block: values of each property
        final String[] blockIds; // internal names, e.g. "oak_log"
        final double[] hardness; // -1 = unbreakable
        final String[] material; // e.g. "mineable/pickaxe"
        final int[][] harvest; // item ids that can harvest it (empty = anything)
        /** Exact vanilla collision: per block, shape id per state offset (one entry = same for all states); null = unknown. */
        final int[][] collision;
        final float[][][] collisionShapes; // shape id -> boxes {x0,y0,z0,x1,y1,z1}

        VersionData(JSONObject root) throws org.json.JSONException {
            def = new ProtoDef(root.getJSONObject("types"));
            packets = root.getJSONObject("packets");
            JSONArray n = root.getJSONArray("items");
            itemNames = new String[n.length()];
            for (int i = 0; i < n.length(); i++) itemNames[i] = n.getString(i);
            JSONArray bm = root.optJSONArray("blockMin"), bn = root.optJSONArray("blockNames");
            int k = bm == null || bn == null ? 0 : Math.min(bm.length(), bn.length());
            blockMin = new int[k];
            blockNames = new String[k];
            for (int i = 0; i < k; i++) {
                blockMin[i] = bm.getInt(i);
                blockNames[i] = bn.getString(i);
            }
            JSONArray ids = root.optJSONArray("blockIds"), hd = root.optJSONArray("hardness"), mt = root.optJSONArray("material"), hv = root.optJSONArray("harvest");
            blockIds = new String[k];
            hardness = new double[k];
            material = new String[k];
            harvest = new int[k][];
            for (int i = 0; i < k; i++) {
                blockIds[i] = ids != null && i < ids.length() ? ids.getString(i) : "";
                hardness[i] = hd != null && i < hd.length() ? hd.getDouble(i) : 1;
                material[i] = mt != null && i < mt.length() ? mt.getString(i) : "default";
                JSONArray h = hv != null && i < hv.length() ? hv.getJSONArray(i) : new JSONArray();
                harvest[i] = new int[h.length()];
                for (int j = 0; j < h.length(); j++) harvest[i][j] = h.getInt(j);
            }
            JSONArray co = root.optJSONArray("collision");
            JSONObject cs = root.optJSONObject("collisionShapes");
            if (co != null && cs != null) {
                int max = 0;
                java.util.Iterator<String> it = cs.keys();
                while (it.hasNext()) max = Math.max(max, Integer.parseInt(it.next()));
                collisionShapes = new float[max + 1][][];
                it = cs.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    JSONArray boxes = cs.getJSONArray(key);
                    float[][] f = new float[boxes.length()][6];
                    for (int b = 0; b < boxes.length(); b++) {
                        JSONArray bx = boxes.getJSONArray(b);
                        for (int q = 0; q < 6; q++) f[b][q] = (float) bx.getDouble(q);
                    }
                    collisionShapes[Integer.parseInt(key)] = f;
                }
                collision = new int[k][];
                for (int i = 0; i < k && i < co.length(); i++) {
                    Object v = co.get(i);
                    if (v instanceof JSONArray) {
                        JSONArray a = (JSONArray) v;
                        collision[i] = new int[a.length()];
                        for (int q = 0; q < a.length(); q++) collision[i][q] = a.getInt(q);
                    } else {
                        int id = ((Number) v).intValue();
                        collision[i] = id < 0 ? null : new int[]{id};
                    }
                }
            } else {
                collision = null;
                collisionShapes = null;
            }
            JSONArray sh = root.optJSONArray("shapes"), pr = root.optJSONArray("props");
            shapes = new int[k];
            propNames = new String[k][];
            propValues = new String[k][][];
            for (int i = 0; i < k; i++) {
                shapes[i] = sh != null && i < sh.length() ? sh.getInt(i) : 1;
                JSONArray ps = pr != null && i < pr.length() ? pr.getJSONArray(i) : new JSONArray();
                propNames[i] = new String[ps.length()];
                propValues[i] = new String[ps.length()][];
                for (int j = 0; j < ps.length(); j++) {
                    JSONArray one = ps.getJSONArray(j);
                    propNames[i][j] = one.getString(0);
                    JSONArray vals = one.getJSONArray(1);
                    propValues[i][j] = new String[vals.length()];
                    for (int v = 0; v < vals.length(); v++) propValues[i][j][v] = vals.getString(v);
                }
            }
        }

        int blockIndex(int state) {
            int i = java.util.Arrays.binarySearch(blockMin, state);
            if (i < 0) i = -i - 2;
            return i;
        }

        /** Display name of a block state, e.g. "Chest". */
        String blockName(int state) {
            int i = java.util.Arrays.binarySearch(blockMin, state);
            if (i < 0) i = -i - 2;
            return i >= 0 && i < blockNames.length ? blockNames[i] : "Block";
        }

        Map<String, Object> readPacket(String name, Buf.In in) throws IOException {
            try {
                if (!packets.has(name)) throw new IOException("no definition for " + name);
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) def.read(packets.get(name), in);
                return m;
            } catch (org.json.JSONException e) {
                throw new IOException(e.getMessage());
            }
        }

        String itemName(int id) {
            return id >= 0 && id < itemNames.length && !itemNames[id].isEmpty() ? itemNames[id] : "Item #" + id;
        }
    }

    /** Loads plain or gzipped JSON. */
    static VersionData load(java.io.InputStream raw) throws IOException {
        java.io.InputStream in = new java.io.BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) in = new java.util.zip.GZIPInputStream(in);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        try {
            return new VersionData(new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }
}
