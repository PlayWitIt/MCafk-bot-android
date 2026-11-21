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
        System.out.println("[GL] translated: " + Text.flatten(new org.json.JSONObject("{\"translate\":\"death.attack.mob\",\"with\":[\"Tester\",{\"translate\":\"entity.minecraft.slime\"}]}").toMap()));
        GameModels models = new GameModels(files, atlas, vd);
        GameAssetStore.Loaded loaded = new GameAssetStore.Loaded(Hub.versionName, files, atlas, models,
                android.graphics.Bitmap.createBitmap(atlas.pixels, GameAtlas.SIZE, GameAtlas.SIZE, android.graphics.Bitmap.Config.ARGB_8888));
        // something to show in the hotbar, and a few hearts missing
        for (String g : new String[]{"diamond_sword", "oak_planks 64", "torch 32", "grass_block 12", "apple 5", "oak_log 20", "glass 16", "bow", "furnace"})
            PreviewTest.cmd("give Tester minecraft:" + g);
        PreviewTest.cmd("gamerule doMobSpawning false");
        PreviewTest.cmd("kill @e[type=slime]");
        PreviewTest.cmd("effect give Tester minecraft:resistance infinite 255 true");
        PreviewTest.cmd("difficulty easy");
        String[] mobs = {"zombie", "skeleton", "creeper", "cow", "pig", "sheep", "chicken", "villager", "iron_golem", "spider"};
        for (int i = 0; i < mobs.length; i++)
            PreviewTest.cmd("summon minecraft:" + mobs[i] + " " + (-4 + i * 1.6) + " -60 -9 {NoAI:1b,Rotation:[0f,0f],PersistenceRequired:1b}");
        EntityModels.init(new android.content.Context());
        PreviewTest.cmd("damage Tester 7");
        PreviewTest.cmd("xp set Tester 12 levels");
        Thread.sleep(1200);

        for (int[] p : new int[][]{{7, -60, 0}, {7, -60, -1}, {6, -60, -1}, {7, -61, -2}, {5, -60, -3}, {14, -59, 4}, {14, -59, 3}})
            System.out.println("[GL] light at " + p[0] + "," + p[1] + "," + p[2] + " = " + Integer.toHexString(MapData.lightAt(p[0], p[1], p[2])) + " block " + Shapes.id(MapData.stateAt(p[0], p[1], p[2])));
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
        r.assets = loaded;
        r.renderDistance = 4;
        r.onSurfaceCreated(null, null);
        r.onSurfaceChanged(null, W, H);
        b.manual = true;
        shot(r, b, out + "/gl_day.png", 0, 12, 6000);
        PreviewTest.cmd("time set 18000");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_night.png", 0, 12, 18000);
        PreviewTest.cmd("time set 6000");
        PreviewTest.cmd("tp Tester 10.5 -59.5 12.5 135 28");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_close.png", 135, 28, 6000);
        PreviewTest.cmd("tp Tester 3 -60 -16 0 15");
        Thread.sleep(1500);
        for (World.Dot d : World.snapshot(null)) System.out.println(String.format("[GL] entity %s kind %c type %s at %.1f %.1f %.1f yaw %.0f", d.label, d.kind, d.type, d.dx, d.dy, d.dz, d.yaw));
        for (int i = 0; i < 3; i++) { shot(r, b, out + "/gl_mobs.png", 0, 8, 6000); } // models download on first sight
        PreviewTest.cmd("tp Tester 14.5 -59 5.4 180 25");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_torch_room.png", 180, 25, 6000);
        PreviewTest.cmd("tp Tester 5 -60 -4 0 10");
        Thread.sleep(1500);
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
