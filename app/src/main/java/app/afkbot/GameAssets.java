package app.afkbot;

/**
 * Which game files the Play screen needs from Mojang's client jar (downloaded on the phone with range requests, like
 * the light 3D view's textures, but a bigger set: item icons, HUD sprites, crack stages, sun and moon). Platform
 * neutral; GameAssetStore does the Android side (download, atlas, caching).
 */
final class GameAssets {
    private GameAssets() {
    }

    static boolean wanted(String n) {
        if (n.endsWith(".mcmeta")) return false;
        return n.startsWith("assets/minecraft/blockstates/") || n.startsWith("assets/minecraft/models/block/")
                || n.startsWith("assets/minecraft/models/item/") || n.startsWith("assets/minecraft/items/")
                || n.startsWith("assets/minecraft/textures/block/") || n.startsWith("assets/minecraft/textures/item/")
                || n.startsWith("assets/minecraft/textures/entity/chest/")
                || n.startsWith("assets/minecraft/textures/gui/sprites/hud/")
                || n.equals("assets/minecraft/textures/environment/sun.png")
                || n.equals("assets/minecraft/textures/environment/moon_phases.png")
                || n.equals("assets/minecraft/textures/environment/moon/full_moon.png");
    }
}
