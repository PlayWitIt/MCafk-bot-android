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
 * How an item looks in the hotbar and inventory, from the game's item definitions (items/*.json, 1.21.4+) and item
 * models: flat layered textures with their tints (tools, food, potions, dyed leather...), a small 3D cube with the
 * block's top/front/side textures (block items), and stand-ins for the items the game draws with entity models
 * (chests, heads, banners, beds, shields). Rectangles are in atlas pixels {x, y, w, h}. Platform neutral.
 */
final class GameIcons {
    static final class Icon {
        final List<int[]> layers = new ArrayList<int[]>(); // flat: {x, y, w, h, tintRGB or 0}
        int[] top, front, side; // cube faces, or null for a flat icon
        int tint; // cube tint (grass, leaves), 0 = none
        boolean tintSides;
        /** The model's first-person (right hand) display transform: rotation degrees XYZ, translation (1/16), scale. */
        float[] fpRot = {0, -90, 25}, fpTrans = {1.13f, 3.2f, 1.13f}, fpScale = {0.68f, 0.68f, 0.68f};

        boolean flat() {
            return top == null;
        }

        boolean empty() {
            return top == null && layers.isEmpty();
        }
    }

    private static final Icon NONE = new Icon();
    private final Map<String, byte[]> files;
    private final GameAtlas atlas;
    private final Map<String, Icon> cache = new HashMap<String, Icon>();

    GameIcons(Map<String, byte[]> files, GameAtlas atlas) {
        this.files = files;
        this.atlas = atlas;
    }

    synchronized Icon get(String itemId) {
        if (itemId == null || itemId.isEmpty()) return NONE;
        Icon i = cache.get(itemId);
        if (i != null) return i;
        try {
            i = resolve(itemId);
        } catch (Exception e) {
            i = null;
        }
        if (i == null || i.empty()) i = fallback(itemId);
        if (i == null) i = NONE;
        cache.put(itemId, i);
        return i;
    }

    // ---------------------------------------------------------------- item definitions

    private Icon resolve(String id) throws Exception {
        JSONObject def = json("assets/minecraft/items/" + id + ".json");
        JSONObject node = def == null ? null : findModelNode(def.opt("model"));
        if (node != null && "minecraft:special".equals(node.optString("type"))) return special(id, node);
        String model = node != null ? node.optString("model", null) : null;
        if (model == null) model = "item/" + id; // before 1.21.4: item models are named after the item
        JSONArray tints = node != null ? node.optJSONArray("tints") : null;
        return fromModel(id, strip(model), tints);
    }

    /** The first concrete model in an item definition, following conditions, selects and range dispatches. */
    private static JSONObject findModelNode(Object o) throws org.json.JSONException {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String type = j.optString("type", "");
            if (type.equals("minecraft:model") || type.equals("minecraft:special")) return j;
            for (String k : new String[]{"fallback", "on_false", "model", "on_true", "cases", "entries"}) {
                Object v = j.opt(k);
                if (v == null) continue;
                JSONObject r = findModelNode(v);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findModelNode(a.get(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private Icon fromModel(String id, String model, JSONArray tints) throws Exception {
        Map<String, String> tex = new HashMap<String, String>();
        boolean flat = false, block = false;
        JSONObject fp = null;
        String m = model;
        for (int depth = 0; m != null && depth < 16; depth++) {
            if (m.equals("item/generated") || m.equals("item/handheld") || m.startsWith("builtin/generated") || m.equals("item/handheld_rod")
                    || m.equals("item/handheld_mace")) flat = true;
            if (m.startsWith("block/")) block = true;
            JSONObject j = json("assets/minecraft/models/" + m + ".json");
            if (j == null) break;
            JSONObject t = j.optJSONObject("textures");
            if (t != null) {
                Iterator<String> it = t.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!tex.containsKey(k)) tex.put(k, t.getString(k));
                }
            }
            JSONObject disp = j.optJSONObject("display");
            if (fp == null && disp != null) fp = disp.optJSONObject("firstperson_righthand");
            String parent = j.optString("parent", null);
            m = parent == null ? null : strip(parent);
        }
        Icon ic = new Icon();
        if (fp != null) {
            ic.fpRot = vec(fp.optJSONArray("rotation"), 0f);
            ic.fpTrans = vec(fp.optJSONArray("translation"), 0f);
            ic.fpScale = vec(fp.optJSONArray("scale"), 1f);
        } else if (block) {
            ic.fpRot = new float[]{0, 45, 0};
            ic.fpTrans = new float[]{0, 0, 0};
            ic.fpScale = new float[]{0.4f, 0.4f, 0.4f};
        }
        if (flat || (!block && tex.containsKey("layer0"))) {
            for (int l = 0; l < 5; l++) {
                int t = tile(tex, "layer" + l);
                if (t < 0) continue;
                ic.layers.add(tileRect(t, tintOf(tints, l)));
            }
            return ic;
        }
        int top = first(tex, "top", "end", "up", "all", "texture", "particle");
        int side = first(tex, "side", "all", "north", "texture", "particle");
        int front = first(tex, "front", "north", "side", "all", "texture", "particle");
        if (top < 0 && side < 0) return null;
        if (top < 0) top = side;
        if (side < 0) side = top;
        if (front < 0) front = side;
        ic.top = tileRect(top, 0);
        ic.side = tileRect(side, 0);
        ic.front = tileRect(front, 0);
        ic.tint = TexturePack.tintFor(id);
        ic.tintSides = ic.tint != 0 && !id.equals("grass_block");
        return ic;
    }

    /** Default tint of layer l from the definition's "tints" (dye/potion/firework... "default", constant "value"). */
    private static int tintOf(JSONArray tints, int l) {
        if (tints == null || l >= tints.length()) return 0;
        JSONObject t = tints.optJSONObject(l);
        if (t == null) return 0;
        if (t.has("value")) return t.optInt("value") & 0xFFFFFF;
        if (t.has("default")) return t.optInt("default") & 0xFFFFFF;
        String type = t.optString("type", "");
        if (type.endsWith("grass")) return 0x91BD59;
        return 0;
    }

    // ---------------------------------------------------------------- items drawn with entity models

    private Icon special(String id, JSONObject node) throws Exception {
        JSONObject inner = node.optJSONObject("model");
        String type = inner == null ? "" : inner.optString("type", "");
        Icon ic = new Icon();
        if (type.endsWith("chest")) {
            String tex = strip(inner.optString("texture", "normal"));
            int t = atlas.tile("entity/chest/" + (tex.equals("christmas") ? "normal" : tex));
            if (t < 0) t = atlas.tile("entity/chest/normal");
            if (t >= 0) { // the chest texture's lid top, front and side (Minecraft box-UV layout)
                int x = (t % GameAtlas.GRID) * GameAtlas.TILE, y = (t / GameAtlas.GRID) * GameAtlas.TILE;
                ic.top = new int[]{x + 14, y, 14, 14};
                ic.front = new int[]{x + 42, y + 33, 14, 10};
                ic.side = new int[]{x + 28, y + 33, 14, 10};
                return ic;
            }
        }
        if (type.endsWith("head") || id.endsWith("_head") || id.endsWith("_skull")) {
            String skin = id.startsWith("zombie") ? "entity/zombie/zombie" : id.startsWith("skeleton") ? "entity/skeleton/skeleton"
                    : id.startsWith("wither_skeleton") ? "entity/skeleton/wither_skeleton" : id.startsWith("creeper") ? "entity/creeper/creeper"
                    : id.startsWith("piglin") ? "entity/piglin/piglin" : "entity/player/wide/steve";
            int t = atlas.tile(skin);
            if (t >= 0) { // the head's face, top and side from the skin
                int x = (t % GameAtlas.GRID) * GameAtlas.TILE, y = (t / GameAtlas.GRID) * GameAtlas.TILE;
                ic.top = new int[]{x + 8, y, 8, 8};
                ic.front = new int[]{x + 8, y + 8, 8, 8};
                ic.side = new int[]{x, y + 8, 8, 8};
                return ic;
            }
        }
        if (type.endsWith("shield")) {
            int t = atlas.tile("entity/shield_base_nopattern");
            if (t >= 0) {
                int x = (t % GameAtlas.GRID) * GameAtlas.TILE, y = (t / GameAtlas.GRID) * GameAtlas.TILE;
                ic.layers.add(new int[]{x + 1, y + 1, 12, 22, 0});
                return ic;
            }
        }
        String color = colorOf(id);
        if (color != null && (type.endsWith("banner") || type.endsWith("bed"))) {
            int wool = atlas.tile("block/" + color + "_wool");
            if (wool >= 0) {
                if (type.endsWith("bed")) { // a low cube of the bed's colour
                    ic.top = tileRect(wool, 0);
                    ic.front = ic.side = tileRect(wool, 0);
                } else {
                    ic.layers.add(tileRect(wool, 0));
                }
                return ic;
            }
        }
        // anything else: the base model's textures (e.g. decorated pot, conduit)
        String base = node.optString("base", null);
        return base == null ? null : fromModel(id, strip(base), null);
    }

    private static final String[] COLORS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black"};

    private static String colorOf(String id) {
        for (String c : COLORS) if (id.startsWith(c + "_")) return c;
        return null;
    }

    /** Last resorts: the item's own texture, or the block texture of the same name. */
    private Icon fallback(String id) {
        Icon ic = new Icon();
        int t = atlas.tile("item/" + id);
        if (t >= 0) {
            ic.layers.add(tileRect(t, 0));
            return ic;
        }
        t = atlas.tile("block/" + id);
        if (t < 0) t = atlas.tile("block/" + id + "_top");
        if (t >= 0) {
            ic.top = ic.front = ic.side = tileRect(t, 0);
            return ic;
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static float[] vec(JSONArray a, float def) {
        float[] v = {def, def, def};
        if (a != null) for (int i = 0; i < 3 && i < a.length(); i++) v[i] = (float) a.optDouble(i, def);
        return v;
    }

    private static int[] tileRect(int tile, int tint) {
        return new int[]{(tile % GameAtlas.GRID) * GameAtlas.TILE, (tile / GameAtlas.GRID) * GameAtlas.TILE, GameAtlas.TILE, GameAtlas.TILE, tint};
    }

    private int first(Map<String, String> tex, String... keys) {
        for (String k : keys) {
            int t = tile(tex, k);
            if (t >= 0) return t;
        }
        return -1;
    }

    private int tile(Map<String, String> tex, String key) {
        String ref = tex.get(key);
        for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) ref = tex.get(ref.substring(1));
        if (ref == null) return -1;
        return atlas.tile(strip(ref));
    }

    private JSONObject json(String path) {
        byte[] b = files.get(path);
        if (b == null) return null;
        try {
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static String strip(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }
}
