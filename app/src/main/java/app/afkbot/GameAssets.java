package app.afkbot;

/**
 * Which game files the Play screen needs from Mojang's client jar (downloaded on the phone with range requests, like
 * the light 3D view's textures, but a bigger set: item icons, HUD sprites, crack stages, sun and moon). Platform
 * neutral; GameAssetStore does the Android side (download, atlas, caching).
 */
final class GameAssets {
    private GameAssets() {
    }

    /** Bump when wanted() changes, so phones download the new set (the file name includes it). */
    static final int FORMAT = 2;

    static boolean wanted(String n) {
        if (n.endsWith(".mcmeta")) return n.startsWith("assets/minecraft/textures/block/"); // animation timing
        return n.startsWith("assets/minecraft/blockstates/") || n.startsWith("assets/minecraft/models/block/")
                || n.startsWith("assets/minecraft/models/item/") || n.startsWith("assets/minecraft/items/")
                || n.startsWith("assets/minecraft/textures/block/") || n.startsWith("assets/minecraft/textures/item/")
                || n.startsWith("assets/minecraft/textures/entity/chest/")
                || n.startsWith("assets/minecraft/textures/gui/sprites/hud/")
                || n.startsWith("assets/minecraft/textures/environment/") // sun, moon (both layouts), clouds
                || n.equals("assets/minecraft/textures/font/ascii.png")
                || n.equals("assets/minecraft/lang/en_us.json")
                || n.equals("assets/minecraft/textures/gui/container/inventory.png")
                || n.equals("assets/minecraft/textures/gui/container/generic_54.png")
                || n.startsWith("assets/minecraft/textures/gui/sprites/widget/button")
                || n.startsWith("assets/minecraft/textures/entity/player/wide/") || n.startsWith("assets/minecraft/textures/entity/player/slim/")
                || n.startsWith("assets/minecraft/textures/colormap/")
                || n.equals("assets/minecraft/textures/entity/zombie/zombie.png") || n.equals("assets/minecraft/textures/entity/skeleton/skeleton.png")
                || n.equals("assets/minecraft/textures/entity/skeleton/wither_skeleton.png") || n.equals("assets/minecraft/textures/entity/creeper/creeper.png")
                || n.equals("assets/minecraft/textures/entity/piglin/piglin.png") || n.equals("assets/minecraft/textures/entity/shield_base_nopattern.png");
    }
}
