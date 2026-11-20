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
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Simple 3D view of the blocks around the player: coloured cubes (no textures), only faces that touch air,
 * plus boxes for players/mobs. Renders on demand (a couple of frames per second) and only while visible.
 */
public class World3DView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final int STRIDE = 7; // x y z r g b a
    private static final float SKY_R = 0.53f, SKY_G = 0.70f, SKY_B = 0.92f;

    volatile int radius = 16;
    private float camYaw = 225, camPitch = 40, camDist = 22;
    private final ScaleGestureDetector scaler;
    private float lastX, lastY;
    private boolean dragging;

    // mesh (built off the GL thread)
    private volatile FloatBuffer pendingSolid, pendingWater;
    private volatile int pendingSolidCount, pendingWaterCount;
    private volatile int originX, originY, originZ, pendingOX, pendingOY, pendingOZ;
    private int vboSolid, vboWater, solidCount, waterCount;
    private volatile boolean building;
    private int builtVersion = -1, builtBX = Integer.MIN_VALUE, builtBY, builtBZ, builtRadius;
    private long lastBuild;

    private int program, aPos, aCol, uMvp, uCam, uFog;
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];
    private FloatBuffer entityBuf;

    public World3DView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                camDist = Math.max(4, Math.min(80, camDist / g.getScaleFactor()));
                requestRender();
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaler.isInProgress() && e.getPointerCount() == 1) {
                    camYaw -= (e.getX() - lastX) * 0.35f;
                    camPitch = Math.max(-10, Math.min(89, camPitch + (e.getY() - lastY) * 0.3f));
                    requestRender();
                }
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    /** Called ~2x per second while visible: rebuilds the mesh if needed and redraws. */
    void tick() {
        maybeRebuild();
        requestRender();
    }

    // ================================================================ mesh building

    private void maybeRebuild() {
        if (building || !World.inWorld) return;
        int bx = (int) Math.floor(World.selfX), by = (int) Math.floor(World.selfY), bz = (int) Math.floor(World.selfZ);
        int ver = MapData.version;
        boolean moved = Math.abs(bx - builtBX) > 2 || Math.abs(by - builtBY) > 2 || Math.abs(bz - builtBZ) > 2;
        long now = System.currentTimeMillis();
        if (!moved && radius == builtRadius && (ver == builtVersion || now - lastBuild < 3000)) return;
        building = true;
        builtVersion = ver;
        builtBX = bx;
        builtBY = by;
        builtBZ = bz;
        builtRadius = radius;
        lastBuild = now;
        final int fbx = bx, fby = by, fbz = bz, r = radius;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    build(fbx, fby, fbz, r);
                } catch (Throwable t) {
                    Hub.log("3D view: " + t);
                } finally {
                    building = false;
                    requestRender();
                }
            }
        }, "mesh").start();
    }

    private static final int[][] DIRS = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private static final float[] SHADE = {1.0f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f};
    // 4 corners per face (unit cube), counter-clockwise when seen from outside
    private static final float[][][] FACE = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}}, // up
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, // down
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, // north (-z)
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // south (+z)
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}, // east (+x)
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // west (-x)
    };

    private void build(int ox, int oy, int oz, int r) {
        FloatList solid = new FloatList(1 << 16), water = new FloatList(1 << 12);
        int vr = Math.max(8, r / 2 + 6); // vertical radius
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                for (int y = -vr; y <= vr; y++) {
                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    int col = MapData.blockColor(wx, wy, wz);
                    if (col <= 0) continue;
                    boolean isWater = col == MapData.WATER;
                    for (int f = 0; f < 6; f++) {
                        int n = MapData.blockColor(wx + DIRS[f][0], wy + DIRS[f][1], wz + DIRS[f][2]);
                        boolean show = isWater ? n == 0 : (n == 0 || n == MapData.WATER);
                        if (!show) continue;
                        float s = SHADE[f];
                        float cr = ((col >> 16) & 255) / 255f * s, cg = ((col >> 8) & 255) / 255f * s, cb = (col & 255) / 255f * s;
                        float a = isWater ? 0.6f : 1f;
                        FloatList out = isWater ? water : solid;
                        float[][] q = FACE[f];
                        int[] order = {0, 1, 2, 0, 2, 3};
                        for (int k : order) {
                            out.add(x + q[k][0], y + q[k][1] - (isWater && f == 0 ? 0.1f : 0), z + q[k][2]);
                            out.add(cr, cg, cb, a);
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
                    " float d = distance(aPos, uCam); vF = clamp((d - uFog*0.55)/(uFog*0.45), 0.0, 1.0); }";
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
        builtVersion = -1; // force re-upload after context loss
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
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 60, w / (float) Math.max(1, h), 0.1f, 400f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        FloatBuffer ps = pendingSolid;
        if (ps != null) {
            pendingSolid = null;
            FloatBuffer pw = pendingWater;
            upload(vboSolid, ps);
            upload(vboWater, pw);
            solidCount = pendingSolidCount;
            waterCount = pendingWaterCount;
            originX = pendingOX;
            originY = pendingOY;
            originZ = pendingOZ;
        }
        GLES20.glClearColor(SKY_R, SKY_G, SKY_B, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (solidCount == 0 && !World.inWorld) return;

        // camera orbits the player's eyes
        float tx = (float) (World.selfX - originX), ty = (float) (World.selfY - originY) + 1.6f, tz = (float) (World.selfZ - originZ);
        double yr = Math.toRadians(camYaw), pr = Math.toRadians(camPitch);
        float ex = tx + (float) (Math.cos(pr) * Math.sin(yr) * camDist);
        float ey = ty + (float) (Math.sin(pr) * camDist);
        float ez = tz + (float) (Math.cos(pr) * Math.cos(yr) * camDist);
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform3f(uCam, ex, ey, ez);
        GLES20.glUniform1f(uFog, radius + camDist);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        draw(vboSolid, solidCount);
        drawEntities(tx, ty - 1.6f, tz);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        draw(vboWater, waterCount);
        GLES20.glDepthMask(true);
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

    /** Boxes for the player and nearby entities (rebuilt every frame; there are only a few). */
    private void drawEntities(float px, float py, float pz) {
        FloatList f = new FloatList(4096);
        box(f, px, py, pz, 0.6f, 1.8f, 0x8B5CF6);
        Bot.Config cfg = Hub.config;
        List<World.Dot> dots = World.snapshot(cfg != null ? cfg.friendSet() : null);
        int n = 0;
        for (World.Dot d : dots) {
            if (d.dist > radius + 8 || ++n > 300) continue;
            float x = px + (float) d.dx, y = py + (float) d.dy, z = pz + (float) d.dz;
            int col = RadarView.color(d) & 0xFFFFFF;
            if (d.kind == 'p') box(f, x, y, z, 0.6f, 1.8f, col);
            else if (d.kind == 'h') box(f, x, y, z, 0.6f, 1.7f, col);
            else if (d.kind == 'a') box(f, x, y, z, 0.8f, 0.9f, col);
            else box(f, x, y + 0.1f, z, 0.3f, 0.3f, col);
        }
        FloatBuffer fb = f.toBuffer();
        if (entityBuf == null || entityBuf.capacity() < fb.capacity()) entityBuf = fb;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        fb.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        fb.position(3);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, STRIDE * 4, fb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, f.size / STRIDE);
    }

    private static void box(FloatList out, float cx, float y, float cz, float w, float h, int col) {
        float x0 = cx - w / 2, z0 = cz - w / 2;
        for (int fi = 0; fi < 6; fi++) {
            float s = SHADE[fi];
            float r = ((col >> 16) & 255) / 255f * s, g = ((col >> 8) & 255) / 255f * s, b = (col & 255) / 255f * s;
            float[][] q = FACE[fi];
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int k : order) {
                out.add(x0 + q[k][0] * w, y + q[k][1] * h, z0 + q[k][2] * w);
                out.add(r, g, b, 1f);
            }
        }
    }
}
