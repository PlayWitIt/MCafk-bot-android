package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * How an item looks in the hotbar and inventory, from the game's item definitions (items/*.json, 1.21.4+) and item
 * models: a flat texture (tools, food...) or a small 3D cube with the block's top and side textures (block items).
 * Platform neutral; the HUD draws the result.
 */
final class GameIcons {
    static final class Icon {
        int flat = -1; // atlas tile of a flat item
        int top = -1, side = -1, front = -1; // atlas tiles of a block item's cube
        int tint; // RGB for tinted blocks (grass, leaves), 0 = none
        boolean tintSides;
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
        if (i == null) i = NONE;
        cache.put(itemId, i);
        return i;
    }

    private Icon resolve(String id) throws Exception {
        String model = null;
        JSONObject def = json("assets/minecraft/items/" + id + ".json");
        if (def != null) model = findModel(def);
        if (model == null) model = "item/" + id; // before 1.21.4: item models are named after the item
        // walk the model's parents, collecting textures
        Map<String, String> tex = new HashMap<String, String>();
        boolean flat = false, block = false;
        String m = strip(model);
        for (int depth = 0; m != null && depth < 16; depth++) {
            if (m.equals("item/generated") || m.equals("item/handheld") || m.startsWith("builtin/generated") || m.equals("item/handheld_rod")) flat = true;
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
            String parent = j.optString("parent", null);
            m = parent == null ? null : strip(parent);
        }
        Icon ic = new Icon();
        if (flat || (!block && tex.containsKey("layer0"))) {
            ic.flat = tile(tex, "layer0");
            if (ic.flat < 0) ic.flat = atlas.tile("item/" + id);
            return ic.flat >= 0 ? ic : null;
        }
        ic.top = first(tex, "top", "end", "up", "all", "texture", "particle");
        ic.side = first(tex, "side", "all", "north", "texture", "particle");
        ic.front = first(tex, "front", "north", "side", "all", "texture", "particle");
        if (ic.top < 0 && ic.side < 0) {
            int t = atlas.tile("block/" + id);
            if (t < 0) t = atlas.tile("item/" + id);
            if (t < 0) return null;
            ic.flat = t;
            return ic;
        }
        if (ic.top < 0) ic.top = ic.side;
        if (ic.side < 0) ic.side = ic.top;
        if (ic.front < 0) ic.front = ic.side;
        ic.tint = TexturePack.tintFor(id);
        ic.tintSides = ic.tint != 0 && !id.equals("grass_block");
        return ic;
    }

    /** First "model" string in an item definition (1.21.4+ "items/*.json"), following conditions and selects. */
    private static String findModel(Object o) throws org.json.JSONException {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Object m = j.opt("model");
            if (m instanceof String) return (String) m;
            if (m != null) {
                String r = findModel(m);
                if (r != null) return r;
            }
            for (String k : new String[]{"fallback", "on_false", "on_true", "cases", "entries"}) {
                Object v = j.opt(k);
                if (v == null) continue;
                String r = findModel(v);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = findModel(a.get(i));
                if (r != null) return r;
            }
        }
        return null;
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
