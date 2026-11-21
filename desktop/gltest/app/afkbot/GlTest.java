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
    static final int W = 960, H = 540;

    public static void main(String[] args) throws Throwable {
        String out = args.length > 0 ? args[0] : "desktop/preview";
        new File(out).mkdirs();
        Bot b = PreviewTest.join();
        PreviewTest.scene();
        ProtoDef.VersionData vd = Hub.versionData;
        Shapes.setVersion(vd);
        Map<String, byte[]> files = PreviewTest.Textures.files(Hub.versionName);
        GameAtlas atlas = GameAtlas.build(files, new PreviewTest.Textures.Awt());
        GameModels models = new GameModels(files, atlas, vd);
        GameAssetStore.Loaded loaded = new GameAssetStore.Loaded(Hub.versionName, files, atlas, models, null);

        GlContext.create(W, H);
        GameRenderer r = new GameRenderer();
        MapData.changes = r;
        r.assets = loaded;
        r.renderDistance = 4;
        r.onSurfaceCreated(null, null);
        r.onSurfaceChanged(null, W, H);
        b.manual = true;
        shot(r, b, out + "/gl_day.png", 0, 12, 6000);
        shot(r, b, out + "/gl_night.png", 0, 12, 18000);
        PreviewTest.cmd("tp Tester 10.5 -59.5 12.5 135 28");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_close.png", 135, 28, 6000);
        PreviewTest.cmd("tp Tester 14.5 -59 5.4 180 25");
        Thread.sleep(1500);
        shot(r, b, out + "/gl_torch_room.png", 180, 25, 6000);
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
        javax.imageio.ImageIO.write(img, "png", new File(path));
        System.out.println(String.format("[GL] %s: %d frames, last frame %.1f ms (software GL), target %s %s", path, frames, ms,
                r.targetBlock == null ? "-" : r.targetBlock[0] + "," + r.targetBlock[1] + "," + r.targetBlock[2] + " face " + r.targetBlock[3],
                r.targetEntity == null ? "" : r.targetEntity.label));
    }
}
