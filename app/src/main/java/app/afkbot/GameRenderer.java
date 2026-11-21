package app.afkbot;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
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
    private final GameSky sky = new GameSky();
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
        initEntities();
        sky.init();
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
        float[] fogc = GameSky.fogColor(day, GameState.rain); // the world fades into the horizon colour, like the game
        float sr = fogc[0], sg = fogc[1], sb = fogc[2];
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
        animateTextures(a);
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
        sky.draw(a, fov, aspect, view, camX, camY, camZ, day, GameState.rain, far);

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
        drawEntities(day, sr, sg, sb, far);
        drawCracks(a);
        drawOutline();
        drawHand(a, day, aspect);
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
                        if (m.biomes != a.biomes) {
                            m.biomes = a.biomes;
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
            if (uploadBuf == null || uploadBuf.capacity() < m.data[p].length)
                uploadBuf = ByteBuffer.allocateDirect(Math.max(m.data[p].length, 256 * 1024)).order(ByteOrder.nativeOrder());
            ByteBuffer bb = uploadBuf;
            bb.clear();
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

    // reusable native buffers (allocating them every frame makes Android's garbage collector stutter)
    private ByteBuffer scratch, uploadBuf;

    /** A cleared direct buffer of at least n bytes, reused between draws (each draw consumes it right away). */
    private ByteBuffer scratch(int n) {
        if (scratch == null || scratch.capacity() < n) scratch = ByteBuffer.allocateDirect(Math.max(n, 64 * 1024)).order(ByteOrder.nativeOrder());
        scratch.clear();
        return scratch;
    }

    private int[] shownFrame = new int[0];
    private final ByteBuffer frameBuf = ByteBuffer.allocateDirect(GameAtlas.TILE * GameAtlas.TILE * 4).order(ByteOrder.nativeOrder());

    /** Water, lava, fire, portals...: puts each animated texture's current frame into the atlas (20 ticks a second). */
    private void animateTextures(GameAssetStore.Loaded a) {
        List<GameAtlas.Anim> anims = a.atlas.anims;
        if (anims.isEmpty() || texId == 0) return;
        if (shownFrame.length != anims.size()) {
            shownFrame = new int[anims.size()];
            java.util.Arrays.fill(shownFrame, -1);
        }
        long tick = System.currentTimeMillis() / 50;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        for (int i = 0; i < anims.size(); i++) {
            GameAtlas.Anim an = anims.get(i);
            int idx = an.order[(int) ((tick / an.frameTime) % an.order.length)];
            if (idx == shownFrame[i]) continue;
            shownFrame[i] = idx;
            frameBuf.clear();
            for (int p : an.frames[idx]) frameBuf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            frameBuf.position(0);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, (an.tile % GameAtlas.GRID) * GameAtlas.TILE, (an.tile / GameAtlas.GRID) * GameAtlas.TILE,
                    GameAtlas.TILE, GameAtlas.TILE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuf);
        }
    }

    private void upload(GameAssetStore.Loaded a) {
        shownFrame = new int[0];
        int[] ids = new int[1];
        if (texId != 0) GLES20.glDeleteTextures(1, new int[]{texId}, 0);
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // straight (not premultiplied) RGBA from the atlas pixels: Android's GLUtils would upload a premultiplied
        // bitmap, which makes translucent textures (water, stained glass) too dark with our blending
        int[] px = a.atlas.pixels;
        ByteBuffer rgba = ByteBuffer.allocateDirect(px.length * 4).order(ByteOrder.nativeOrder());
        for (int p : px) rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        rgba.position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, GameAtlas.SIZE, GameAtlas.SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
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
        int[] hit = GameRay.cast(camX, camY, camZ, dx, dy, dz, reach);
        double blockT = hit == null ? Double.MAX_VALUE : hit[4] / 1000.0;
        World.Dot best = null;
        double bestT = Math.min(blockT, reach);
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        for (World.Dot d : World.snapshot(null)) {
            if (d.kind == 'i' || d.dist > reach + 3) continue;
            double w = d.kind == 'a' ? 0.9 : 0.6, h = d.kind == 'a' ? 1.0 : 1.8;
            double ex = px + d.dx, ey = py + d.dy, ez = pz + d.dz;
            double[] t = GameRay.box(camX, camY, camZ, dx, dy, dz, ex - w / 2, ey, ez - w / 2, ex + w / 2, ey + h, ez + w / 2);
            if (t != null && t[0] < bestT) {
                bestT = t[0];
                best = d;
            }
        }
        targetEntity = best;
        targetBlock = best != null || hit == null ? null : new int[]{hit[0], hit[1], hit[2], hit[3]};
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

    // ---- entities: Mojang's own models (EntityModels), lit by the light where they stand ----

    private static final String EVS =
            "attribute vec3 aPos; attribute vec2 aUv; attribute float aShade; attribute float aLimb;\n" +
            "uniform mat4 uMvp; uniform vec3 uPos; uniform vec2 uRot; uniform vec2 uFogRange;\n" +
            "uniform vec3 uPivot[4]; uniform float uSwing;\n" +
            "varying vec2 vUv; varying float vShade; varying float vFog;\n" +
            "void main() {\n" +
            "  vec3 q = aPos;\n" +
            "  if (aLimb > 0.5) {\n" + // walk: legs (and arms, opposite) swing around their pivot, like the game
            "    int li = int(aLimb + 0.5) - 1;\n" +
            "    vec3 pv = uPivot[0];\n" +
            "    if (li == 1) pv = uPivot[1]; else if (li == 2) pv = uPivot[2]; else if (li == 3) pv = uPivot[3];\n" +
            "    float a = (li == 0 || li == 3) ? uSwing : -uSwing;\n" +
            "    vec3 d = q - pv;\n" +
            "    q = pv + vec3(d.x, d.y * cos(a) - d.z * sin(a), d.y * sin(a) + d.z * cos(a));\n" +
            "  }\n" +
            "  vec3 p = vec3(q.x * uRot.x - q.z * uRot.y, q.y, q.x * uRot.y + q.z * uRot.x) + uPos;\n" +
            "  gl_Position = uMvp * vec4(p, 1.0);\n" +
            "  vUv = aUv; vShade = aShade;\n" +
            "  vFog = clamp((length(p) - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);\n" +
            "}";
    private static final String EFS =
            "precision mediump float;\n" +
            "uniform sampler2D uTex; uniform vec3 uLight; uniform vec3 uFog; uniform vec4 uTint;\n" +
            "varying vec2 vUv; varying float vShade; varying float vFog;\n" +
            "void main() {\n" +
            "  vec4 t = texture2D(uTex, vUv);\n" +
            "  if (t.a < 0.1) discard;\n" +
            "  vec3 c = mix(t.rgb, uTint.rgb, uTint.a) * vShade * uLight;\n" +
            "  gl_FragColor = vec4(mix(c, uFog, vFog), 1.0);\n" +
            "}";
    private int eProg, eaPos, eaUv, eaShade, eaLimb, euMvp, euPos, euRot, euFogRange, euTex, euLight, euFog, euTint, euPivot, euSwing;
    /** Walk animation state per entity id: {last x, last z, walk position, swing amount, last time ms}. */
    private final Map<Integer, double[]> walk = new java.util.HashMap<Integer, double[]>();
    private final Map<EntityModels.Model, int[]> modelGl = new java.util.HashMap<EntityModels.Model, int[]>(); // {vbo, tex, vertices}
    /** Name tags for the HUD: {screen x, screen y, text}, updated every frame. */
    volatile List<Object[]> labels = new ArrayList<Object[]>();
    private final float[] lc = new float[3];

    private void initEntities() {
        eProg = program(EVS, EFS);
        eaPos = GLES20.glGetAttribLocation(eProg, "aPos");
        eaUv = GLES20.glGetAttribLocation(eProg, "aUv");
        eaShade = GLES20.glGetAttribLocation(eProg, "aShade");
        eaLimb = GLES20.glGetAttribLocation(eProg, "aLimb");
        euPivot = GLES20.glGetUniformLocation(eProg, "uPivot");
        euSwing = GLES20.glGetUniformLocation(eProg, "uSwing");
        euMvp = GLES20.glGetUniformLocation(eProg, "uMvp");
        euPos = GLES20.glGetUniformLocation(eProg, "uPos");
        euRot = GLES20.glGetUniformLocation(eProg, "uRot");
        euFogRange = GLES20.glGetUniformLocation(eProg, "uFogRange");
        euTex = GLES20.glGetUniformLocation(eProg, "uTex");
        euLight = GLES20.glGetUniformLocation(eProg, "uLight");
        euFog = GLES20.glGetUniformLocation(eProg, "uFog");
        euTint = GLES20.glGetUniformLocation(eProg, "uTint");
        modelGl.clear();
        skinGl.clear();
    }

    /** GL textures of players' real skins, by URL (0 while one downloads). */
    private final Map<String, Integer> skinGl = new java.util.HashMap<String, Integer>();

    private int skinTex(String url) {
        if (url == null) return 0;
        Integer t = skinGl.get(url);
        if (t != null) return t;
        int[] px = GameSkins.get(url);
        if (px == null) return 0;
        if (skinGl.size() > 64) { // many players came and went: start over
            for (int id : skinGl.values()) GLES20.glDeleteTextures(1, new int[]{id}, 0);
            skinGl.clear();
        }
        ByteBuffer rgba = scratch(64 * 64 * 4);
        for (int p : px) rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        rgba.position(0);
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 64, 64, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        skinGl.put(url, ids[0]);
        return ids[0];
    }

    private int[] modelGl(EntityModels.Model m) {
        int[] g = modelGl.get(m);
        if (g != null) return g;
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        FloatBuffer fb = ByteBuffer.allocateDirect(m.mesh.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(m.mesh).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.mesh.length * 4, fb, GLES20.GL_STATIC_DRAW);
        int vbo = ids[0];
        int w = m.texture.getWidth(), h = m.texture.getHeight();
        ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = m.texture.getPixel(x, y);
                rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            }
        rgba.position(0);
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        int limbVbo = 0;
        if (m.limbs != null) {
            int[] lb = new int[1];
            GLES20.glGenBuffers(1, lb, 0);
            FloatBuffer lf = ByteBuffer.allocateDirect(m.limbs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            lf.put(m.limbs).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lb[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.limbs.length * 4, lf, GLES20.GL_STATIC_DRAW);
            limbVbo = lb[0];
        }
        g = new int[]{vbo, ids[0], m.mesh.length / Geo.STRIDE, limbVbo};
        modelGl.put(m, g);
        return g;
    }

    /** Players and mobs with their real models (boxes while a model downloads), plus name tag positions. */
    private void drawEntities(float day, float sr, float sg, float sb, float far) {
        List<World.Dot> dots = World.snapshot(null);
        List<Object[]> tags = new ArrayList<Object[]>();
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        int n = 0;
        GLES20.glUseProgram(eProg);
        GLES20.glUniformMatrix4fv(euMvp, 1, false, mvp, 0);
        GLES20.glUniform2f(euFogRange, far * 0.72f, far);
        GLES20.glUniform3f(euFog, sr, sg, sb);
        GLES20.glUniform1i(euTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(eaPos);
        GLES20.glEnableVertexAttribArray(eaUv);
        GLES20.glEnableVertexAttribArray(eaShade);
        GLES20.glDisable(GLES20.GL_CULL_FACE); // some model parts are single planes
        List<World.Dot> boxes = new ArrayList<World.Dot>();
        for (World.Dot d : dots) {
            if (d.dist > renderDistance * 16 || d.kind == 'i') {
                if (d.kind == 'i' && d.dist <= renderDistance * 16) boxes.add(d);
                continue;
            }
            float x = (float) (px + d.dx - camX), y = (float) (py + d.dy - camY), z = (float) (pz + d.dz - camZ);
            if (d.kind == 'p') {
                float[] sp = project(x, y + 2.15f, z);
                if (sp != null) tags.add(new Object[]{sp[0], sp[1], d.label});
            }
            EntityModels.Model m = ++n <= 120 ? EntityModels.get(d.type) : null;
            if (m == null || m.texture == null) {
                boxes.add(d);
                continue;
            }
            int[] g = modelGl(m);
            int l = MapData.lightAt((int) Math.floor(px + d.dx), (int) Math.floor(py + d.dy + 0.5), (int) Math.floor(pz + d.dz));
            if (l < 0) l = 0xF0;
            GameLight.color(l >> 4, l & 15, day, lc);
            GLES20.glUniform3f(euLight, lc[0], lc[1], lc[2]);
            GLES20.glUniform4f(euTint, 1f, 1f, 1f, d == targetEntity ? 0.18f : 0f);
            double a = Math.toRadians(d.yaw + 180);
            GLES20.glUniform2f(euRot, (float) Math.cos(a), (float) Math.sin(a));
            GLES20.glUniform3f(euPos, x, y, z);
            // walk animation from how fast it actually moves (the game: limb swing grows with speed, up to 1)
            float swing = 0;
            if (g[3] != 0 && m.pivots != null) {
                double wx = px + d.dx, wz = pz + d.dz;
                long now = System.currentTimeMillis();
                double[] st = walk.get(d.id);
                if (st == null) walk.put(d.id, st = new double[]{wx, wz, 0, 0, now});
                double dt = Math.max(1, now - st[4]) / 50.0; // in ticks
                double moved = Math.hypot(wx - st[0], wz - st[1]);
                double target = Math.min(1, moved / dt * 4);
                st[3] += (target - st[3]) * Math.min(1, dt * 0.4);
                st[2] += st[3] * dt;
                st[0] = wx;
                st[1] = wz;
                st[4] = now;
                swing = (float) (Math.cos(st[2] * 0.6662) * 1.4 * st[3]);
                GLES20.glUniform3fv(euPivot, 4, m.pivots, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g[3]);
                GLES20.glEnableVertexAttribArray(eaLimb);
                GLES20.glVertexAttribPointer(eaLimb, 1, GLES20.GL_FLOAT, false, 4, 0);
            } else {
                GLES20.glDisableVertexAttribArray(eaLimb);
                GLES20.glVertexAttrib4f(eaLimb, 0, 0, 0, 0);
            }
            GLES20.glUniform1f(euSwing, swing);
            int skin = d.kind == 'p' && m.texture.getWidth() == 64 && m.texture.getHeight() == 64 ? skinTex(d.skin) : 0;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skin != 0 ? skin : g[1]);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g[0]);
            GLES20.glVertexAttribPointer(eaPos, 3, GLES20.GL_FLOAT, false, Geo.STRIDE * 4, 0);
            GLES20.glVertexAttribPointer(eaUv, 2, GLES20.GL_FLOAT, false, Geo.STRIDE * 4, 12);
            GLES20.glVertexAttribPointer(eaShade, 1, GLES20.GL_FLOAT, false, Geo.STRIDE * 4, 20);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, g[2]);
        }
        GLES20.glDisableVertexAttribArray(eaPos);
        GLES20.glDisableVertexAttribArray(eaUv);
        GLES20.glDisableVertexAttribArray(eaShade);
        GLES20.glDisableVertexAttribArray(eaLimb);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        if (walk.size() > 400) walk.clear(); // forget entities long gone
        labels = tags;
        List<World.Dot> items = new ArrayList<World.Dot>(), others = new ArrayList<World.Dot>();
        for (World.Dot d : boxes) (d.kind == 'i' ? items : others).add(d);
        if (!items.isEmpty()) others.addAll(drawItems(items));
        if (!others.isEmpty()) drawBoxes(others, day);
    }

    private GameIcons icons;
    private GameAssetStore.Loaded iconsFor;

    /**
     * Dropped items as the game shows them: the item itself, spinning and bobbing (flat items as a double-sided
     * sprite, blocks as a small cube). Returns the ones that couldn't be drawn (item not known yet).
     */
    private List<World.Dot> drawItems(List<World.Dot> items) {
        GameAssetStore.Loaded a = assets;
        List<World.Dot> rest = new ArrayList<World.Dot>();
        if (a == null) return items;
        if (iconsFor != a) {
            icons = new GameIcons(a.files, a.atlas);
            iconsFor = a;
        }
        GLES20.glUseProgram(prog);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1f(uAlphaRef, 0.5f);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glEnableVertexAttribArray(aLight);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        long now = System.currentTimeMillis();
        for (World.Dot d : items) {
            GameIcons.Icon ic = d.item == null ? null : icons.get(d.item);
            if (ic == null || ic.empty()) {
                rest.add(d);
                continue;
            }
            int l = MapData.lightAt((int) Math.floor(px + d.dx), (int) Math.floor(py + d.dy + 0.25), (int) Math.floor(pz + d.dz));
            if (l < 0) l = 0xF0;
            double spin = (now / 1000.0 + d.id * 0.37) % (Math.PI * 2);
            float bob = (float) (0.1 + 0.05 * Math.sin(now / 500.0 + d.id));
            ByteBuffer bb = scratch(8 * 4 * GameMesher.BYTES);
            int quads;
            if (ic.flat()) {
                quads = 0;
                for (int[] layer : ic.layers) { // a 0.5-block sprite, standing up
                    float h = 0.5f, w = h * layer[2] / Math.max(1, layer[3]);
                    float[][] c = {{-w / 2, 0, 0}, {w / 2, 0, 0}, {w / 2, h, 0}, {-w / 2, h, 0}};
                    float[][] uv = {{layer[0], layer[1] + layer[3]}, {layer[0] + layer[2], layer[1] + layer[3]}, {layer[0] + layer[2], layer[1]}, {layer[0], layer[1]}};
                    int tint = layer[4] != 0 ? layer[4] : 0xFFFFFF;
                    quad(bb, c, uv, spin, tint, l, 1f);
                    quads++;
                }
            } else { // a quarter-size cube
                float e = 0.125f;
                int[][] rects = {ic.top, ic.top, ic.front, ic.side, ic.side, ic.side};
                float[] shade = {1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};
                for (int f = 0; f < 6; f++) {
                    float[][] c = GameMesher.cornersOf(f, new float[]{-e, 0, -e, e, 2 * e, e});
                    int[] r = rects[f];
                    float[][] uv = {{r[0], r[1] + r[3]}, {r[0] + r[2], r[1] + r[3]}, {r[0] + r[2], r[1]}, {r[0], r[1]}};
                    int t = (f == 0 || ic.tintSides) && ic.tint != 0 ? ic.tint : 0xFFFFFF;
                    quad(bb, c, uv, spin, t, l, shade[f]);
                }
                quads = 6;
            }
            bb.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glUniform3f(uOff, (float) (px + d.dx - camX), (float) (py + d.dy - camY) + bob, (float) (pz + d.dz - camZ));
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
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        return rest;
    }

    /** One quad (corners around the item's centre, uvs in atlas pixels) turned by the spin angle, in the block format. */
    private static void quad(ByteBuffer bb, float[][] c, float[][] uvPx, double spin, int tint, int light, float shade) {
        double cos = Math.cos(spin), sin = Math.sin(spin);
        int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
        for (int k = 0; k < 4; k++) {
            double x = c[k][0] * cos - c[k][2] * sin, z = c[k][0] * sin + c[k][2] * cos;
            bb.putShort((short) Math.round(x * GameMesher.POS)).putShort((short) Math.round(c[k][1] * GameMesher.POS)).putShort((short) Math.round(z * GameMesher.POS));
            bb.putShort((short) Math.round(uvPx[k][0] / GameAtlas.SIZE * 65535)).putShort((short) Math.round(uvPx[k][1] / GameAtlas.SIZE * 65535));
            bb.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).put((byte) ((light >> 4) * 17)).put((byte) ((light & 15) * 17));
        }
    }

    /** Entities without a model yet (and items not known yet): shaded boxes. */
    private void drawBoxes(List<World.Dot> dots, float day) {
        GLES20.glUseProgram(lineProg);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mvp, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(lPos);
        double px = World.selfX, py = World.selfY, pz = World.selfZ;
        long now = System.currentTimeMillis();
        for (World.Dot d : dots) {
            boolean item = d.kind == 'i';
            float w = d.kind == 'a' ? 0.9f : item ? 0.25f : 0.6f, h = d.kind == 'a' ? 1.0f : item ? 0.25f : 1.8f;
            int col = d.kind == 'p' ? (d.friend ? 0x60A5FA : 0xF8FAFC) : d.kind == 'h' ? 0xEF4444 : d.kind == 'a' ? 0x84CC16 : item ? 0xFACC15 : 0xA78BFA;
            float bob = item ? (float) (0.1 + 0.08 * Math.sin(now / 400.0 + d.id)) : 0; // dropped items float and bob
            GLES20.glUniform3f(lOff, (float) (px + d.dx - camX), (float) (py + d.dy - camY) + bob, (float) (pz + d.dz - camZ));
            int l = MapData.lightAt((int) Math.floor(px + d.dx), (int) Math.floor(py + d.dy + 0.5), (int) Math.floor(pz + d.dz));
            if (l < 0) l = 0xF0;
            GameLight.color(l >> 4, l & 15, day, lc);
            float[] shade = {1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};
            for (int f = 0; f < 6; f++) {
                float[][] c = GameMesher.cornersOf(f, new float[]{-w / 2, 0, -w / 2, w / 2, h, w / 2});
                lineBuf.clear();
                for (int k : new int[]{0, 1, 2, 0, 2, 3}) lineBuf.put(c[k]);
                lineBuf.position(0);
                float s = shade[f] * (d == targetEntity ? 1f : 0.85f);
                GLES20.glUniform4f(lColor, ((col >> 16) & 255) / 255f * s * lc[0], ((col >> 8) & 255) / 255f * s * lc[1], (col & 255) / 255f * s * lc[2], 1f);
                GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, lineBuf);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            }
        }
        GLES20.glDisableVertexAttribArray(lPos);
    }

    /** Screen position of a camera-relative point, or null if behind or far off screen. */
    private float[] project(float x, float y, float z) {
        float[] in = {x, y, z, 1}, out = new float[4];
        Matrix.multiplyMV(out, 0, mvp, 0, in, 0);
        if (out[3] <= 0.05f) return null;
        float nx = out[0] / out[3], ny = out[1] / out[3];
        if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) return null;
        return new float[]{(nx + 1) / 2 * vw, (1 - ny) / 2 * vh};
    }

    // ---- first person: your arm, or the item you hold, with swing and walk bob ----

    private volatile long swingAt;
    private double lastBobX, lastBobZ, walked;
    private final float[] handProj = new float[16];

    /** Starts the arm swing (tap, hit, place, and repeatedly while breaking). */
    void swing() {
        long now = System.currentTimeMillis();
        if (now - swingAt > 250) swingAt = now;
    }

    private void drawHand(GameAssetStore.Loaded a, float day, float aspect) {
        if (icons == null || iconsFor != a) {
            icons = new GameIcons(a.files, a.atlas);
            iconsFor = a;
        }
        // walking bob: how far we've walked, smoothed
        double dx = World.selfX - lastBobX, dz = World.selfZ - lastBobZ;
        double step = Math.sqrt(dx * dx + dz * dz);
        if (step < 1) walked += step;
        lastBobX = World.selfX;
        lastBobZ = World.selfZ;
        float bobX = (float) Math.sin(walked * Math.PI * 0.9) * 0.02f, bobY = -(float) Math.abs(Math.cos(walked * Math.PI * 0.9)) * 0.025f;
        float p = Math.min(1f, (System.currentTimeMillis() - swingAt) / 280f);
        float sw = p >= 1 ? 0 : (float) Math.sin(p * Math.PI);
        int l = MapData.lightAt((int) Math.floor(World.selfX), (int) Math.floor(World.selfY + 1), (int) Math.floor(World.selfZ));
        if (l < 0) l = 0xF0;

        Inv.Item held = Inv.heldItem();
        GameIcons.Icon ic = held == null || Hub.versionData == null ? null : icons.get(Hub.versionData.itemId(held.id));
        ByteBuffer bb = scratch(8 * 4 * GameMesher.BYTES);
        int quads = 0;
        // the transform: a small rotation and a position at the lower right of the view, plus swing and bob
        float tx = 0.56f + bobX - sw * 0.25f, ty = -0.52f + bobY + sw * 0.12f, tz = -0.72f - sw * 0.1f;
        float rotX = -sw * 50f, rotY = -12f + sw * 20f;
        if (ic == null || ic.empty()) { // the bare right arm (default skin)
            int t = a.atlas.tile("entity/player/wide/steve");
            if (t < 0) return;
            int sx = (t % GameAtlas.GRID) * GameAtlas.TILE, sy = (t / GameAtlas.GRID) * GameAtlas.TILE;
            float w = 0.11f, len = 0.8f;
            // long axis along -z; uv rects (pixels in the 64x64 skin): length runs along v
            float[][][] faces = {
                    {{-w, w, 0}, {w, w, 0}, {w, w, -len}, {-w, w, -len}}, // top side (the arm's front)
                    {{-w, -w, -len}, {w, -w, -len}, {w, -w, 0}, {-w, -w, 0}}, // underside
                    {{-w, -w, 0}, {-w, w, 0}, {-w, w, -len}, {-w, -w, -len}}, // inner side (facing the centre)
                    {{w, w, 0}, {w, -w, 0}, {w, -w, -len}, {w, w, -len}}, // outer side
                    {{-w, -w, -len}, {-w, w, -len}, {w, w, -len}, {w, -w, -len}}}; // the hand
            int[][] rects = {{44, 20, 4, 12}, {52, 20, 4, 12}, {48, 20, 4, 12}, {40, 20, 4, 12}, {48, 16, 4, 4}};
            float[] shade = {1f, 0.55f, 0.8f, 0.65f, 0.9f};
            for (int f = 0; f < 5; f++) {
                int[] r = rects[f];
                float[][] uv = f < 4 ? new float[][]{{sx + r[0], sy + r[1]}, {sx + r[0] + r[2], sy + r[1]}, {sx + r[0] + r[2], sy + r[1] + r[3]}, {sx + r[0], sy + r[1] + r[3]}}
                        : new float[][]{{sx + r[0], sy + r[1] + r[3]}, {sx + r[0], sy + r[1]}, {sx + r[0] + r[2], sy + r[1]}, {sx + r[0] + r[2], sy + r[1] + r[3]}};
                viewQuad(bb, faces[f], uv, 0xFFFFFF, shade[f], l, tx, ty, tz, rotX - 10, rotY + 8);
                quads++;
            }
        } else { // the item, placed with its model's own first-person transform (like the game)
            // the game's hand position, raised a little: on a wide phone screen with touch controls the game's own
            // spot (-0.52) leaves held blocks mostly below the edge, behind the hotbar
            float[] base = {0.54f + bobX - sw * 0.3f, -0.4f + bobY + sw * 0.1f, -0.72f - sw * 0.15f};
            float swingRot = -sw * 40f;
            if (ic.flat()) {
                for (int[] layer : ic.layers) { // a 1x1 item quad (16 px) centred on the model's origin
                    float[][] c = {{0, 0, 0.5f}, {1, 0, 0.5f}, {1, 1, 0.5f}, {0, 1, 0.5f}};
                    float[][] uv = {{layer[0], layer[1] + layer[3]}, {layer[0] + layer[2], layer[1] + layer[3]}, {layer[0] + layer[2], layer[1]}, {layer[0], layer[1]}};
                    itemQuad(bb, c, uv, layer[4] != 0 ? layer[4] : 0xFFFFFF, 1f, l, ic, base, swingRot);
                    quads++;
                }
            } else {
                int[][] rects = {ic.top, ic.top, ic.front, ic.side, ic.side, ic.side};
                float[] shade = {1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f};
                for (int f = 0; f < 6; f++) {
                    float[][] c = GameMesher.cornersOf(f, new float[]{0, 0, 0, 1, 1, 1});
                    int[] r = rects[f];
                    float[][] uv = {{r[0], r[1] + r[3]}, {r[0] + r[2], r[1] + r[3]}, {r[0] + r[2], r[1]}, {r[0], r[1]}};
                    int tint = (f == 0 || ic.tintSides) && ic.tint != 0 ? ic.tint : 0xFFFFFF;
                    itemQuad(bb, c, uv, tint, shade[f], l, ic, base, swingRot);
                    quads++;
                }
            }
        }
        if (quads == 0) return;
        Matrix.perspectiveM(handProj, 0, 70f, aspect, 0.02f, 10f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT); // the hand is always in front
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, handProj, 0);
        GLES20.glUniform3f(uOff, 0, 0, 0);
        GLES20.glUniform2f(uFogRange, 1e6f, 2e6f);
        GLES20.glUniform1f(uDay, day);
        GLES20.glUniform1f(uAlphaRef, 0.5f);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glEnableVertexAttribArray(aLight);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        bb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_SHORT, false, GameMesher.BYTES, bb);
        bb.position(6);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_UNSIGNED_SHORT, true, GameMesher.BYTES, bb);
        bb.position(10);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, bb);
        bb.position(14);
        GLES20.glVertexAttribPointer(aLight, 2, GLES20.GL_UNSIGNED_BYTE, true, GameMesher.BYTES, bb);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, quads * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glDisableVertexAttribArray(aLight);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
    }

    /**
     * A quad of an item model (corners in 0..1 model space) placed like the game does it in first person:
     * hand position + display translation (1/16 block) + rotation (X, Y, Z) + scale, around the model's centre.
     */
    private static void itemQuad(ByteBuffer bb, float[][] c, float[][] uvPx, int tint, float shade, int light, GameIcons.Icon ic,
                                 float[] base, float swingRot) {
        double rx = Math.toRadians(ic.fpRot[0] + swingRot), ry = Math.toRadians(ic.fpRot[1]), rz = Math.toRadians(ic.fpRot[2]);
        int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
        for (int k = 0; k < 4; k++) {
            final double phone = 0.8; // a bit smaller than on a monitor, so it doesn't crowd the touch controls
            double x = (c[k][0] - 0.5) * ic.fpScale[0] * phone, y = (c[k][1] - 0.5) * ic.fpScale[1] * phone, z = (c[k][2] - 0.5) * ic.fpScale[2] * phone;
            // R = Rx * Ry * Rz (the game's rotationXYZ): Z first, then Y, then X
            double x1 = x * Math.cos(rz) - y * Math.sin(rz), y1 = x * Math.sin(rz) + y * Math.cos(rz), z1 = z;
            double x2 = x1 * Math.cos(ry) + z1 * Math.sin(ry), z2 = -x1 * Math.sin(ry) + z1 * Math.cos(ry), y2 = y1;
            double y3 = y2 * Math.cos(rx) - z2 * Math.sin(rx), z3 = y2 * Math.sin(rx) + z2 * Math.cos(rx), x3 = x2;
            double fx = base[0] + ic.fpTrans[0] / 16.0 * phone + x3, fy = base[1] + ic.fpTrans[1] / 16.0 * phone + y3, fz = base[2] + ic.fpTrans[2] / 16.0 * phone + z3;
            bb.putShort((short) Math.round(fx * GameMesher.POS)).putShort((short) Math.round(fy * GameMesher.POS)).putShort((short) Math.round(fz * GameMesher.POS));
            bb.putShort((short) Math.round(uvPx[k][0] / GameAtlas.SIZE * 65535)).putShort((short) Math.round(uvPx[k][1] / GameAtlas.SIZE * 65535));
            bb.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).put((byte) ((light >> 4) * 17)).put((byte) ((light & 15) * 17));
        }
    }

    /** A quad in view space: corners rotated (X then Y, degrees) and moved to (tx, ty, tz); uvs in atlas pixels. */
    private static void viewQuad(ByteBuffer bb, float[][] c, float[][] uvPx, int tint, float shade, int light, float tx, float ty, float tz, float rx, float ry) {
        double ax = Math.toRadians(rx), ay = Math.toRadians(ry);
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay);
        int r = (int) (((tint >> 16) & 255) * shade), g = (int) (((tint >> 8) & 255) * shade), b = (int) ((tint & 255) * shade);
        for (int k = 0; k < 4; k++) {
            double x = c[k][0], y = c[k][1], z = c[k][2];
            double y1 = y * cx - z * sx, z1 = y * sx + z * cx;
            double x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
            bb.putShort((short) Math.round((x2 + tx) * GameMesher.POS)).putShort((short) Math.round((y1 + ty) * GameMesher.POS)).putShort((short) Math.round((z2 + tz) * GameMesher.POS));
            bb.putShort((short) Math.round(uvPx[k][0] / GameAtlas.SIZE * 65535)).putShort((short) Math.round(uvPx[k][1] / GameAtlas.SIZE * 65535));
            bb.put((byte) r).put((byte) g).put((byte) b).put((byte) 255).put((byte) ((light >> 4) * 17)).put((byte) ((light & 15) * 17));
        }
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
            ByteBuffer bb = scratch(boxes.length * 6 * 4 * GameMesher.BYTES);
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

    static int program(String vs, String fs) {
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
