package app.afkbot;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Biome colours for the Play screen, worked out like the game: each biome's temperature and downfall (from the game's
 * own data pack, data/minecraft/worldgen/biome/*.json) pick a pixel in the grass and foliage colour maps
 * (textures/colormap/*.png); biomes can override the result (foliage_color, grass_color, water_color) and swamps and
 * dark forests modify grass. Platform neutral.
 */
final class GameBiomes {
    static final int GRASS = 1, FOLIAGE = 2, WATER = 3;

    private final int[] grassMap, foliageMap; // 256x256 ARGB, or null
    private final Map<String, byte[]> files;
    private final java.util.HashMap<String, int[]> perBiome = new java.util.HashMap<String, int[]>(); // name -> {grass, foliage, water}

    GameBiomes(Map<String, byte[]> files, GameAtlas.Images img) {
        this.files = files;
        grassMap = map(files, img, "assets/minecraft/textures/colormap/grass.png");
        foliageMap = map(files, img, "assets/minecraft/textures/colormap/foliage.png");
    }

    private static int[] map(Map<String, byte[]> files, GameAtlas.Images img, String path) {
        byte[] b = files.get(path);
        if (b == null || img == null) return null;
        int[] wh = new int[2];
        int[] px = img.decodeFull(b, wh);
        return px != null && wh[0] == 256 && wh[1] == 256 ? px : null;
    }

    /** The colour of a kind (GRASS, FOLIAGE, WATER) in a biome id from the chunk data; fallback if unknown. */
    int color(int kind, int biomeId, int fallback) {
        String[] names = GameState.biomeNames;
        if (biomeId < 0 || names == null || biomeId >= names.length) return fallback;
        int[] c = colors(names[biomeId]);
        if (c == null) return fallback;
        return c[kind - 1];
    }

    private synchronized int[] colors(String name) {
        if (perBiome.containsKey(name)) return perBiome.get(name);
        int[] c = null;
        byte[] b = files.get("data/minecraft/worldgen/biome/" + name + ".json");
        if (b != null) {
            try {
                JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
                double temp = j.optDouble("temperature", 0.8), down = j.optDouble("downfall", 0.4);
                JSONObject fx = j.optJSONObject("effects");
                int grass = lookup(grassMap, temp, down, 0x91BD59), foliage = lookup(foliageMap, temp, down, 0x77AB2F), water = 0x3F76E4;
                if (fx != null) {
                    if (fx.has("grass_color")) grass = color(fx.opt("grass_color"), grass);
                    if (fx.has("foliage_color")) foliage = color(fx.opt("foliage_color"), foliage);
                    if (fx.has("water_color")) water = color(fx.opt("water_color"), water);
                    String mod = fx.optString("grass_color_modifier", "");
                    if (mod.equals("dark_forest")) grass = ((grass & 0xFEFEFE) + 0x28340A) >> 1;
                    else if (mod.equals("swamp")) grass = 0x6A7039; // the game varies this with noise; this is its common shade
                }
                c = new int[]{grass, foliage, water};
            } catch (org.json.JSONException ignored) {
            }
        }
        perBiome.put(name, c);
        return c;
    }

    /** The game's colour map lookup: x from temperature, y from downfall scaled by temperature. */
    private static int lookup(int[] map, double temp, double down, int fallback) {
        if (map == null) return fallback;
        double t = Math.max(0, Math.min(1, temp)), d = Math.max(0, Math.min(1, down)) * t;
        int x = (int) ((1 - t) * 255), y = (int) ((1 - d) * 255);
        return map[y * 256 + x] & 0xFFFFFF;
    }

    /** Colours in the data pack are "#rrggbb" (newer) or plain integers (older). */
    private static int color(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue() & 0xFFFFFF;
        String s = String.valueOf(o);
        try {
            return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Which biome colour a block uses: 0 none (fixed tint), else GRASS / FOLIAGE / WATER. */
    static int kindOf(String id) {
        switch (id) {
            case "grass_block": case "short_grass": case "tall_grass": case "fern": case "large_fern": case "potted_fern": case "sugar_cane":
                return GRASS;
            case "oak_leaves": case "jungle_leaves": case "acacia_leaves": case "dark_oak_leaves": case "mangrove_leaves": case "vine":
                return FOLIAGE;
            case "water": case "bubble_column": case "water_cauldron":
                return WATER;
            default:
                return 0;
        }
    }
}
