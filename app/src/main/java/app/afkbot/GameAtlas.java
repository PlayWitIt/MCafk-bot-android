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
    final Map<String, Integer> tiles = new HashMap<String, Integer>(); // "block/stone", "item/apple", "entity/chest/normal"
    final byte[] alpha = new byte[GRID * GRID];
    int used;

    int tile(String name) {
        Integer t = tiles.get(name);
        return t == null ? -1 : t;
    }

    static GameAtlas build(Map<String, byte[]> files, TexturePack.Images img) {
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
            }
        }
        a.used = next;
        return a;
    }
}
