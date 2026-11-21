package app.afkbot;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 2.0 renderer for the Play screen. Chunk sections are meshed on background threads (GameMesher, real
 * block models) and drawn camera-relative (so Constantiam's huge coordinates don't jitter), in three passes: opaque,
 * cutout (alpha test) and translucent (blended, back to front). Lighting is a light map in the shader, so day/night
 * needs no re-meshing. Also draws the crosshair target outline, block cracks and simple entity boxes.
 */
final class GameRenderer implements GLSurfaceView.Renderer, MapData.Changes {
    volatile GameAssetStore.Loaded assets;
    volatile int renderDistance = 6; // chunks
    volatile float fov = 70;
    volatile float yaw, pitch; // look direction (degrees, Minecraft convention)
    volatile boolean sneaking;

    // what the crosshair points at (updated every frame on the GL thread)
    volatile int[] targetBlock; // x, y, z, face
    volatile World.Dot targetEntity;
    volatile float reach = 4.5f;

    // ---- sections ----
    private static final class Section {
        final int sx, sy, sz;
        final int[] vbo = new int[3];
        final int[] quads = new int[3];

        Section(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }
    }

    private final Map<Long, Section> sections = new ConcurrentHashMap<Long, Section>();
    private final Map<Long, Boolean> dirty = new ConcurrentHashMap<Long, Boolean>();
    private final ConcurrentLinkedQueue<GameMesher.Mesh> ready = new ConcurrentLinkedQueue<GameMesher.Mesh>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private ExecutorService workers;
    private final ThreadLocal<GameMesher> mesher = new ThreadLocal<GameMesher>();
    private int epoch = -1;

    static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 34) | ((long) (sz & 0x3FFFFF) << 12) | (sy & 0xFFF);
    }

    // ---- MapData.Changes (any thread) ----

    @Override
    public void chunk(int cx, int cz) {
        markColumn(cx, cz, true);
        // neighbours only need new edge faces if they were already meshed
        markColumn(cx + 1, cz, false);
        markColumn(cx - 1, cz, false);
        markColumn(cx, cz + 1, false);
        markColumn(cx, cz - 1, false);
    }

    @Override
    public void block(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        dirty.put(key(sx, sy, sz), Boolean.TRUE);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) markIfMeshed(sx - 1, sy, sz);
        if (lx == 15) markIfMeshed(sx + 1, sy, sz);
        if (ly == 0) markIfMeshed(sx, sy - 1, sz);
        if (ly == 15) markIfMeshed(sx, sy + 1, sz);
        if (lz == 0) markIfMeshed(sx, sy, sz - 1);
        if (lz == 15) markIfMeshed(sx, sy, sz + 1);
    }

    @Override
    public void light(int cx, int cz) {
        markColumn(cx, cz, false);
    }

    private void markIfMeshed(int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        if (sections.containsKey(k)) dirty.put(k, Boolean.TRUE);
    }

    private void markColumn(int cx, int cz, boolean all) {
        int min = MapData.minY >> 4;
        for (int sy = min; sy < min + 24; sy++) {
            long k = key(cx, sy, cz);
            if (all || sections.containsKey(k)) dirty.put(k, Boolean.TRUE);
        }
    }

    /** Queue every loaded chunk around the player (when the screen opens or the world changes). */
    void markAll() {
        int pcx = (int) Math.floor(World.selfX) >> 4, pcz = (int) Math.floor(World.selfZ) >> 4, r = renderDistance + 1;
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++)
                if (MapData.loaded(cx << 4, cz << 4)) markColumn(cx, cz, true);
    }

    void stop() {
        ExecutorService w = workers;
        if (w != null) w.shutdownNow();
        workers = null;
    }

    // ---- GL ----
    private int prog, aPos, aUv, aCol, aLight, uMvp, uOff, uDay, uFog, uFogRange, uAlphaRef, uTex;
    private int lineProg, lPos, lMvp, lColor, lOff;
    private int texId, indexBuf;
    private GameAssetStore.Loaded uploaded;
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];
    private final float[][] planes = new float[6][4];
    private int vw = 1, vh = 1;
    private double camX = Double.NaN, camY, camZ, fromX, fromY, fromZ, toX = Double.NaN, toY, toZ;
    private long moveAt;

    private static final String VS =
            "attribute vec3 aPos; attribute vec2 aUv; attribute vec4 aCol; attribute vec2 aLight;\n" +
            "uniform mat4 uMvp; uniform vec3 uOff; uniform float uDay; uniform vec2 uFogRange;\n" +
            "varying vec2 vUv; varying vec4 vCol; varying vec3 vLight; varying float vFog;\n" +
            "float curve(float l) { float b = l / (4.0 - 3.0 * l); float i = 1.0 - b; return mix(b, 1.0 - i * i * i * i, 0.5); }\n" +
            "void main() {\n" +
            "  vec3 p = aPos / 512.0 + uOff;\n" +
            "  gl_Position = uMvp * vec4(p, 1.0);\n" +
            "  vUv = aUv; vCol = aCol;\n" +
            "  float sky = curve(aLight.x * (0.2 + 0.8 * uDay)); float blk = curve(aLight.y);\n" +
            "  vec3 c = vec3(max(sky * (0.85 + 0.15 * uDay), blk), max(sky * (0.88 + 0.12 * uDay), blk * 0.9), max(sky, blk * 0.75));\n" +
            "  vLight = 0.04 + c * 0.96;\n" +
            "  vFog = clamp((length(p) - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);\n" +
            "}";
    private static final String FS =
            "precision mediump float;\n" +
            "uniform sampler2D uTex; uniform vec3 uFog; uniform float uAlphaRef;\n" +
            "varying vec2 vUv; varying vec4 vCol; varying vec3 vLight; varying float vFog;\n" +
            "void main() {\n" +
            "  vec4 t = texture2D(uTex, vUv);\n" +
            "  if (t.a < uAlphaRef) discard;\n" +
            "  vec3 c = t.rgb * vCol.rgb * vLight;\n" +
            "  gl_FragColor = vec4(mix(c, uFog, vFog), t.a * vCol.a);\n" +
            "}";
    private static final String LVS = "attribute vec3 aPos; uniform mat4 uMvp; uniform vec3 uOff; void main() { gl_Position = uMvp * vec4(aPos + uOff, 1.0); }";
    private static final String LFS = "precision mediump float; uniform vec4 uColor; void main() { gl_FragColor = uColor; }";

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prog = program(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aUv = GLES20.glGetAttribLocation(prog, "aUv");
        aCol = GLES20.glGetAttribLocation(prog, "aCol");
        aLight = GLES20.glGetAttribLocation(prog, "aLight");
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
        uOff = GLES20.glGetUniformLocation(prog, "uOff");
        uDay = GLES20.glGetUniformLocation(prog, "uDay");
        uFog = GLES20.glGetUniformLocation(prog, "uFog");
        uFogRange = GLES20.glGetUniformLocation(prog, "uFogRange");
        uAlphaRef = GLES20.glGetUniformLocation(prog, "uAlphaRef");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        lineProg = program(LVS, LFS);
        lPos = GLES20.glGetAttribLocation(lineProg, "aPos");
        lMvp = GLES20.glGetUniformLocation(lineProg, "uMvp");
        lOff = GLES20.glGetUniformLocation(lineProg, "uOff");
        lColor = GLES20.glGetUniformLocation(lineProg, "uColor");
        // one index buffer for all quads: 0 1 2 0 2 3, 4 5 6 4 6 7, ... (16384 quads = 65536 vertices)
        ShortBuffer ib = ByteBuffer.allocateDirect(16384 * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int q = 0; q < 16384; q++) {
            short b = (short) (q * 4);
            ib.put(b).put((short) (b + 1)).put((short) (b + 2)).put(b).put((short) (b + 2)).put((short) (b + 3));
        }
        ib.position(0);
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        indexBuf = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, 16384 * 6 * 2, ib, GLES20.GL_STATIC_DRAW);
        // a new GL context lost every buffer and texture: rebuild everything
        uploaded = null;
        texId = 0;
        sections.clear();
        dirty.clear();
        ready.clear();
        markAll();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        vw = Math.max(1, w);
        vh = Math.max(1, h);
        GLES20.glViewport(0, 0, vw, vh);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GameAssetStore.Loaded a = assets;
        float day = GameState.daylight();
        int sky = GameLight.skyColor(day, GameState.rain);
        float sr = ((sky >> 16) & 255) / 255f, sg = ((sky >> 8) & 255) / 255f, sb = (sky & 255) / 255f;
        GLES20.glClearColor(sr, sg, sb, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (a == null || !World.inWorld) return;
        if (uploaded != a) upload(a);
        if (MapData.epoch != epoch) { // new world: drop everything
            epoch = MapData.epoch;
            for (Section s : sections.values()) delete(s);
            sections.clear();
            dirty.clear();
            markAll();
        }
        updateCamera();
        scheduleMeshing(a);
        for (int i = 0; i < 8; i++) {
            GameMesher.Mesh m = ready.poll();
            if (m == null) break;
            install(m);
        }
        dropFarSections();

        float aspect = (float) vw / vh;
        float far = (renderDistance + 1) * 16f;
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, far + 32);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(Math.max(-89.9f, Math.min(89.9f, pitch)));
        float fx = (float) (-Math.sin(yr) * Math.cos(pr)), fy = (float) -Math.sin(pr), fz = (float) (Math.cos(yr) * Math.cos(pr));
        Matrix.setLookAtM(view, 0, 0, 0, 0, fx, fy, fz, 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        extractPlanes(mvp);

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform1f(uDay, day);
        GLES20.glUniform3f(uFog, sr, sg, sb);
        GLES20.glUniform2f(uFogRange, far * 0.72f, far);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glEnableVertexAttribArray(aLight);

        List<Section> visible = new ArrayList<Section>();
        for (Section s : sections.values()) if (inFrustum(s)) visible.add(s);
        // opaque + cutout front to back (saves fill rate), translucent back to front
        Collections.sort(visible, new Comparator<Section>() {
            public int compare(Section p, Section q) {
                return Double.compare(dist2(p), dist2(q));
            }
        });
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUniform1f(uAlphaRef, -1f);
        for (Section s : visible) draw(s, GameMesher.OPAQUE);
        GLES20.glUniform1f(uAlphaRef, 0.5f);
        GLES20.glDisable(GLES20.GL_CULL_FACE); // plants are seen from both sides
        for (Section s : visible) draw(s, GameMesher.CUTOUT);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uAlphaRef, 0.01f);
        for (int i = visible.size() - 1; i >= 0; i--) draw(visible.get(i), GameMesher.TRANSLUCENT);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glDisableVertexAttribArray(aLight);

        pickTarget(fx, fy, fz);
        drawEntities();
        drawCracks(a);
        drawOutline();
    }

    private double dist2(Section s) {
        double dx = s.sx * 16 + 8 - camX, dy = s.sy * 16 + 8 - camY, dz = s.sz * 16 + 8 - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void draw(Section s, int pass) {
        int quads = s.quads[pass];
        if (quads == 0) return;
        GLES20.glUniform3f(uOff, (float) (s.sx * 16 - camX), (float) (s.sy * 16 - camY), (float) (s.sz * 16 - camZ));
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, s.vbo[pass]);
        for (int first = 0; first < quads; first += 16384) {
            int base = first * 4 * GameMesher.BYTES;
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_SHORT, false, GameMesher.BYTES, base);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_UNSIGNED_SHORT, true, GameMesher.BYTES, base + 6);
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, base + 10);
            GLES20.glVertexAttribPointer(aLight, 2, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, base + 14);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, Math.min(16384, quads - first) * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        }
    }

    // ---- camera: follow the player smoothly between the bot's 20 Hz ticks ----

    private void updateCamera() {
        double tx = World.selfX, ty = World.selfY + (sneaking ? 1.27 : 1.62), tz = World.selfZ;
        long now = System.nanoTime();
        if (Double.isNaN(toX) || Math.abs(tx - toX) + Math.abs(ty - toY) + Math.abs(tz - toZ) > 8 || Double.isNaN(camX)) {
            camX = fromX = toX = tx; // first frame or a teleport: jump straight there
            camY = fromY = toY = ty;
            camZ = fromZ = toZ = tz;
            moveAt = now;
            return;
        }
        if (tx != toX || ty != toY || tz != toZ) {
            fromX = camX;
            fromY = camY;
            fromZ = camZ;
            toX = tx;
            toY = ty;
            toZ = tz;
            moveAt = now;
        }
        double f = Math.min(1, (now - moveAt) / 50e6);
        camX = fromX + (toX - fromX) * f;
        camY = fromY + (toY - fromY) * f;
        camZ = fromZ + (toZ - fromZ) * f;
    }

    double[] camera() {
        return new double[]{camX, camY, camZ};
    }

    // ---- meshing ----

    private void scheduleMeshing(final GameAssetStore.Loaded a) {
        if (workers == null) workers = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mesher");
                t.setPriority(Thread.MIN_PRIORITY + 1);
                t.setDaemon(true);
                return t;
            }
        });
        int budget = 6 - inFlight.get();
        if (budget <= 0 || dirty.isEmpty()) return;
        int pcx = (int) Math.floor(camX) >> 4, pcy = (int) Math.floor(camY) >> 4, pcz = (int) Math.floor(camZ) >> 4, r = renderDistance;
        // nearest dirty sections first
        List<long[]> cand = new ArrayList<long[]>();
        Iterator<Long> it = dirty.keySet().iterator();
        while (it.hasNext()) {
            long k = it.next();
            int sx = (int) (k << 8 >> 42), sz = (int) (k << 30 >> 42), sy = (int) (k << 52 >> 52);
            int dx = sx - pcx, dz = sz - pcz;
            if (Math.abs(dx) > r || Math.abs(dz) > r) {
                it.remove();
                continue;
            }
            int dy = sy - pcy;
            cand.add(new long[]{dx * dx + dz * dz + dy * dy / 4, k, sx, sy, sz});
        }
        Collections.sort(cand, new Comparator<long[]>() {
            public int compare(long[] p, long[] q) {
                return Long.compare(p[0], q[0]);
            }
        });
        for (int i = 0; i < cand.size() && budget > 0; i++, budget--) {
            final long[] c = cand.get(i);
            dirty.remove(c[1]);
            inFlight.incrementAndGet();
            workers.execute(new Runnable() {
                public void run() {
                    try {
                        GameMesher m = mesher.get();
                        if (m == null) {
                            m = new GameMesher(Hub.versionData, a.atlas, a.models);
                            mesher.set(m);
                        }
                        GameMesher.Mesh mesh = m.build((int) c[2], (int) c[3], (int) c[4]);
                        if (mesh != null) ready.add(mesh);
                    } catch (Throwable ignored) {
                        // a section failed to mesh (e.g. chunk unloaded mid-way): it will be retried on the next change
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            });
        }
    }

    private void install(GameMesher.Mesh m) {
        long k = key(m.sx, m.sy, m.sz);
        Section old = sections.get(k);
        if (m.empty()) {
            if (old != null) {
                delete(old);
                sections.remove(k);
            }
            return;
        }
        Section s = old != null ? old : new Section(m.sx, m.sy, m.sz);
        for (int p = 0; p < 3; p++) {
            if (m.quads[p] == 0) {
                if (s.vbo[p] != 0) GLES20.glDeleteBuffers(1, new int[]{s.vbo[p]}, 0);
                s.vbo[p] = 0;
                s.quads[p] = 0;
                continue;
            }
            if (s.vbo[p] == 0) {
                int[] id = new int[1];
                GLES20.glGenBuffers(1, id, 0);
                s.vbo[p] = id[0];
            }
            ByteBuffer bb = ByteBuffer.allocateDirect(m.data[p].length).order(ByteOrder.nativeOrder());
            bb.put(m.data[p]).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, s.vbo[p]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.data[p].length, bb, GLES20.GL_STATIC_DRAW);
            s.quads[p] = m.quads[p];
        }
        sections.put(k, s);
    }

    private void delete(Section s) {
        for (int p = 0; p < 3; p++) {
            if (s.vbo[p] != 0) GLES20.glDeleteBuffers(1, new int[]{s.vbo[p]}, 0);
            s.vbo[p] = 0;
            s.quads[p] = 0;
        }
    }

    private long lastDrop;

    private void dropFarSections() {
        long now = System.currentTimeMillis();
        if (now - lastDrop < 1000) return;
        lastDrop = now;
        int pcx = (int) Math.floor(camX) >> 4, pcz = (int) Math.floor(camZ) >> 4, r = renderDistance + 1;
        Iterator<Map.Entry<Long, Section>> it = sections.entrySet().iterator();
        while (it.hasNext()) {
            Section s = it.next().getValue();
            if (Math.abs(s.sx - pcx) > r || Math.abs(s.sz - pcz) > r || !MapData.loaded(s.sx << 4, s.sz << 4)) {
                delete(s);
                it.remove();
            }
        }
    }

    private void upload(GameAssetStore.Loaded a) {
        int[] ids = new int[1];
        if (texId != 0) GLES20.glDeleteTextures(1, new int[]{texId}, 0);
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, a.atlasBitmap, 0);
        uploaded = a;
        markAll();
    }

    // ---- frustum culling ----

    private void extractPlanes(float[] m) {
        // rows of the (column-major) matrix
        for (int i = 0; i < 6; i++) {
            int row = i / 2;
            float sign = (i % 2 == 0) ? 1 : -1;
            planes[i][0] = m[3] + sign * m[row];
            planes[i][1] = m[7] + sign * m[4 + row];
            planes[i][2] = m[11] + sign * m[8 + row];
            planes[i][3] = m[15] + sign * m[12 + row];
        }
    }

    private boolean inFrustum(Section s) {
        float x0 = (float) (s.sx * 16 - camX), y0 = (float) (s.sy * 16 - camY), z0 = (float) (s.sz * 16 - camZ);
        float x1 = x0 + 16, y1 = y0 + 16, z1 = z0 + 16;
        for (float[] p : planes) {
            float x = p[0] >= 0 ? x1 : x0, y = p[1] >= 0 ? y1 : y0, z = p[2] >= 0 ? z1 : z0;
            if (p[0] * x + p[1] * y + p[2] * z + p[3] < 0) return false;
        }
        return true;
    }

    // ---- what the crosshair points at ----

    private void pickTarget(float dx, float dy, float dz) {
        int[] hit = World3DView.raycast(camX, camY, camZ, dx, dy, dz, reach + 1);
        double blockT = Double.MAX_VALUE;
        if (hit != null) {
            double cx = hit[0] + 0.5 - camX, cy = hit[1] + 0.5 - camY, cz = hit[2] + 0.5 - camZ;
            blockT = cx * dx + cy * dy + cz * dz - 0.5; // close enough: distance to the block along the ray
            if (Math.sqrt(cx * cx + cy * cy + cz * cz) > reach + 0.9) {
                hit = null;
                blockT = Double.MAX_VALUE;
            }
        }
        World.Dot best = null;
        double bestT = Math.min(blockT, reach);
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        for (World.Dot d : World.snapshot(null)) {
            if (d.kind == 'i' || d.dist > reach + 2) continue;
            double w = d.kind == 'a' ? 0.9 : 0.6, h = d.kind == 'a' ? 1.0 : 1.8;
            double ex = px + d.dx - camX, ey = py + d.dy - camY, ez = pz + d.dz - camZ;
            double t = rayBox(dx, dy, dz, ex - w / 2, ey, ez - w / 2, ex + w / 2, ey + h, ez + w / 2);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = d;
            }
        }
        targetEntity = best;
        targetBlock = best != null ? null : hit;
    }

    private static double rayBox(double dx, double dy, double dz, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = 1e9;
        double[] o = {0, 0, 0}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    // ---- overlays: outline, cracks, entities ----

    private FloatBuffer lineBuf = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private void drawOutline() {
        int[] t = targetBlock;
        if (t == null) return;
        int st = MapData.stateAt(t[0], t[1], t[2]);
        float[][] boxes = st > 0 ? Shapes.outline(st) : Shapes.FULL;
        if (boxes.length == 0) boxes = Shapes.FULL;
        lineBuf.clear();
        float e = 0.002f;
        for (float[] b : boxes) {
            float x0 = b[0] - e, y0 = b[1] - e, z0 = b[2] - e, x1 = b[3] + e, y1 = b[4] + e, z1 = b[5] + e;
            float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
            int[] edges = {0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7};
            for (int i : edges) if (lineBuf.remaining() >= 3) lineBuf.put(c[i]);
        }
        int n = lineBuf.position() / 3;
        lineBuf.position(0);
        GLES20.glUseProgram(lineProg);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mvp, 0);
        GLES20.glUniform3f(lOff, (float) (t[0] - camX), (float) (t[1] - camY), (float) (t[2] - camZ));
        GLES20.glUniform4f(lColor, 0f, 0f, 0f, 0.55f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glLineWidth(Math.max(2f, vh / 400f));
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, lineBuf);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, n);
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Other players and mobs: shaded boxes (players 0.6 x 1.8, animals wider). */
    private void drawEntities() {
        List<World.Dot> dots = World.snapshot(null);
        if (dots.isEmpty()) return;
        GLES20.glUseProgram(lineProg);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mvp, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(lPos);
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        for (World.Dot d : dots) {
            if (d.dist > renderDistance * 16) continue;
            float w = d.kind == 'a' ? 0.9f : d.kind == 'i' ? 0.25f : 0.6f, h = d.kind == 'a' ? 1.0f : d.kind == 'i' ? 0.25f : 1.8f;
            int col = d.kind == 'p' ? (d.friend ? 0x60A5FA : 0xF8FAFC) : d.kind == 'h' ? 0xEF4444 : d.kind == 'a' ? 0x84CC16 : d.kind == 'i' ? 0xFACC15 : 0xA78BFA;
            GLES20.glUniform3f(lOff, (float) (px + d.dx - camX), (float) (py + d.dy - camY), (float) (pz + d.dz - camZ));
            float[] shade = {1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};
            for (int f = 0; f < 6; f++) {
                float[][] c = GameMesher.cornersOf(f, new float[]{-w / 2, 0, -w / 2, w / 2, h, w / 2});
                lineBuf.clear();
                for (int k : new int[]{0, 1, 2, 0, 2, 3}) lineBuf.put(c[k]);
                lineBuf.position(0);
                float s = shade[f] * (d == targetEntity ? 1f : 0.85f);
                GLES20.glUniform4f(lColor, ((col >> 16) & 255) / 255f * s, ((col >> 8) & 255) / 255f * s, (col & 255) / 255f * s, 1f);
                GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, lineBuf);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            }
        }
        GLES20.glDisableVertexAttribArray(lPos);
    }

    /** Crack texture over blocks being broken (ours and other players'), multiplied onto the block like vanilla. */
    private void drawCracks(GameAssetStore.Loaded a) {
        List<int[]> list = new ArrayList<int[]>();
        int[] t = targetBlock;
        Bot b = Hub.bot;
        float progress = Bot.miningProgress;
        if (t != null && b != null && b.isMining() && progress >= 0) list.add(new int[]{t[0], t[1], t[2], Math.min(9, (int) (progress * 10))});
        for (Map.Entry<Long, Integer> e : GameState.breaking.entrySet()) {
            long k = e.getKey();
            list.add(new int[]{(int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38), e.getValue()});
        }
        if (list.isEmpty()) return;
        GLES20.glUseProgram(prog);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_SRC_COLOR);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glPolygonOffset(-1f, -4f);
        GLES20.glUniform1f(uAlphaRef, 0.01f);
        GLES20.glUniform2f(uFogRange, 1e6f, 2e6f);
        GLES20.glUniform1f(uDay, 1f);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glEnableVertexAttribArray(aLight);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        for (int[] c : list) {
            int tile = a.atlas.tile("block/destroy_stage_" + c[3]);
            if (tile < 0) continue;
            int st = MapData.stateAt(c[0], c[1], c[2]);
            float[][] boxes = st > 0 ? Shapes.outline(st) : Shapes.FULL;
            if (boxes.length == 0) boxes = Shapes.FULL;
            ByteBuffer bb = ByteBuffer.allocateDirect(boxes.length * 6 * 4 * GameMesher.BYTES).order(ByteOrder.nativeOrder());
            int quads = 0;
            float tu = (tile % GameAtlas.GRID) / (float) GameAtlas.GRID, tv = (tile / GameAtlas.GRID) / (float) GameAtlas.GRID, ts = 1f / GameAtlas.GRID;
            for (float[] box : boxes)
                for (int f = 0; f < 6; f++) {
                    float[][] cs = GameMesher.cornersOf(f, box);
                    float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
                    for (int k = 0; k < 4; k++) {
                        bb.putShort((short) Math.round(cs[k][0] * GameMesher.POS)).putShort((short) Math.round(cs[k][1] * GameMesher.POS)).putShort((short) Math.round(cs[k][2] * GameMesher.POS));
                        bb.putShort((short) Math.round((tu + uv[k][0] * ts) * 65535)).putShort((short) Math.round((tv + uv[k][1] * ts) * 65535));
                        bb.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
                    }
                    quads++;
                }
            bb.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glUniform3f(uOff, (float) (c[0] - camX), (float) (c[1] - camY), (float) (c[2] - camZ));
            bb.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_SHORT, false, GameMesher.BYTES, bb);
            bb.position(6);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_UNSIGNED_SHORT, true, GameMesher.BYTES, bb);
            bb.position(10);
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, bb);
            bb.position(14);
            GLES20.glVertexAttribPointer(aLight, 2, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, bb);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, quads * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        }
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glDisableVertexAttribArray(aLight);
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("GL program: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("GL shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
