package app.afkbot;

import java.util.HashMap;
import java.util.Map;

/**
 * The Play screen's texture atlas: every block and item texture of the game in one 1024x1024 image (16x16 tiles on a
 * 64x64 grid), plus the 64x64 chest textures as 4x4-tile blocks. Platform neutral (PNG decoding is supplied).
 */
final class GameAtlas {
    static final int TILE = 16, GRID = 64, SIZE = TILE * GRID;
    /** Per tile: 0 opaque, 1 has fully transparent pixels (cutout), 2 has partly transparent pixels (translucent). */
    static final int OPAQUE = 0, CUTOUT = 1, TRANSLUCENT = 2;

    final int[] pixels = new int[SIZE * SIZE];

    /** Also decodes a whole image at its own size (animated textures are a vertical strip of frames). */
    interface Images extends TexturePack.Images {
        /** ARGB pixels of the whole image, with its size put in wh[0], wh[1]; or null. */
        int[] decodeFull(byte[] png, int[] wh);
    }

    /** An animated texture: its tile, every 16x16 frame, the frame order and ticks per frame (from its .mcmeta). */
    static final class Anim {
        int tile, frameTime = 1;
        int[][] frames;
        int[] order;
    }

    final java.util.List<Anim> anims = new java.util.ArrayList<Anim>();
    final Map<String, Integer> tiles = new HashMap<String, Integer>(); // "block/stone", "item/apple", "entity/chest/normal"
    final byte[] alpha = new byte[GRID * GRID];
    int used;

    int tile(String name) {
        Integer t = tiles.get(name);
        return t == null ? -1 : t;
    }

    static GameAtlas build(Map<String, byte[]> files, TexturePack.Images img) {
        Images full = img instanceof Images ? (Images) img : null;
        GameAtlas a = new GameAtlas();
        // tile 0: plain white (untextured faces get a flat colour)
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) a.pixels[y * SIZE + x] = 0xFFFFFFFF;
        // chests use 64x64 "entity" textures: 4x4-tile blocks from the bottom rows up
        int cx = 0, cy = GRID - 4;
        for (String name : new java.util.TreeSet<String>(files.keySet())) {
            if (!name.startsWith("assets/minecraft/textures/entity/") || !name.endsWith(".png")) continue;
            boolean half = name.endsWith("/skeleton.png") || name.endsWith("/wither_skeleton.png"); // 64x32 skins
            if (!half && !name.contains("/chest/") && !name.contains("/player/") && !name.endsWith("/zombie.png") && !name.endsWith("/creeper.png")
                    && !name.endsWith("/piglin.png") && !name.endsWith("shield_base_nopattern.png")) continue;
            int[] px = img.decode(files.get(name), 64);
            if (px == null) continue;
            if (half) { // decoded stretched to 64x64: squeeze back into the top half, leave the rest clear
                int[] top = new int[64 * 64];
                for (int y = 0; y < 32; y++) System.arraycopy(px, (y * 2) * 64, top, y * 64, 64);
                px = top;
            }
            for (int y = 0; y < 64; y++) System.arraycopy(px, y * 64, a.pixels, (cy * TILE + y) * SIZE + cx * TILE, 64);
            a.tiles.put(name.substring("assets/minecraft/textures/".length(), name.length() - 4), cy * GRID + cx);
            cx += 4;
            if (cx >= GRID) {
                cx = 0;
                cy -= 4;
            }
        }
        int limit = (cx > 0 ? cy : cy + 4) * GRID; // tiles below the chest blocks are free for textures
        int next = 1;
        for (String prefix : new String[]{"assets/minecraft/textures/block/", "assets/minecraft/textures/item/"}) {
            for (String name : new java.util.TreeSet<String>(files.keySet())) {
                if (!name.startsWith(prefix) || !name.endsWith(".png") || next >= limit) continue;
                int[] px = img.decode16(files.get(name));
                if (px == null) continue;
                int t = next++;
                int tx = (t % GRID) * TILE, ty = (t / GRID) * TILE;
                int mode = OPAQUE;
                for (int y = 0; y < TILE; y++) {
                    System.arraycopy(px, y * TILE, a.pixels, (ty + y) * SIZE + tx, TILE);
                    for (int x = 0; x < TILE; x++) {
                        int al = px[y * TILE + x] >>> 24;
                        if (al == 0) mode = Math.max(mode, CUTOUT);
                        else if (al < 255) mode = TRANSLUCENT;
                    }
                }
                a.alpha[t] = (byte) mode;
                a.tiles.put(name.substring("assets/minecraft/textures/".length(), name.length() - 4), t);
                byte[] meta = files.get(name + ".mcmeta");
                if (meta != null && full != null) {
                    Anim an = animation(t, files.get(name), new String(meta, java.nio.charset.StandardCharsets.UTF_8), full);
                    if (an != null) a.anims.add(an);
                }
            }
        }
        a.used = next;
        return a;
    }

    private static Anim animation(int tile, byte[] png, String meta, Images img) {
        int[] wh = new int[2];
        int[] px = img.decodeFull(png, wh);
        if (px == null) return null;
        int w = wh[0], h = wh[1];
        if (w <= 0 || h < w * 2 || h % w != 0) return null;
        int n = h / w;
        Anim an = new Anim();
        an.tile = tile;
        an.frames = new int[n][];
        for (int f = 0; f < n; f++) { // each frame scaled to 16x16 like the atlas tile
            int[] fr = new int[TILE * TILE];
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++) fr[y * TILE + x] = px[(f * w + y * w / TILE) * w + x * w / TILE];
            an.frames[f] = fr;
        }
        int[] order = null;
        try {
            org.json.JSONObject anim = new org.json.JSONObject(meta).optJSONObject("animation");
            if (anim == null) return null;
            an.frameTime = Math.max(1, anim.optInt("frametime", 1));
            org.json.JSONArray fr = anim.optJSONArray("frames");
            if (fr != null && fr.length() > 0) {
                java.util.List<Integer> l = new java.util.ArrayList<Integer>();
                for (int i = 0; i < fr.length(); i++) {
                    Object o = fr.get(i);
                    int idx = o instanceof org.json.JSONObject ? ((org.json.JSONObject) o).optInt("index") : fr.getInt(i);
                    int time = o instanceof org.json.JSONObject ? ((org.json.JSONObject) o).optInt("time", an.frameTime) : an.frameTime;
                    for (int k = 0; k < Math.max(1, time / an.frameTime); k++) l.add(Math.max(0, Math.min(n - 1, idx)));
                }
                order = new int[l.size()];
                for (int i = 0; i < order.length; i++) order[i] = l.get(i);
            }
        } catch (org.json.JSONException e) {
            return null;
        }
        if (order == null) {
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
        }
        an.order = order;
        return an;
    }

    /** The frame of an animation at a game tick. */
    static int[] frameAt(Anim an, long tick) {
        return an.frames[an.order[(int) ((tick / an.frameTime) % an.order.length)]];
    }
}
