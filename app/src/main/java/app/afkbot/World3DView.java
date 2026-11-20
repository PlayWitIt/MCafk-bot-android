package app.afkbot;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 3D view of the blocks around the player: shaded cubes with ambient occlusion, humanoid players,
 * first-person or orbit camera, tap a block to select it. Only renders while visible.
 */
public class World3DView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final int STRIDE = 9; // x y z u v r g b a
    private static final float WU = 0.5f / TexturePack.SIZE, WV = 0.5f / TexturePack.SIZE; // centre of the white tile
    private static final float SKY_R = 0.55f, SKY_G = 0.73f, SKY_B = 0.95f;

    interface Listener {
        /** A block was tapped (or null when tapping empty space). */
        void onSelect(int[] block);

        /** Screen positions of name tags (x, y, label) for the overlay, called on the GL thread. */
        void onLabels(List<Object[]> labels);

        /** First-person look changed by dragging. */
        void onLook(float yaw, float pitch);

        /** A player or mob was tapped. */
        void onSelectEntity(World.Dot entity);
    }

    volatile int radius = 16;
    volatile boolean firstPerson = true;
    volatile Listener listener;
    volatile int[] selected; // x, y, z, face
    volatile float fpYaw, fpPitch; // first-person look (synced with the bot)

    private float camYaw = 225, camPitch = 35, camDist = 18;
    private final ScaleGestureDetector scaler;
    private float lastX, lastY, downX, downY;
    private long downT;
    private boolean dragging, moved;

    // mesh (built off the GL thread)
    private volatile FloatBuffer pendingSolid, pendingWater;
    private volatile int pendingSolidCount, pendingWaterCount, pendingOX, pendingOY, pendingOZ;
    private int originX, originY, originZ;
    private int vboSolid, vboWater, solidCount, waterCount;
    private volatile boolean building;
    private int builtVersion = -1, builtBX = Integer.MIN_VALUE, builtBY, builtBZ, builtRadius;
    private long lastBuild;

    private int program, aPos, aUv, aCol, uMvp, uCam, uFog, uTex;
    private int texId = 0;
    private TextureStore.Loaded uploaded;
    private volatile boolean meshTextured;
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16], inv = new float[16];
    private volatile float[] lastMvp;
    private volatile float eyeX, eyeY, eyeZ; // camera position in mesh coordinates
    private int vw = 1, vh = 1;
    private double smX = Double.NaN, smY, smZ;
    private long lastFrame = System.nanoTime();

    public World3DView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                if (!firstPerson) {
                    camDist = Math.max(3, Math.min(80, camDist / g.getScaleFactor()));
                    requestRender();
                }
                return true;
            }
        });
    }

    void setContinuous(boolean on) {
        setRenderMode(on ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                downT = System.currentTimeMillis();
                dragging = true;
                moved = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                moved = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > 20) moved = true;
                if (dragging && !scaler.isInProgress() && e.getPointerCount() == 1) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    if (firstPerson) {
                        fpYaw = (fpYaw + dx * 0.25f) % 360f;
                        fpPitch = Math.max(-89, Math.min(89, fpPitch + dy * 0.25f));
                        Listener l = listener;
                        if (l != null) l.onLook(fpYaw, fpPitch);
                    } else {
                        camYaw -= dx * 0.35f;
                        camPitch = Math.max(-10, Math.min(89, camPitch + dy * 0.3f));
                    }
                    requestRender();
                }
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_UP:
                if (!moved && System.currentTimeMillis() - downT < 400) pick(e.getX(), e.getY());
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    /** Called a few times per second while visible. */
    void tick() {
        maybeRebuild();
        requestRender();
    }

    // ================================================================ picking

    private void pick(float sx, float sy) {
        float[] m = lastMvp;
        if (m == null) return;
        float[] invm = new float[16];
        if (!Matrix.invertM(invm, 0, m, 0)) return;
        float nx = sx / vw * 2 - 1, ny = 1 - sy / vh * 2;
        float[] near = unproject(invm, nx, ny, -1), far = unproject(invm, nx, ny, 1);
        double ox = near[0] + originX, oy = near[1] + originY, oz = near[2] + originZ;
        double dx = far[0] - near[0], dy = far[1] - near[1], dz = far[2] - near[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= len;
        dy /= len;
        dz /= len;
        int[] hit = raycast(ox, oy, oz, dx, dy, dz, 96);
        double blockT = hit == null ? 1e9 : Math.sqrt(sq(hit[0] + 0.5 - ox) + sq(hit[1] + 0.5 - oy) + sq(hit[2] + 0.5 - oz)) - 0.5;
        // players and mobs in front of the block win
        World.Dot best = null;
        double bestT = blockT;
        for (World.Dot d : World.snapshot(null)) {
            if (d.dist > 40) continue;
            double w = d.kind == 'i' || d.kind == 'o' ? 0.5 : d.kind == 'a' ? 0.9 : 0.6, h = d.kind == 'i' || d.kind == 'o' ? 0.5 : d.kind == 'a' ? 1.0 : 1.8;
            double ex = World.selfX + d.dx, ey = World.selfY + d.dy, ez = World.selfZ + d.dz;
            double t = rayBoxT(ox, oy, oz, dx, dy, dz, ex - w / 2 - 0.1, ey, ez - w / 2 - 0.1, ex + w / 2 + 0.1, ey + h + 0.1, ez + w / 2 + 0.1);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = d;
            }
        }
        Listener l = listener;
        if (best != null) {
            selected = null;
            if (l != null) l.onSelectEntity(best);
        } else {
            selected = hit;
            if (l != null) l.onSelect(hit);
        }
        requestRender();
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Distance along the ray to a box, or -1 if missed. */
    private static double rayBoxT(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = 1e9;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    private static float[] unproject(float[] invm, float x, float y, float z) {
        float[] in = {x, y, z, 1}, out = new float[4];
        Matrix.multiplyMV(out, 0, invm, 0, in, 0);
        return new float[]{out[0] / out[3], out[1] / out[3], out[2] / out[3]};
    }

    /** Voxel ray walk (Amanatides & Woo) that also hits small blocks (buttons, levers, doors) by their real shape. */
    static int[] raycast(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tx = (dx > 0 ? (x + 1 - ox) : (ox - x)) * tdx, ty = (dy > 0 ? (y + 1 - oy) : (oy - y)) * tdy, tz = (dz > 0 ? (z + 1 - oz) : (oz - z)) * tdz;
        int face = -1;
        double t = 0;
        while (t < max) {
            int st = MapData.stateAt(x, y, z);
            if (st > 0) {
                int c = MapData.colorOf(st);
                if (c > 0 && c != MapData.WATER) {
                    if (!Shapes.known() || Shapes.isFull(st)) return new int[]{x, y, z, face < 0 ? 1 : face};
                    for (float[] b : Shapes.outline(st)) {
                        int f = rayBox(ox, oy, oz, dx, dy, dz, (double) x + b[0], (double) y + b[1], (double) z + b[2], (double) x + b[3], (double) y + b[4], (double) z + b[5]);
                        if (f >= 0) return new int[]{x, y, z, f};
                    }
                }
            }
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += tdx;
                face = sx > 0 ? 4 : 5;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += tdy;
                face = sy > 0 ? 0 : 1;
            } else {
                z += sz;
                t = tz;
                tz += tdz;
                face = sz > 0 ? 2 : 3;
            }
        }
        return null;
    }

    /** Ray vs box: returns the face hit (0 down 1 up 2 north 3 south 4 west 5 east) or -1. */
    private static int rayBox(double ox, double oy, double oz, double dx, double dy, double dz,
                              double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = -1e9, tmax = 1e9;
        int face = -1;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        int[][] faces = {{4, 5}, {0, 1}, {2, 3}};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            int f = d[a] > 0 ? faces[a][0] : faces[a][1];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tmin) {
                tmin = t1;
                face = f;
            }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmax >= 0 ? face : -1;
    }

    // ================================================================ mesh building

    private void maybeRebuild() {
        if (building || !World.inWorld) return;
        int bx = (int) Math.floor(World.selfX), by = (int) Math.floor(World.selfY), bz = (int) Math.floor(World.selfZ);
        int ver = MapData.version;
        boolean far = Math.abs(bx - builtBX) > 3 || Math.abs(by - builtBY) > 3 || Math.abs(bz - builtBZ) > 3;
        long now = System.currentTimeMillis();
        if (!far && radius == builtRadius && (ver == builtVersion || now - lastBuild < 1500)) return;
        building = true;
        builtVersion = ver;
        builtBX = bx;
        builtBY = by;
        builtBZ = bz;
        builtRadius = radius;
        lastBuild = now;
        final int fbx = bx, fby = by, fbz = bz, r = radius;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    build(fbx, fby, fbz, r);
                } catch (Throwable th) {
                    Hub.log("3D view: " + th);
                } finally {
                    building = false;
                    requestRender();
                }
            }
        }, "mesh");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final int[][] DIRS = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private static final float[] SHADE = {1.0f, 0.55f, 0.82f, 0.82f, 0.68f, 0.68f};
    private static final float[][][] FACE = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
    };
    private static final float[] AO = {1f, 0.82f, 0.68f, 0.55f};

    private static boolean opaque(int c) {
        return c > 0 && c != MapData.WATER; // shaped blocks (-2) and air don't darken corners
    }

    private void build(int ox, int oy, int oz, int r) {
        FloatList solid = new FloatList(1 << 18), water = new FloatList(1 << 13);
        int vr = Math.max(10, r / 2 + 8);
        TextureStore.Loaded tex = TextureStore.current;
        TexturePack.Map3D tm = tex != null ? tex.map : null;
        ProtoDef.VersionData vd = Hub.versionData;
        if (vd == null) tm = null;
        meshTextured = tm != null;
        // cache: colour for full blocks, -2 for shaped (non-full) blocks, 0 air/transparent, -1 unknown, WATER for water
        int sx = 2 * r + 3, sy = 2 * vr + 3, sz = 2 * r + 3;
        int[] cache = new int[sx * sy * sz];
        int[] states = new int[sx * sy * sz];
        boolean shapes = Shapes.known();
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++)
                for (int y = 0; y < sy; y++) {
                    int st = MapData.stateAt(ox + x - r - 1, oy + y - vr - 1, oz + z - r - 1);
                    int k = (x * sz + z) * sy + y;
                    states[k] = st;
                    if (st < 0) {
                        cache[k] = -1;
                        continue;
                    }
                    int c = MapData.colorOf(st);
                    if (c > 0 && c != MapData.WATER && shapes && !Shapes.isFull(st)) c = -2;
                    cache[k] = c;
                }
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                for (int y = -vr; y <= vr; y++) {
                    int k = ((x + r + 1) * sz + (z + r + 1)) * sy + (y + vr + 1);
                    int st = states[k];
                    int col = cache[k];
                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    float noise = tm != null ? 1f : 0.93f + ((hash(wx, wy, wz) & 255) / 255f) * 0.14f;
                    int bi = tm != null && st > 0 ? vd.blockIndex(st) : -1;
                    if (bi >= tm_len(tm)) bi = -1;
                    if (col == 0 && bi >= 0 && tm.cross[bi] >= 0) {
                        cross(solid, x, y, z, tm.cross[bi], tm.tint[bi]);
                        continue;
                    }
                    if (col == -2 && bi >= 0 && tm.chest != null && bi < tm.chest.length && tm.chest[bi] >= 0) {
                        chestModel(solid, x, y, z, tm.chest[bi], Shapes.prop(st, "facing"));
                        continue;
                    }
                    if (col == -2) {
                        shaped(solid, x, y, z, MapData.colorOf(st), Shapes.outline(st), noise, tm, bi);
                        continue;
                    }
                    if (col <= 0) continue;
                    boolean isWater = col == MapData.WATER;
                    for (int f = 0; f < 6; f++) {
                        int n = at(cache, sx, sy, sz, r, vr, x + DIRS[f][0], y + DIRS[f][1], z + DIRS[f][2]);
                        boolean show = isWater ? n == 0 || n == -2 : (n == 0 || n == MapData.WATER || n == -2);
                        if (!show) continue;
                        float shade = SHADE[f] * noise;
                        int tile = bi >= 0 ? tm.faces[bi][f] : -1;
                        int c = tile >= 0 ? ((tm.tintMask[bi] >> f & 1) != 0 && tm.tint[bi] != 0 ? tm.tint[bi] : 0xFFFFFF) : col;
                        if (tile < 0) tile = 0;
                        float cr = ((c >> 16) & 255) / 255f, cg = ((c >> 8) & 255) / 255f, cb = (c & 255) / 255f;
                        float a = isWater ? 0.62f : 1f;
                        FloatList out = isWater ? water : solid;
                        float[][] q = FACE[f];
                        float[] ao = new float[4];
                        for (int kk = 0; kk < 4; kk++) ao[kk] = isWater ? 1f : vertexAo(cache, sx, sy, sz, r, vr, x, y, z, f, q[kk]);
                        int[] order = ao[0] + ao[2] < ao[1] + ao[3] ? new int[]{1, 2, 3, 1, 3, 0} : new int[]{0, 1, 2, 0, 2, 3};
                        for (int kk : order) {
                            float s = shade * ao[kk];
                            out.add(x + q[kk][0], y + q[kk][1] - (isWater && f == 0 ? 0.12f : 0), z + q[kk][2]);
                            uvOf(out, tile, f, q[kk][0], q[kk][1], q[kk][2]);
                            out.add(cr * s, cg * s, cb * s, a);
                        }
                    }
                }
            }
        }
        pendingOX = ox;
        pendingOY = oy;
        pendingOZ = oz;
        pendingSolidCount = solid.size / STRIDE;
        pendingWaterCount = water.size / STRIDE;
        pendingWater = water.toBuffer();
        pendingSolid = solid.toBuffer();
    }

    private static int tm_len(TexturePack.Map3D tm) {
        return tm == null ? 0 : tm.faces.length;
    }

    /** Texture coordinates for a point (lx, ly, lz in 0..1) on a face, inside an atlas tile. */
    private static void uvOf(FloatList out, int tile, int f, float lx, float ly, float lz) {
        float uu, vv;
        switch (f) {
            case 0: uu = lx; vv = lz; break;          // up
            case 1: uu = lx; vv = 1 - lz; break;      // down
            case 2: uu = 1 - lx; vv = 1 - ly; break;  // north
            case 3: uu = lx; vv = 1 - ly; break;      // south
            case 4: uu = 1 - lz; vv = 1 - ly; break;  // east
            default: uu = lz; vv = 1 - ly; break;     // west
        }
        float in = 0.02f; // stay inside the tile so neighbours don't bleed in
        uu = in + uu * (1 - 2 * in);
        vv = in + vv * (1 - 2 * in);
        int tx = (tile % TexturePack.GRID) * TexturePack.TILE, ty = (tile / TexturePack.GRID) * TexturePack.TILE;
        out.uv((tx + uu * TexturePack.TILE) / TexturePack.SIZE, (ty + vv * TexturePack.TILE) / TexturePack.SIZE);
    }

    /** Draws a non-full block (door, slab, stairs, fence, button...) as its real boxes. */
    private static void shaped(FloatList out, int x, int y, int z, int col, float[][] boxes, float noise, TexturePack.Map3D tm, int bi) {
        for (float[] b : boxes) {
            for (int f = 0; f < 6; f++) {
                int tile = bi >= 0 ? tm.faces[bi][f] : -1;
                int c = tile >= 0 ? ((tm.tintMask[bi] >> f & 1) != 0 && tm.tint[bi] != 0 ? tm.tint[bi] : 0xFFFFFF) : col;
                if (tile < 0) tile = 0;
                float cr = ((c >> 16) & 255) / 255f, cg = ((c >> 8) & 255) / 255f, cb = (c & 255) / 255f;
                float s = SHADE[f] * noise;
                float[][] q = FACE[f];
                for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
                    float lx = b[0] + q[k][0] * (b[3] - b[0]), ly = b[1] + q[k][1] * (b[4] - b[1]), lz = b[2] + q[k][2] * (b[5] - b[2]);
                    out.add(x + lx, y + ly, z + lz);
                    uvOf(out, tile, f, lx, ly, lz);
                    out.add(cr * s, cg * s, cb * s, 1f);
                }
            }
        }
    }

    /** Minecraft's chest model (body, lid, latch) with the 64x64 chest texture, turned to face the right way. */
    private static void chestModel(FloatList out, int x, int y, int z, int tile, String facing) {
        int tx = (tile % TexturePack.GRID) * TexturePack.TILE, ty = (tile / TexturePack.GRID) * TexturePack.TILE;
        float yaw = "west".equals(facing) ? 90 : "north".equals(facing) ? 180 : "east".equals(facing) ? 270 : 0;
        // model space: pixels, front facing south (+z); box-UV origins from the chest texture layout
        modelBox(out, x, y, z, yaw, tx, ty, 64, 1, 0, 1, 14, 10, 14, 0, 19);   // body
        modelBox(out, x, y, z, yaw, tx, ty, 64, 1, 9, 1, 14, 5, 14, 0, 0);     // lid
        modelBox(out, x, y, z, yaw, tx, ty, 64, 7, 7, 15, 2, 4, 1, 0, 0);      // latch
    }

    /**
     * A textured box (Minecraft "box UV" layout) in pixel units within a block, rotated around the block
     * centre by yaw. (bx,by,bz) = box corner, (w,h,d) = size, (u,v) = texture origin, texture at atlas px (tx,ty).
     */
    static void modelBox(FloatList out, int x, int y, int z, float yaw, int tx, int ty, int texSize,
                         float bx, float by, float bz, float w, float h, float d, float u, float v) {
        // texture rectangles (u0, v0, width, height) for: up, down, north, south, east, west
        // Minecraft entity models are defined upside down (y points down), so the "down" rectangle is the visible top
        float[][] rect = {
                {u + d + w, v, w, d},         // top (for the chest body this is the dark inside)
                {u + d, v, w, d},             // bottom
                {u + d, v + d, w, h},         // north (back)
                {u + d + w + d, v + d, w, h}, // south (front, with the latch mark)
                {u + d + w, v + d, d, h},     // east
                {u, v + d, d, h},             // west
        };
        double a = Math.toRadians(yaw), cos = Math.cos(a), sin = Math.sin(a);
        float scale = TexturePack.SIZE;
        for (int f = 0; f < 6; f++) {
            float[][] q = FACE[f];
            float s = SHADE[f];
            for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
                float lx = bx + q[k][0] * w, ly = by + q[k][1] * h, lz = bz + q[k][2] * d; // pixels
                float fx = q[k][0], fy = q[k][1], fz = q[k][2];
                float uu, vv;
                switch (f) {
                    case 0: uu = fx; vv = fz; break;
                    case 1: uu = fx; vv = 1 - fz; break;
                    case 2: uu = 1 - fx; vv = 1 - fy; break;
                    case 3: uu = fx; vv = 1 - fy; break;
                    case 4: uu = 1 - fz; vv = 1 - fy; break;
                    default: uu = fz; vv = 1 - fy; break;
                }
                // rotate around the block centre (8, 8) in pixels
                double cx = lx - 8, cz = lz - 8;
                double rx = cx * cos - cz * sin, rz = cx * sin + cz * cos;
                out.add(x + (float) (rx + 8) / 16f, y + ly / 16f, z + (float) (rz + 8) / 16f);
                float[] r = rect[f];
                out.uv((tx + r[0] + uu * r[2]) / scale, (ty + r[1] + vv * r[3]) / scale);
                out.add(s, s, s, 1f);
            }
        }
    }

    /** Plants: two crossed, double-sided textured planes. */
    private static void cross(FloatList out, int x, int y, int z, int tile, int tint) {
        int c = tint != 0 ? tint : 0xFFFFFF;
        float cr = ((c >> 16) & 255) / 255f, cg = ((c >> 8) & 255) / 255f, cb = (c & 255) / 255f;
        float[][][] planes = {{{0.15f, 0, 0.15f}, {0.85f, 0, 0.85f}, {0.85f, 1, 0.85f}, {0.15f, 1, 0.15f}},
                {{0.85f, 0, 0.15f}, {0.15f, 0, 0.85f}, {0.15f, 1, 0.85f}, {0.85f, 1, 0.15f}}};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        int tx = (tile % TexturePack.GRID) * TexturePack.TILE, ty = (tile / TexturePack.GRID) * TexturePack.TILE;
        for (float[][] p : planes) {
            for (int[] order : new int[][]{{0, 1, 2, 0, 2, 3}, {0, 2, 1, 0, 3, 2}}) { // both sides
                for (int k : order) {
                    out.add(x + p[k][0], y + p[k][1], z + p[k][2]);
                    float in = 0.02f;
                    out.uv((tx + (in + uv[k][0] * (1 - 2 * in)) * TexturePack.TILE) / TexturePack.SIZE,
                            (ty + (in + uv[k][1] * (1 - 2 * in)) * TexturePack.TILE) / TexturePack.SIZE);
                    out.add(cr * 0.9f, cg * 0.9f, cb * 0.9f, 1f);
                }
            }
        }
    }

    private static int at(int[] cache, int sx, int sy, int sz, int r, int vr, int x, int y, int z) {
        int ix = x + r + 1, iy = y + vr + 1, iz = z + r + 1;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) return -1;
        return cache[(ix * sz + iz) * sy + iy];
    }

    /** Classic voxel AO: darken a corner by how many of its 3 neighbours (in front of the face) are solid. */
    private static float vertexAo(int[] cache, int sx, int sy, int sz, int r, int vr, int x, int y, int z, int f, float[] corner) {
        int nx = DIRS[f][0], ny = DIRS[f][1], nz = DIRS[f][2];
        // the two in-plane directions for this corner
        int ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0;
        if (nx != 0) {
            ay = corner[1] > 0.5f ? 1 : -1;
            bz = corner[2] > 0.5f ? 1 : -1;
        } else if (ny != 0) {
            ax = corner[0] > 0.5f ? 1 : -1;
            bz = corner[2] > 0.5f ? 1 : -1;
        } else {
            ax = corner[0] > 0.5f ? 1 : -1;
            by = corner[1] > 0.5f ? 1 : -1;
        }
        int px = x + nx, py = y + ny, pz = z + nz;
        boolean s1 = opaque(at(cache, sx, sy, sz, r, vr, px + ax, py + ay, pz + az));
        boolean s2 = opaque(at(cache, sx, sy, sz, r, vr, px + bx, py + by, pz + bz));
        boolean cc = opaque(at(cache, sx, sy, sz, r, vr, px + ax + bx, py + ay + by, pz + az + bz));
        int occ = s1 && s2 ? 3 : (s1 ? 1 : 0) + (s2 ? 1 : 0) + (cc ? 1 : 0);
        return AO[occ];
    }

    private static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }

    static final class FloatList {
        float[] a;
        int size;

        FloatList(int cap) {
            a = new float[cap];
        }

        void add(float x, float y, float z) {
            ensure(3);
            a[size++] = x;
            a[size++] = y;
            a[size++] = z;
        }

        void uv(float u, float v) {
            ensure(2);
            a[size++] = u;
            a[size++] = v;
        }

        void add(float r, float g, float b, float al) {
            ensure(4);
            a[size++] = r;
            a[size++] = g;
            a[size++] = b;
            a[size++] = al;
        }

        private void ensure(int n) {
            if (size + n > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, size + n));
        }

        FloatBuffer toBuffer() {
            FloatBuffer fb = ByteBuffer.allocateDirect(Math.max(4, size * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(a, 0, size);
            fb.position(0);
            return fb;
        }
    }

    // ================================================================ GL

    private static final String VS =
            "uniform mat4 uMvp; uniform vec3 uCam; uniform float uFog;" +
                    "attribute vec3 aPos; attribute vec2 aUv; attribute vec4 aCol; varying vec4 vCol; varying vec2 vUv; varying float vF;" +
                    "void main(){ gl_Position = uMvp * vec4(aPos,1.0); vCol = aCol; vUv = aUv;" +
                    " float d = distance(aPos, uCam); vF = clamp((d - uFog*0.6)/(uFog*0.4), 0.0, 1.0); }";
    private static final String FS =
            "precision mediump float; uniform sampler2D uTex; varying vec4 vCol; varying vec2 vUv; varying float vF;" +
                    "void main(){ vec4 t = texture2D(uTex, vUv); if (t.a < 0.4) discard;" +
                    " gl_FragColor = vec4(mix(t.rgb * vCol.rgb, vec3(" + SKY_R + "," + SKY_G + "," + SKY_B + "), vF), vCol.a < 0.99 ? vCol.a * max(t.a, 0.75) : 1.0); }";

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aCol = GLES20.glGetAttribLocation(program, "aCol");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        // start with a plain white texture; the block atlas replaces it once downloaded
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        java.nio.ByteBuffer white = java.nio.ByteBuffer.allocateDirect(4);
        white.put(new byte[]{-1, -1, -1, -1}).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, white);
        uploaded = null;
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uCam = GLES20.glGetUniformLocation(program, "uCam");
        uFog = GLES20.glGetUniformLocation(program, "uFog");
        int[] b = new int[2];
        GLES20.glGenBuffers(2, b, 0);
        vboSolid = b[0];
        vboWater = b[1];
        solidCount = waterCount = 0;
        builtVersion = -1;
        builtBX = Integer.MIN_VALUE;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        vw = Math.max(1, w);
        vh = Math.max(1, h);
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        FloatBuffer ps = pendingSolid;
        if (ps != null) {
            pendingSolid = null;
            upload(vboSolid, ps);
            upload(vboWater, pendingWater);
            solidCount = pendingSolidCount;
            waterCount = pendingWaterCount;
            originX = pendingOX;
            originY = pendingOY;
            originZ = pendingOZ;
        }
        TextureStore.Loaded L = TextureStore.current;
        if (L != uploaded) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            if (L != null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, L.atlas, 0);
            uploaded = L;
            builtVersion = -1; // rebuild with texture coordinates
            builtBX = Integer.MIN_VALUE;
        }
        GLES20.glClearColor(SKY_R, SKY_G, SKY_B, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!World.inWorld) return;

        // smooth the camera between the 20-per-second position updates (snap on teleports)
        long nowN = System.nanoTime();
        double dt = Math.min(0.1, (nowN - lastFrame) / 1e9);
        lastFrame = nowN;
        double tx0 = World.selfX, ty0 = World.selfY, tz0 = World.selfZ;
        if (Double.isNaN(smX) || Math.abs(tx0 - smX) + Math.abs(ty0 - smY) + Math.abs(tz0 - smZ) > 4) {
            smX = tx0;
            smY = ty0;
            smZ = tz0;
        } else {
            double k = Math.min(1, dt * 18);
            smX += (tx0 - smX) * k;
            smY += (ty0 - smY) * k;
            smZ += (tz0 - smZ) * k;
        }
        float px = (float) (smX - originX), py = (float) (smY - originY), pz = (float) (smZ - originZ);
        float ex, ey, ez, tx, ty, tz;
        if (firstPerson) {
            Matrix.perspectiveM(proj, 0, 75, vw / (float) vh, 0.05f, 400f);
            ex = px;
            ey = py + 1.62f;
            ez = pz;
            double yr = Math.toRadians(fpYaw), pr = Math.toRadians(fpPitch);
            // Minecraft: yaw 0 = +Z (south), positive pitch looks down
            tx = ex - (float) (Math.sin(yr) * Math.cos(pr));
            ty = ey - (float) Math.sin(pr);
            tz = ez + (float) (Math.cos(yr) * Math.cos(pr));
        } else {
            Matrix.perspectiveM(proj, 0, 60, vw / (float) vh, 0.1f, 400f);
            tx = px;
            ty = py + 1.6f;
            tz = pz;
            double yr = Math.toRadians(camYaw), pr = Math.toRadians(camPitch);
            ex = tx + (float) (Math.cos(pr) * Math.sin(yr) * camDist);
            ey = ty + (float) (Math.sin(pr) * camDist);
            ez = tz + (float) (Math.cos(pr) * Math.cos(yr) * camDist);
        }
        eyeX = ex;
        eyeY = ey;
        eyeZ = ez;
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        lastMvp = mvp.clone();

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform3f(uCam, ex, ey, ez);
        GLES20.glUniform1f(uFog, radius + (firstPerson ? 0 : camDist));

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        draw(vboSolid, solidCount);
        List<Object[]> labels = drawEntities(px, py, pz);
        drawSelection();

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        draw(vboWater, waterCount);
        GLES20.glDepthMask(true);

        Listener l = listener;
        if (l != null) l.onLabels(labels);
    }

    private void upload(int vbo, FloatBuffer fb) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void draw(int vbo, int count) {
        if (count <= 0) return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE * 4, 0);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE * 4, 12);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, STRIDE * 4, 20);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void drawClient(FloatList f, int mode) {
        if (f.size == 0) return;
        FloatBuffer fb = f.toBuffer();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        fb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        fb.position(3);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        fb.position(5);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        GLES20.glDrawArrays(mode, 0, f.size / STRIDE);
    }

    /** Players as simple figures, mobs as boxes. Returns name tag positions for the overlay. */
    private List<Object[]> drawEntities(float px, float py, float pz) {
        FloatList f = new FloatList(8192);
        List<Object[]> labels = new ArrayList<Object[]>();
        if (!firstPerson) humanoid(f, px, py, pz, World.selfYaw, 0x8B5CF6);
        Bot.Config cfg = Hub.config;
        List<World.Dot> dots = World.snapshot(cfg != null ? cfg.friendSet() : null);
        int n = 0;
        Map<EntityModels.Model, FloatList> byModel = new HashMap<EntityModels.Model, FloatList>();
        for (World.Dot d : dots) {
            if (d.dist > radius + 16 || ++n > 300) continue;
            float x = px + (float) d.dx, y = py + (float) d.dy, z = pz + (float) d.dz;
            int col = RadarView.color(d) & 0xFFFFFF;
            EntityModels.Model model = n <= 80 ? EntityModels.get(d.type) : null;
            if (model != null) {
                FloatList ml = byModel.get(model);
                if (ml == null) byModel.put(model, ml = new FloatList(4096));
                placeModel(ml, model.mesh, x, y, z, d.yaw);
                if (d.kind == 'p') {
                    float[] sp = project(x, y + 2.2f, z);
                    if (sp != null) labels.add(new Object[]{sp[0], sp[1], d.label + "  " + Math.round(d.dist) + "m", col});
                }
                continue;
            }
            if (d.kind == 'p') {
                humanoid(f, x, y, z, 0, col);
                float[] sp = project(x, y + 2.2f, z);
                if (sp != null) labels.add(new Object[]{sp[0], sp[1], d.label + "  " + Math.round(d.dist) + "m", col});
            } else if (d.kind == 'h') box(f, x, y, z, 0.6f, 1.8f, 0.6f, col);
            else if (d.kind == 'a') box(f, x, y, z, 0.9f, 1.0f, 0.9f, col);
            else box(f, x, y + 0.1f, z, 0.3f, 0.3f, 0.3f, col);
        }
        drawClient(f, GLES20.GL_TRIANGLES);
        // real models: one draw per mob type with its own texture
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        for (Map.Entry<EntityModels.Model, FloatList> en : byModel.entrySet()) {
            EntityModels.Model m = en.getKey();
            if (m.glTex == 0) {
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                m.glTex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m.glTex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, m.texture, 0);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m.glTex);
            drawClient(en.getValue(), GLES20.GL_TRIANGLES);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        return labels;
    }

    /** Adds a model's triangles at (x, y, z), turned to face yaw (Minecraft degrees). */
    private static void placeModel(FloatList out, float[] mesh, float x, float y, float z, float yaw) {
        double a = Math.toRadians(yaw + 180), cos = Math.cos(a), sin = Math.sin(a);
        for (int i = 0; i + Geo.STRIDE <= mesh.length; i += Geo.STRIDE) {
            float mx = mesh[i], my = mesh[i + 1], mz = mesh[i + 2];
            out.add(x + (float) (mx * cos - mz * sin), y + my, z + (float) (mx * sin + mz * cos));
            out.uv(mesh[i + 3], mesh[i + 4]);
            float s = mesh[i + 5];
            out.add(s, s, s, 1f);
        }
    }

    private float[] project(float x, float y, float z) {
        float[] in = {x, y, z, 1}, out = new float[4];
        Matrix.multiplyMV(out, 0, mvp, 0, in, 0);
        if (out[3] <= 0.01f) return null;
        float nx = out[0] / out[3], ny = out[1] / out[3];
        if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) return null;
        return new float[]{(nx + 1) / 2 * vw, (1 - ny) / 2 * vh};
    }

    private static void humanoid(FloatList f, float x, float y, float z, float yaw, int shirt) {
        int skin = 0xD6A27A, pants = 0x2F3D7A;
        box(f, x, y, z, 0.5f, 0.75f, 0.3f, pants); // legs
        box(f, x, y + 0.75f, z, 0.55f, 0.75f, 0.3f, shirt); // body
        box(f, x - 0.38f, y + 0.8f, z, 0.2f, 0.7f, 0.25f, shirt); // arms
        box(f, x + 0.38f, y + 0.8f, z, 0.2f, 0.7f, 0.25f, shirt);
        box(f, x, y + 1.5f, z, 0.5f, 0.5f, 0.5f, skin); // head
    }

    private static void box(FloatList out, float cx, float y, float cz, float w, float h, float d, int col) {
        float x0 = cx - w / 2, z0 = cz - d / 2;
        for (int fi = 0; fi < 6; fi++) {
            float s = SHADE[fi];
            float r = ((col >> 16) & 255) / 255f * s, g = ((col >> 8) & 255) / 255f * s, b = (col & 255) / 255f * s;
            float[][] q = FACE[fi];
            for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
                out.add(x0 + q[k][0] * w, y + q[k][1] * h, z0 + q[k][2] * d);
                out.uv(WU, WV);
                out.add(r, g, b, 1f);
            }
        }
    }

    private void drawSelection() {
        int[] s = selected;
        if (s == null) return;
        float x = s[0] - originX - 0.005f, y = s[1] - originY - 0.005f, z = s[2] - originZ - 0.005f, e = 1.01f;
        FloatList f = new FloatList(256);
        float[][] c = {{0, 0, 0}, {e, 0, 0}, {e, 0, e}, {0, 0, e}, {0, e, 0}, {e, e, 0}, {e, e, e}, {0, e, e}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : edges)
            for (int k : ed) {
                f.add(x + c[k][0], y + c[k][1], z + c[k][2]);
                f.uv(WU, WV);
                f.add(1f, 1f, 1f, 1f);
            }
        GLES20.glLineWidth(3f);
        drawClient(f, GLES20.GL_LINES);
    }

    /** Distance from the player's eyes to the centre of a block. */
    static double reach(int[] b) {
        return World.reach(b);
    }
}
