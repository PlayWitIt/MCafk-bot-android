package app.afkbot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders what the Play screen would show, on the PC: joins the local test server (desktop/realserver.sh) with light
 * data on, builds a test scene, meshes the sections around the player with the real GameMesher and draws them with a
 * small software rasterizer that mimics the GL shader. Writes PNGs so the result can be looked at.
 *
 *   desktop/run.sh preview [outDir]
 */
public class PreviewTest {
    static void say(String s) {
        System.out.println("[PREVIEW] " + s);
    }

    static void cmd(String c) throws Exception {
        java.io.FileWriter w = new java.io.FileWriter(System.getProperty("afk.cmd", "desktop/realsrv/cmd"));
        w.write(c + "\n");
        w.close();
        Thread.sleep(120);
    }

    public static void main(String[] a) throws Exception {
        String outDir = a.length > 0 ? a[0] : "desktop/preview";
        new File(outDir).mkdirs();
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        MapData.keepLight = true;
        GameState.open = true;
        Bot.Config c = new Bot.Config();
        c.address = "127.0.0.1:25601";
        c.offline = true;
        c.offlineName = "Tester";
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        c.viewDistance = 4;
        Bot b = new Bot(c, null);
        b.start();
        for (int i = 0; i < 100 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        Thread.sleep(1500);
        ProtoDef.VersionData vd = Hub.versionData;
        say("in world, version " + Hub.versionName + ", data " + (vd != null));

        // ---- a test scene: house with glass, torch-lit dark room, water, chest, slabs/stairs, plants, a tree ----
        int X = 0, Y = -60, Z = 0;
        cmd("time set 6000");
        cmd("weather clear");
        cmd("fill -6 -60 -6 22 -50 22 minecraft:air");
        cmd("fill -6 -61 -6 22 -61 22 minecraft:grass_block");
        cmd("fill 2 -60 2 8 -56 8 minecraft:oak_planks hollow");
        cmd("fill 2 -60 5 2 -59 5 minecraft:air");
        cmd("setblock 2 -60 5 minecraft:oak_door[facing=west,half=lower]");
        cmd("setblock 2 -59 5 minecraft:oak_door[facing=west,half=upper]");
        cmd("fill 5 -58 2 6 -58 2 minecraft:glass");
        cmd("fill 2 -56 2 8 -56 8 minecraft:oak_slab[type=bottom]");
        cmd("fill 12 -60 2 16 -57 6 minecraft:stone hollow");
        cmd("setblock 14 -59 4 minecraft:torch");
        cmd("setblock 13 -59 3 minecraft:wall_torch[facing=east]");
        cmd("fill -4 -61 10 0 -61 14 minecraft:water");
        cmd("setblock 4 -60 12 minecraft:chest[facing=south]");
        cmd("setblock 6 -60 12 minecraft:copper_chest[facing=south]");
        cmd("fill 8 -60 12 10 -60 12 minecraft:stone_brick_stairs[facing=west]");
        cmd("fill 8 -60 13 10 -60 13 minecraft:smooth_stone_slab");
        cmd("fill -2 -60 4 0 -60 6 minecraft:short_grass");
        cmd("setblock -1 -60 7 minecraft:poppy");
        cmd("setblock 0 -60 7 minecraft:dandelion");
        cmd("place feature minecraft:oak 18 -60 14");
        cmd("fill 10 -60 8 10 -58 8 minecraft:oak_fence");
        cmd("setblock 11 -60 9 minecraft:lantern");
        cmd("fill 10 -60 8 10 -60 10 minecraft:oak_fence");
        cmd("fill 9 -60 10 9 -60 10 minecraft:oak_fence");
        cmd("fill 0 -60 9 0 -58 11 minecraft:glass_pane");
        cmd("fill -3 -60 1 -3 -60 6 minecraft:rail");
        cmd("fill 12 -61 9 15 -61 11 minecraft:farmland");
        cmd("fill 12 -60 9 15 -60 11 minecraft:wheat[age=7]");
        cmd("setblock 1 -60 3 minecraft:ladder[facing=west]");
        cmd("setblock 1 -59 3 minecraft:ladder[facing=west]");
        cmd("setblock 3 -60 0 minecraft:potted_red_tulip");
        cmd("setblock 7 -60 0 minecraft:wall_torch[facing=north]");
        cmd("tp Tester 5 -60 -4 0 10");
        Thread.sleep(4000);

        // ---- textures (cached after the first download) ----
        Map<String, byte[]> files = Textures.files(Hub.versionName);
        long ta = System.nanoTime();
        GameAtlas atlas = GameAtlas.build(files, new Textures.Awt());
        GameModels models = new GameModels(files, atlas, vd);
        Shapes.setVersion(vd);
        say(String.format("atlas: %d tiles in %.0f ms", atlas.used, (System.nanoTime() - ta) / 1e6));

        // ---- mesh the sections around the player ----
        GameMesher mesher = new GameMesher(vd, atlas, models);
        // diagnostics
        int grass = MapData.stateAt(-5, -61, -5);
        say("grass state " + grass + " " + Shapes.id(grass) + " quads " + models.quads(grass).length + " tile(top)=" + atlas.tile("block/grass_block_top"));
        if (models.lastError != null) models.lastError.printStackTrace(System.out);
        for (GameModels.Quad q : models.quads(grass))
            say(String.format("  dir %d cull %d tint %b uv %.4f,%.4f %.4f,%.4f", q.dir, q.cull, q.tint, q.uv[0][0], q.uv[0][1], q.uv[2][0], q.uv[2][1]));
        for (int[] p : new int[][]{{14, -59, 4}, {14, -58, 5}, {5, -59, 5}, {5, -50, 5}, {-5, -60, -5}})
            say("light at " + p[0] + "," + p[1] + "," + p[2] + " = " + Integer.toHexString(MapData.lightAt(p[0], p[1], p[2])));
        MapData.Chunk ch = MapData.chunk(0, 0);
        say("chunk 0,0 light arrays: " + (ch == null ? "no chunk" : ch.sky == null ? "none" : ch.sky.length + " sections"));
        int pcx = (int) Math.floor(World.selfX) >> 4, pcz = (int) Math.floor(World.selfZ) >> 4;
        int minSec = MapData.minY >> 4;
        List<GameMesher.Mesh> meshes = new ArrayList<GameMesher.Mesh>();
        long t0 = System.nanoTime();
        int sections = 0, quads = 0;
        for (int cx = pcx - 3; cx <= pcx + 3; cx++)
            for (int cz = pcz - 3; cz <= pcz + 3; cz++)
                for (int sy = minSec; sy < minSec + 24; sy++) {
                    GameMesher.Mesh m = mesher.build(cx, sy, cz);
                    if (m == null) continue;
                    sections++;
                    if (m.empty()) continue;
                    meshes.add(m);
                    quads += m.quads[0] + m.quads[1] + m.quads[2];
                }
        say(String.format("meshed %d sections (%d with geometry, %d quads) in %.0f ms; light at player: %s", sections, meshes.size(), quads,
                (System.nanoTime() - t0) / 1e6, Integer.toHexString(MapData.lightAt((int) Math.floor(World.selfX), (int) Math.floor(World.selfY) + 1, (int) Math.floor(World.selfZ)))));

        Raster r = new Raster(960, 540, atlas.pixels);
        double ex = 5.5, ey = -58.4, ez = -5;
        r.render(meshes, ex, ey, ez, 0, 12, 1f);
        r.save(outDir + "/day.png");
        r.render(meshes, ex, ey, ez, 0, 12, 0f);
        r.save(outDir + "/night.png");
        r.render(meshes, 10.5, -57.6, 12.5, 135, 28, 1f);
        r.save(outDir + "/close.png");
        r.render(meshes, 14.5, -58.4, 5.4, 180, 25, 1f); // inside the closed, torch-lit stone room
        r.save(outDir + "/torch_room.png");
        r.render(meshes, 3.5, -58.6, -2.5, 300, 22, 1f);
        r.save(outDir + "/details.png");
        r.render(meshes, 8, -38, 8, 0, 89.9f, 1f);
        r.save(outDir + "/top.png");
        say("wrote " + outDir + "/day.png, night.png, close.png, torch_room.png");
        b.stop();
        Thread.sleep(300);
        System.exit(0);
    }

    /** Minimal perspective-correct, z-buffered rasterizer for GameMesher output (same lighting as the shader). */
    static final class Raster {
        final int w, h;
        final int[] px;
        final float[] zb;
        final int[] atlas;
        final float[] lc = new float[3];

        Raster(int w, int h, int[] atlas) {
            this.w = w;
            this.h = h;
            px = new int[w * h];
            zb = new float[w * h];
            this.atlas = atlas;
        }

        double ex, ey, ez, sy, cy, sp, cp, f;
        float daylight;

        void render(List<GameMesher.Mesh> meshes, double ex, double ey, double ez, float yaw, float pitch, float daylight) {
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.daylight = daylight;
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            sy = Math.sin(yr);
            cy = Math.cos(yr);
            sp = Math.sin(pr);
            cp = Math.cos(pr);
            f = (h / 2.0) / Math.tan(Math.toRadians(70) / 2);
            java.util.Arrays.fill(px, GameLight.skyColor(daylight, 0));
            java.util.Arrays.fill(zb, Float.MAX_VALUE);
            for (int pass = 0; pass < 3; pass++)
                for (GameMesher.Mesh m : meshes) {
                    byte[] d = m.data[pass];
                    if (d == null) continue;
                    for (int q = 0; q < m.quads[pass]; q++) {
                        int base = q * 4 * GameMesher.BYTES;
                        tri(m, d, pass, base, 0, 1, 2);
                        tri(m, d, pass, base, 0, 2, 3);
                    }
                }
        }

        /** Camera space: x right, y up, z forward (Minecraft yaw 0 = looking south, +z). */
        double[] cam(GameMesher.Mesh m, byte[] d, int v) {
            int p = v;
            double wx = m.sx * 16 + GameMesher.getShort(d, p) / GameMesher.POS - ex;
            double wy = m.sy * 16 + GameMesher.getShort(d, p + 2) / GameMesher.POS - ey;
            double wz = m.sz * 16 + GameMesher.getShort(d, p + 4) / GameMesher.POS - ez;
            // yaw: rotate so that the look direction (-sin yaw, cos yaw) becomes +z
            double x1 = wx * cy + wz * sy, z1 = -wx * sy + wz * cy;
            double x = -x1;
            double y2 = wy * cp + z1 * sp, z2 = -wy * sp + z1 * cp;
            double u = (GameMesher.getShort(d, p + 6) & 0xFFFF) / 65535.0, vv = (GameMesher.getShort(d, p + 8) & 0xFFFF) / 65535.0;
            return new double[]{x, y2, z2, u, vv, d[p + 10] & 255, d[p + 11] & 255, d[p + 12] & 255, d[p + 13] & 255, d[p + 14] & 255, d[p + 15] & 255};
        }

        void tri(GameMesher.Mesh m, byte[] d, int pass, int base, int i0, int i1, int i2) {
            List<double[]> poly = new ArrayList<double[]>();
            poly.add(cam(m, d, base + i0 * GameMesher.BYTES));
            poly.add(cam(m, d, base + i1 * GameMesher.BYTES));
            poly.add(cam(m, d, base + i2 * GameMesher.BYTES));
            // back-face cull in camera space (CCW seen from outside = front); cutout plants are double sided anyway
            double[] a0 = poly.get(0), a1 = poly.get(1), a2 = poly.get(2);
            double nx = (a1[1] - a0[1]) * (a2[2] - a0[2]) - (a1[2] - a0[2]) * (a2[1] - a0[1]);
            double ny = (a1[2] - a0[2]) * (a2[0] - a0[0]) - (a1[0] - a0[0]) * (a2[2] - a0[2]);
            double nz = (a1[0] - a0[0]) * (a2[1] - a0[1]) - (a1[1] - a0[1]) * (a2[0] - a0[0]);
            if (nx * a0[0] + ny * a0[1] + nz * a0[2] <= 0) return;
            // clip against the near plane
            double near = 0.05;
            List<double[]> clipped = new ArrayList<double[]>();
            for (int i = 0; i < poly.size(); i++) {
                double[] A = poly.get(i), B = poly.get((i + 1) % poly.size());
                boolean ain = A[2] >= near, bin = B[2] >= near;
                if (ain) clipped.add(A);
                if (ain != bin) {
                    double t = (near - A[2]) / (B[2] - A[2]);
                    double[] Cc = new double[A.length];
                    for (int k = 0; k < A.length; k++) Cc[k] = A[k] + (B[k] - A[k]) * t;
                    clipped.add(Cc);
                }
            }
            for (int i = 1; i + 1 < clipped.size(); i++) raster(clipped.get(0), clipped.get(i), clipped.get(i + 1), pass);
        }

        void raster(double[] A, double[] B, double[] C, int pass) {
            double[][] v = {A, B, C};
            double[] sx = new double[3], syy = new double[3], iz = new double[3];
            for (int i = 0; i < 3; i++) {
                iz[i] = 1 / v[i][2];
                sx[i] = w / 2.0 + v[i][0] * f * iz[i];
                syy[i] = h / 2.0 - v[i][1] * f * iz[i];
            }
            int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2])))), maxX = (int) Math.min(w - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
            int minY = (int) Math.max(0, Math.floor(Math.min(syy[0], Math.min(syy[1], syy[2])))), maxY = (int) Math.min(h - 1, Math.ceil(Math.max(syy[0], Math.max(syy[1], syy[2]))));
            double area = (sx[1] - sx[0]) * (syy[2] - syy[0]) - (sx[2] - sx[0]) * (syy[1] - syy[0]);
            if (Math.abs(area) < 1e-9) return;
            for (int y = minY; y <= maxY; y++)
                for (int x = minX; x <= maxX; x++) {
                    double px0 = x + 0.5, py0 = y + 0.5;
                    double w0 = ((sx[1] - px0) * (syy[2] - py0) - (sx[2] - px0) * (syy[1] - py0)) / area;
                    double w1 = ((sx[2] - px0) * (syy[0] - py0) - (sx[0] - px0) * (syy[2] - py0)) / area;
                    double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    double z = w0 * iz[0] + w1 * iz[1] + w2 * iz[2];
                    float depth = (float) (1 / z);
                    int pi = y * w + x;
                    if (depth > zb[pi] + 1e-4f) continue; // "less or equal", like GL_LEQUAL: overlays drawn on top of the same face
                    double[] at = new double[11];
                    for (int k = 3; k < 11; k++) at[k] = (w0 * v[0][k] * iz[0] + w1 * v[1][k] * iz[1] + w2 * v[2][k] * iz[2]) / z;
                    int tu = Math.min(GameAtlas.SIZE - 1, (int) (at[3] * GameAtlas.SIZE)), tv = Math.min(GameAtlas.SIZE - 1, (int) (at[4] * GameAtlas.SIZE));
                    int t = atlas[tv * GameAtlas.SIZE + tu];
                    int ta = t >>> 24;
                    if (pass != GameMesher.TRANSLUCENT && ta < 128) continue; // alpha test (leaves, plants, glass)
                    GameLight.color((int) Math.round(at[9] / 17), (int) Math.round(at[10] / 17), daylight, lc);
                    double r = ((t >> 16) & 255) * at[5] / 255 * lc[0], g = ((t >> 8) & 255) * at[6] / 255 * lc[1], bb = (t & 255) * at[7] / 255 * lc[2];
                    // fog towards the sky colour at the edge of the view
                    double fog = Math.max(0, Math.min(1, (depth - 40) / 24));
                    int sky = GameLight.skyColor(daylight, 0);
                    r += (((sky >> 16) & 255) - r) * fog;
                    g += (((sky >> 8) & 255) - g) * fog;
                    bb += ((sky & 255) - bb) * fog;
                    if (pass == GameMesher.TRANSLUCENT) {
                        double alpha = ta / 255.0 * at[8] / 255;
                        int old = px[pi];
                        r = r * alpha + ((old >> 16) & 255) * (1 - alpha);
                        g = g * alpha + ((old >> 8) & 255) * (1 - alpha);
                        bb = bb * alpha + (old & 255) * (1 - alpha);
                    } else zb[pi] = depth;
                    px[pi] = 0xFF000000 | (int) Math.min(255, r) << 16 | (int) Math.min(255, g) << 8 | (int) Math.min(255, bb);
                }
        }

        void save(String path) throws Exception {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            javax.imageio.ImageIO.write(img, "png", new File(path));
        }
    }

    /** Game files for a version (blockstates, models, textures), downloaded once from Mojang and cached. */
    static final class Textures {
        @SuppressWarnings("unchecked")
        static Map<String, byte[]> files(String version) throws Exception {
            File cache = new File("desktop/cache/" + version + ".files");
            if (cache.exists()) {
                java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(cache)));
                try {
                    return (Map<String, byte[]>) in.readObject();
                } finally {
                    in.close();
                }
            }
            say("downloading " + version + " block files from Mojang (one time)...");
            RemoteZip z = RemoteZip.forVersion(version);
            List<RemoteZip.Entry> want = new ArrayList<RemoteZip.Entry>();
            for (RemoteZip.Entry e : z.list()) if (GameAssets.wanted(e.name)) want.add(e);
            Map<String, byte[]> files = z.fetch(want, null);
            cache.getParentFile().mkdirs();
            java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(cache)));
            try {
                o.writeObject(new java.util.HashMap<String, byte[]>(files));
            } finally {
                o.close();
            }
            return files;
        }

        static final class Awt implements TexturePack.Images {
            public int[] decode16(byte[] png) {
                return decode(png, 16, true);
            }

            public int[] decode(byte[] png, int size) {
                return decode(png, size, false);
            }

            private static int[] decode(byte[] png, int size, boolean firstFrame) {
                try {
                    BufferedImage b = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                    if (b == null) return null;
                    int w = b.getWidth(), h = firstFrame ? Math.min(b.getHeight(), w) : b.getHeight();
                    int[] out = new int[size * size];
                    for (int y = 0; y < size; y++)
                        for (int x = 0; x < size; x++) out[y * size + x] = b.getRGB(x * w / size, y * h / size);
                    return out;
                } catch (Exception e) {
                    return null;
                }
            }
        }
    }
}
