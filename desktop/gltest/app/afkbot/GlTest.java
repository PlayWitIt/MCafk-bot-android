package app.afkbot;

import android.opengl.GLES20;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

/**
 * Runs the Play screen's real GameRenderer (GLES 2 shaders, VBOs, section meshing threads, culling, overlays) on a
 * PC with Mesa, against the local test server, and saves frames as PNGs: desktop/run.sh gl [outDir]
 */
public class GlTest {
    static final int W = 1600, H = 720; // a 2400x1080 phone's proportions (with -Dafk.density=1.83)
    static GameHud hud;
    static GameBiomes loadedBiomes;

    public static void main(String[] args) throws Throwable {
        String out = args.length > 0 ? args[0] : "desktop/preview";
        new File(out).mkdirs();
        Bot b = PreviewTest.join();
        PreviewTest.scene();
        ProtoDef.VersionData vd = Hub.versionData;
        Shapes.setVersion(vd);
        Map<String, byte[]> files = PreviewTest.Textures.files(Hub.versionName);
        GameAtlas atlas = GameAtlas.build(files, new PreviewTest.Textures.Awt());
        GameAssetStore.loadLanguage(files);
        loadedBiomes = new GameBiomes(files, new PreviewTest.Textures.Awt());
        System.out.println("[GL] animated textures: " + atlas.anims.size());
        System.out.println("[GL] translated: " + Text.flatten(new org.json.JSONObject("{\"translate\":\"death.attack.mob\",\"with\":[\"Tester\",{\"translate\":\"entity.minecraft.slime\"}]}").toMap()));
        GameModels models = new GameModels(files, atlas, vd);
        GameAssetStore.Loaded loaded = new GameAssetStore.Loaded(Hub.versionName, files, atlas, models,
                android.graphics.Bitmap.createBitmap(atlas.pixels, GameAtlas.SIZE, GameAtlas.SIZE, android.graphics.Bitmap.Config.ARGB_8888));
        // something to show in the hotbar, and a few hearts missing
        for (String g : new String[]{"diamond_sword", "oak_planks 64", "torch 32", "grass_block 12", "apple 5", "oak_log 20", "glass 16", "bow", "furnace"})
            PreviewTest.cmd("give Tester minecraft:" + g);
        PreviewTest.cmd("fillbiome -6 -64 -6 2 -40 22 minecraft:swamp");
        PreviewTest.cmd("fillbiome 12 -64 -6 22 -40 22 minecraft:jungle");
        Thread.sleep(500);
        b.reloadTerrain(); // fillbiome's changes come in a separate packet: just have the chunks sent again
        Thread.sleep(3000);
        PreviewTest.cmd("gamerule doMobSpawning false");
        PreviewTest.cmd("kill @e[type=slime]");
        PreviewTest.cmd("effect give Tester minecraft:resistance infinite 255 true");
        PreviewTest.cmd("difficulty easy");
        String[] mobs = {"zombie", "skeleton", "creeper", "cow", "pig", "sheep", "chicken", "villager", "iron_golem", "spider"};
        PreviewTest.cmd("kill @e[type=!player]"); // earlier runs' persistent mobs
        Thread.sleep(500);
        for (int i = 0; i < mobs.length; i++)
            PreviewTest.cmd("summon minecraft:" + mobs[i] + " " + (-4 + i * 1.6) + " -60 -9 {NoAI:1b,Rotation:[0f,0f],PersistenceRequired:1b,equipment:{head:{id:\"minecraft:stone_button\",count:1}}}");
        // a few that walk around, to see the walk animation
        PreviewTest.cmd("summon minecraft:pig 6 -60 -6");
        PreviewTest.cmd("summon minecraft:cow 0 -60 -6");
        EntityModels.init(new android.content.Context());
        for (String t : mobs) { // wait for each model to download (the game shows boxes until then)
            EntityModels.get(t);
            for (int w = 0; w < 40 && EntityModels.get(t) == null; w++) Thread.sleep(500);
        }
        PreviewTest.cmd("damage Tester 7");
        PreviewTest.cmd("xp set Tester 12 levels");
        Thread.sleep(1200);

        GlContext.create(W, H);
        GameRenderer r = new GameRenderer();
        hud = new GameHud(new android.content.Context(), r, new GameHud.Host() {
            public void openChat() {
            }

            public void leave() {
            }

            public void settingsChanged() {
            }
        });
        hud.assets = loaded;
        hud.setDesktopSize(W, H);
        MapData.changes = r;
        loaded.biomes = loadedBiomes;
        r.assets = loaded;
        r.renderDistance = 4;
        r.onSurfaceCreated(null, null);
        r.onSurfaceChanged(null, W, H);
        b.manual = true;
        shot(r, b, out + "/gl_day.png", 0, 12, 6000);
        PreviewTest.cmd("time set 18000");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_night.png", 0, -25, 18000);
        PreviewTest.cmd("time set 23200");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_sunrise.png", -90, -8, 23200);
        PreviewTest.cmd("kill @e[type=slime]");
        PreviewTest.cmd("time set 3000");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_sky.png", -90, -45, 3000);
        PreviewTest.cmd("time set 14500");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_moon.png", -90, -30, 14500);
        PreviewTest.cmd("time set 6000");
        PreviewTest.cmd("tp Tester 10.5 -59.5 12.5 135 28");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_close.png", 135, 28, 6000);
        PreviewTest.cmd("fill 7 -60 11 11 -58 13 air");
        PreviewTest.cmd("setblock 8 -60 12 minecraft:chest[facing=south,type=right]");
        PreviewTest.cmd("setblock 9 -60 12 minecraft:chest[facing=south,type=left]");
        PreviewTest.cmd("setblock 11 -60 11 minecraft:trapped_chest[facing=west,type=right]");
        PreviewTest.cmd("setblock 11 -60 12 minecraft:trapped_chest[facing=west,type=left]");
        PreviewTest.cmd("tp Tester 6.5 -60 8.5");
        Thread.sleep(2000);
        shot(r, b, out + "/gl_chests.png", -45, 35, 6000);
        PreviewTest.cmd("tp Tester 8.9 -60 15.5");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_chests_front.png", 180, 40, 6000);
        PreviewTest.cmd("tp Tester 3 -60 -16 0 15");
        Thread.sleep(1500);
        // a stand-in player with a "real" skin: a recoloured Steve served from the skin cache, so no network is needed
        {
            android.content.Context ctx = new android.content.Context();
            GameSkins.init(ctx);
            java.io.File skinDir = new java.io.File(ctx.getFilesDir(), "skins");
            EntityModels.Model pm = null;
            for (int w = 0; w < 40 && (pm = EntityModels.get("player")) == null; w++) Thread.sleep(500);
            java.awt.image.BufferedImage st = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                int c = pm.texture.getPixel(x, y);
                if (y >= 16 && y < 48 && x >= 16 && x < 40) c = (c & 0xFF000000) | 0xC02020; // red shirt
                st.setRGB(x, y, c);
            }
            javax.imageio.ImageIO.write(st, "png", new java.io.File(skinDir, "testskin.png"));
            java.util.UUID u = java.util.UUID.randomUUID();
            World.putName(u, "SkinTest");
            World.putSkin(u, "http://textures.minecraft.net/texture/testskin");
            World.add(new World.Ent(990001, 'p', "player", u, 3.0, -60, -3.5));
            GameSkins.get("http://textures.minecraft.net/texture/testskin");
            Thread.sleep(1500);
        }
        for (int i = 0; i < 3; i++) { shot(r, b, out + "/gl_mobs.png", 0, 8, 6000); } // models download on first sight
        java.util.Set<String> types = new java.util.TreeSet<String>();
        for (World.Dot d : World.snapshot(null)) if (d.kind != 'i') types.add(d.type + "(" + d.kind + ")");
        Thread.sleep(3000);
        for (int w = 0; w < 20 && EntityModels.get("slime") == null; w++) Thread.sleep(500);
        System.out.println("[GL] slime after waiting: " + (EntityModels.get("slime") != null));
        for (String t : types) {
            String ty = t.substring(0, t.indexOf('('));
            EntityModels.Model m = EntityModels.get(ty);
            System.out.println("[GL] present " + t + ": " + (m == null ? "no model" : "model ok"));
        }
        PreviewTest.cmd("tp Tester 14.5 -59 5.4 180 25");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_torch_room.png", 180, 25, 6000);
        PreviewTest.cmd("tp Tester 5 -60 -4 0 10");
        Thread.sleep(1500);
        b.selectSlot(2);
        Thread.sleep(600);
        shot(r, b, out + "/gl_hand_torch.png", 0, 12, 6000);
        b.selectSlot(3);
        Thread.sleep(600);
        shot(r, b, out + "/gl_hand_block.png", 0, 12, 6000);
        b.selectSlot(0);
        Thread.sleep(600);
        hud.screen = GameHud.INVENTORY;
        shot(r, b, out + "/gl_inventory.png", 0, 12, 6000);
        hud.screen = GameHud.PAUSE;
        shot(r, b, out + "/gl_pause.png", 0, 12, 6000);
        hud.screen = GameHud.NONE;
        System.out.println("[GL] wrote gl_*.png, GL error: " + GLES20.glGetError());
        r.stop();
        b.stop();
        Thread.sleep(300);
        System.exit(0);
    }

    static void shot(GameRenderer r, Bot b, String path, float yaw, float pitch, long time) throws Exception {
        GameState.dayTime = time;
        GameState.dayTimeAt = System.currentTimeMillis();
        GameState.dayTicks = false;
        r.yaw = yaw;
        r.pitch = pitch;
        b.lookYaw = yaw;
        b.lookPitch = pitch;
        long t0 = System.currentTimeMillis();
        int frames = 0;
        while (System.currentTimeMillis() - t0 < 2500) { // let the meshing threads catch up
            r.onDrawFrame(null);
            frames++;
            Thread.sleep(10);
        }
        long f0 = System.nanoTime();
        r.onDrawFrame(null);
        double ms = (System.nanoTime() - f0) / 1e6;
        byte[] px = GLES20.readPixels(W, H);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = ((H - 1 - y) * W + x) * 4;
                img.setRGB(x, y, (px[i] & 255) << 16 | (px[i + 1] & 255) << 8 | (px[i + 2] & 255));
            }
        hud.onDraw(new android.graphics.Canvas(img)); // the HUD exactly as the phone draws it
        javax.imageio.ImageIO.write(img, "png", new File(path));
        System.out.println(String.format("[GL] %s: %d frames, last frame %.1f ms (software GL), target %s %s", path, frames, ms,
                r.targetBlock == null ? "-" : r.targetBlock[0] + "," + r.targetBlock[1] + "," + r.targetBlock[2] + " face " + r.targetBlock[3],
                r.targetEntity == null ? "" : r.targetEntity.label));
    }
}
