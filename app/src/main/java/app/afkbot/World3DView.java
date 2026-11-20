package app.afkbot;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 3D view of the blocks around the player: shaded cubes with ambient occlusion, humanoid players,
 * first-person or orbit camera, tap a block to select it. Only renders while visible.
 */
public class World3DView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final int STRIDE = 7; // x y z r g b a
    private static final float SKY_R = 0.55f, SKY_G = 0.73f, SKY_B = 0.95f;

    interface Listener {
        /** A block was tapped (or null when tapping empty space). */
        void onSelect(int[] block);

        /** Screen positions of name tags (x, y, label) for the overlay, called on the GL thread. */
        void onLabels(List<Object[]> labels);

        /** First-person look changed by dragging. */
        void onLook(float yaw, float pitch);
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

    private int program, aPos, aCol, uMvp, uCam, uFog;
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
        int[] hit = raycast(ox, oy, oz, dx / len, dy / len, dz / len, 96);
        selected = hit;
        Listener l = listener;
        if (l != null) l.onSelect(hit);
        requestRender();
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
                        int f = rayBox(ox, oy, oz, dx, dy, dz, x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]);
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
        FloatList solid = new FloatList(1 << 17), water = new FloatList(1 << 13);
        int vr = Math.max(10, r / 2 + 8);
        // cache: colour for full blocks, -2 for shaped (non-full) blocks, 0 air, -1 unknown, WATER for water
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
                    int col = at(cache, sx, sy, sz, r, vr, x, y, z);
                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    if (col == -2) {
                        int st = states[((x + r + 1) * sz + (z + r + 1)) * sy + (y + vr + 1)];
                        shaped(solid, x, y, z, MapData.colorOf(st), Shapes.outline(st), 0.93f + ((hash(wx, wy, wz) & 255) / 255f) * 0.14f);
                        continue;
                    }
                    if (col <= 0) continue;
                    boolean isWater = col == MapData.WATER;
                    float noise = 0.93f + ((hash(wx, wy, wz) & 255) / 255f) * 0.14f;
                    for (int f = 0; f < 6; f++) {
                        int n = at(cache, sx, sy, sz, r, vr, x + DIRS[f][0], y + DIRS[f][1], z + DIRS[f][2]);
                        boolean show = isWater ? n == 0 || n == -2 : (n == 0 || n == MapData.WATER || n == -2);
                        if (!show) continue;
                        float shade = SHADE[f] * (isWater ? 1f : noise);
                        float cr = ((col >> 16) & 255) / 255f, cg = ((col >> 8) & 255) / 255f, cb = (col & 255) / 255f;
                        float a = isWater ? 0.62f : 1f;
                        FloatList out = isWater ? water : solid;
                        float[][] q = FACE[f];
                        float[] ao = new float[4];
                        for (int k = 0; k < 4; k++) ao[k] = isWater ? 1f : vertexAo(cache, sx, sy, sz, r, vr, x, y, z, f, q[k]);
                        int[] order = ao[0] + ao[2] < ao[1] + ao[3] ? new int[]{1, 2, 3, 1, 3, 0} : new int[]{0, 1, 2, 0, 2, 3};
                        for (int k : order) {
                            float s = shade * ao[k];
                            out.add(x + q[k][0], y + q[k][1] - (isWater && f == 0 ? 0.12f : 0), z + q[k][2]);
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

    /** Draws a non-full block (door, slab, stairs, fence, button...) as its real boxes. */
    private static void shaped(FloatList out, int x, int y, int z, int col, float[][] boxes, float noise) {
        float cr = ((col >> 16) & 255) / 255f, cg = ((col >> 8) & 255) / 255f, cb = (col & 255) / 255f;
        for (float[] b : boxes) {
            for (int f = 0; f < 6; f++) {
                float s = SHADE[f] * noise;
                float[][] q = FACE[f];
                for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
                    out.add(x + b[0] + q[k][0] * (b[3] - b[0]), y + b[1] + q[k][1] * (b[4] - b[1]), z + b[2] + q[k][2] * (b[5] - b[2]));
                    out.add(cr * s, cg * s, cb * s, 1f);
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
                    "attribute vec3 aPos; attribute vec4 aCol; varying vec4 vCol; varying float vF;" +
                    "void main(){ gl_Position = uMvp * vec4(aPos,1.0); vCol = aCol;" +
                    " float d = distance(aPos, uCam); vF = clamp((d - uFog*0.6)/(uFog*0.4), 0.0, 1.0); }";
    private static final String FS =
            "precision mediump float; varying vec4 vCol; varying float vF;" +
                    "void main(){ gl_FragColor = vec4(mix(vCol.rgb, vec3(" + SKY_R + "," + SKY_G + "," + SKY_B + "), vF), vCol.a); }";

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aCol = GLES20.glGetAttribLocation(program, "aCol");
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
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE * 4, 0);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, STRIDE * 4, 12);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void drawClient(FloatList f, int mode) {
        if (f.size == 0) return;
        FloatBuffer fb = f.toBuffer();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aCol);
        fb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        fb.position(3);
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
        for (World.Dot d : dots) {
            if (d.dist > radius + 16 || ++n > 300) continue;
            float x = px + (float) d.dx, y = py + (float) d.dy, z = pz + (float) d.dz;
            int col = RadarView.color(d) & 0xFFFFFF;
            if (d.kind == 'p') {
                humanoid(f, x, y, z, 0, col);
                float[] sp = project(x, y + 2.2f, z);
                if (sp != null) labels.add(new Object[]{sp[0], sp[1], d.label + "  " + Math.round(d.dist) + "m", col});
            } else if (d.kind == 'h') box(f, x, y, z, 0.6f, 1.8f, 0.6f, col);
            else if (d.kind == 'a') box(f, x, y, z, 0.9f, 1.0f, 0.9f, col);
            else box(f, x, y + 0.1f, z, 0.3f, 0.3f, 0.3f, col);
        }
        drawClient(f, GLES20.GL_TRIANGLES);
        return labels;
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
