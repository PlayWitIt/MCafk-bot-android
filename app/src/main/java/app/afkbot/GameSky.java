package app.afkbot;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * The Play screen's sky, drawn the way the game does it: a gradient from the sky colour overhead to the fog colour at
 * the horizon (warmed at sunrise and sunset), the sun and the moon (with its phase) moving on the game's celestial
 * angle, stars at night, and the game's cloud layer at its real height, drifting and fading into the fog.
 * Called from GameRenderer on the GL thread before the world is drawn.
 */
final class GameSky {
    private int colorProg, cPos, cCol, cMvp;
    private int texProg, tPos, tUv, tMvp, tColor, tTex, tFog, tFogRange, tUvOff;
    private int sunTex, moonTex, cloudTex;
    private final int[] moonPhaseTex = new int[8]; // 1.21.9+: one texture per phase
    private final float[] skyProj = new float[16];
    private GameAssetStore.Loaded uploaded;
    private FloatBuffer dome, stars;
    private int domeVerts, starVerts;
    private final float[] rot = new float[16], tmp = new float[16];

    private static final String CVS = "attribute vec3 aPos; attribute vec4 aCol; uniform mat4 uMvp; varying vec4 vCol;"
            + " void main() { gl_Position = uMvp * vec4(aPos, 1.0); vCol = aCol; }";
    private static final String CFS = "precision mediump float; varying vec4 vCol; void main() { gl_FragColor = vCol; }";
    private static final String TVS = "attribute vec3 aPos; attribute vec2 aUv; uniform mat4 uMvp; uniform vec2 uUvOff; uniform vec2 uFogRange;"
            + " varying vec2 vUv; varying vec2 vXZ;"
            + " void main() { gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv + uUvOff; vXZ = aPos.xz; }";
    // fog per pixel (a big cloud plane has its corners far away)
    private static final String TFS = "precision highp float; uniform sampler2D uTex; uniform vec4 uColor; uniform vec3 uFog; uniform vec2 uFogRange;"
            + " varying vec2 vUv; varying vec2 vXZ;"
            + " void main() { vec4 t = texture2D(uTex, vUv) * uColor; if (t.a < 0.01) discard;"
            + " float vFog = clamp((length(vXZ) - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);"
            + " gl_FragColor = vec4(mix(t.rgb, uFog, vFog), t.a * (1.0 - vFog)); }";

    void init() {
        colorProg = GameRenderer.program(CVS, CFS);
        cPos = GLES20.glGetAttribLocation(colorProg, "aPos");
        cCol = GLES20.glGetAttribLocation(colorProg, "aCol");
        cMvp = GLES20.glGetUniformLocation(colorProg, "uMvp");
        texProg = GameRenderer.program(TVS, TFS);
        tPos = GLES20.glGetAttribLocation(texProg, "aPos");
        tUv = GLES20.glGetAttribLocation(texProg, "aUv");
        tMvp = GLES20.glGetUniformLocation(texProg, "uMvp");
        tColor = GLES20.glGetUniformLocation(texProg, "uColor");
        tTex = GLES20.glGetUniformLocation(texProg, "uTex");
        tFog = GLES20.glGetUniformLocation(texProg, "uFog");
        tFogRange = GLES20.glGetUniformLocation(texProg, "uFogRange");
        tUvOff = GLES20.glGetUniformLocation(texProg, "uUvOff");
        uploaded = null;
        buildStars();
    }

    // ---------------------------------------------------------------- time of day (the game's formulas)

    /** The game's celestial angle: 0 at noon, 0.25 at sunset, 0.5 at midnight, 0.75 at sunrise (eased). */
    static float celestialAngle() {
        double f = GameState.dayTimeNow() / 24000.0 - 0.25;
        f = f - Math.floor(f);
        double eased = 1.0 - (Math.cos(f * Math.PI) + 1.0) / 2.0;
        return (float) (f + (eased - f) / 3.0);
    }

    /** Horizon (fog) colour: paler than the sky by day, warmed towards orange at sunrise and sunset. */
    static float[] fogColor(float day, float rain) {
        float r = 0.75f, g = 0.85f, b = 1.0f; // the game's daytime fog
        r = r * (day * 0.94f + 0.06f);
        g = g * (day * 0.94f + 0.06f);
        b = b * (day * 0.91f + 0.09f);
        float glow = sunsetGlow();
        r += (1.0f - r) * glow * 0.55f;
        g += (0.55f - g) * glow * 0.45f;
        b += (0.25f - b) * glow * 0.45f;
        if (rain > 0) {
            float grey = (r * 0.3f + g * 0.59f + b * 0.11f) * 0.6f;
            r += (grey - r) * rain * 0.75f;
            g += (grey - g) * rain * 0.75f;
            b += (grey - b) * rain * 0.75f;
        }
        return new float[]{r, g, b};
    }

    /** 0..1: how strong the sunrise/sunset glow is right now (the game's sunrise colour window). */
    static float sunsetGlow() {
        float c = (float) Math.cos(celestialAngle() * Math.PI * 2);
        if (c < -0.4f || c > 0.4f) return 0;
        float v = c / 0.4f * 0.5f + 0.5f;
        float a = 1.0f - (1.0f - (float) Math.sin(v * Math.PI)) * 0.99f;
        return a * a;
    }

    /** The game's star brightness for the current time (0 by day). */
    static float starBrightness() {
        float c = 1.0f - ((float) Math.cos(celestialAngle() * Math.PI * 2) * 2.0f + 0.25f);
        c = Math.max(0, Math.min(1, c));
        return c * c * 0.5f;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws the sky. view = the camera's rotation-only view matrix, proj = projection; camY = eye height in the
     * world; far = fog end distance for the world (clouds fade into it too).
     */
    void draw(GameAssetStore.Loaded a, float fov, float aspect, float[] view, double camX, double camY, double camZ, float day, float rain, float far) {
        if (a != uploaded) upload(a);
        // the sky has its own projection reaching far enough for clouds, sun and moon (the world's is much shorter)
        float[] proj = skyProj;
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.5f, 1000f);
        int sky = GameLight.skyColor(day, rain);
        float sr = ((sky >> 16) & 255) / 255f, sg = ((sky >> 8) & 255) / 255f, sb = (sky & 255) / 255f;
        float[] fog = fogColor(day, rain);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);

        // gradient dome: sky colour up top, fog colour at and below the horizon
        buildDome(sr, sg, sb, fog);
        GLES20.glUseProgram(colorProg);
        GLES20.glUniformMatrix4fv(cMvp, 1, false, mvp, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(cPos);
        GLES20.glEnableVertexAttribArray(cCol);
        dome.position(0);
        GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 28, dome);
        dome.position(3);
        GLES20.glVertexAttribPointer(cCol, 4, GLES20.GL_FLOAT, false, 28, dome);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, domeVerts);

        // the sky turns with the time of day: sun, moon and stars ride on it
        float angle = celestialAngle();
        Matrix.setIdentityM(rot, 0);
        Matrix.rotateM(rot, 0, -90f, 0, 1, 0); // the sun rises in the east (+X)
        Matrix.rotateM(rot, 0, angle * 360f, 1, 0, 0);
        Matrix.multiplyMM(tmp, 0, view, 0, rot, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

        float starLight = starBrightness() * (1 - rain);
        GLES20.glEnable(GLES20.GL_BLEND);
        if (starLight > 0.01f) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
            GLES20.glUniformMatrix4fv(cMvp, 1, false, mvp, 0);
            this.stars.position(0);
            GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 28, this.stars);
            this.stars.position(3);
            GLES20.glVertexAttribPointer(cCol, 4, GLES20.GL_FLOAT, false, 28, this.stars);
            GLES20.glDisableVertexAttribArray(cCol);
            GLES20.glVertexAttrib4f(cCol, starLight, starLight, starLight, starLight);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, starVerts);
        }
        GLES20.glDisableVertexAttribArray(cPos);
        GLES20.glDisableVertexAttribArray(cCol);

        // sun and moon: additive, like the game
        GLES20.glUseProgram(texProg);
        GLES20.glUniformMatrix4fv(tMvp, 1, false, mvp, 0);
        GLES20.glUniform1i(tTex, 0);
        GLES20.glUniform3f(tFog, fog[0], fog[1], fog[2]);
        GLES20.glUniform2f(tFogRange, 1e6f, 2e6f);
        GLES20.glUniform2f(tUvOff, 0, 0);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        float vis = 1f - rain;
        if (sunTex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sunTex);
            GLES20.glUniform4f(tColor, 1, 1, 1, vis);
            celestialQuad(30f, 100f, 0, 0, 1, 1);
        }
        int phase = (int) (((GameState.worldDay() % 8) + 8) % 8);
        if (moonPhaseTex[phase] != 0) { // one texture per phase (1.21.9+)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, moonPhaseTex[phase]);
            GLES20.glUniform4f(tColor, 1, 1, 1, vis);
            celestialQuad(20f, -100f, 1, 1, 0, 0);
        } else if (moonTex != 0) { // older: all phases in one 4x2 image
            float u0 = (phase % 4) / 4f, v0 = (phase / 4) / 2f;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, moonTex);
            GLES20.glUniform4f(tColor, 1, 1, 1, vis);
            celestialQuad(20f, -100f, u0 + 0.25f, v0 + 0.5f, u0, v0); // the opposite side of the sky's frame
        }

        // clouds: the game's cloud map, one pixel = 12 blocks, at y 192.33, drifting with time
        if (cloudTex != 0) {
            float cloudY = (float) (192.33 - camY);
            float size = Math.max(384f, far * 3f);
            float drift = (float) ((GameState.worldAge() + (System.currentTimeMillis() % 50000) / 50.0) * 0.03);
            float uo = (float) (((camX + drift) / (12.0 * 256.0)) % 1.0), vo = (float) ((camZ / (12.0 * 256.0)) % 1.0);
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            GLES20.glUniformMatrix4fv(tMvp, 1, false, mvp, 0);
            GLES20.glUniform2f(tUvOff, uo, vo);
            GLES20.glUniform2f(tFogRange, size * 0.45f, size);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            float c = 0.1f + 0.9f * day;
            GLES20.glUniform4f(tColor, c, c, c * 1.02f, 0.8f);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cloudTex);
            float t = size / (12f * 256f);
            float[] q = {-size, cloudY, -size, 0, 0, size, cloudY, -size, t * 2, 0, size, cloudY, size, t * 2, t * 2,
                    -size, cloudY, -size, 0, 0, size, cloudY, size, t * 2, t * 2, -size, cloudY, size, 0, t * 2};
            drawTex(q, 6);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
    }

    /** A textured square on the sky sphere, size s at distance d along the sky's +Y (negative d: the opposite side). */
    private void celestialQuad(float s, float d, float u0, float v0, float u1, float v1) {
        float y = d;
        // facing the centre: the square lies in the XZ plane at height y
        float[] q = {-s, y, -s, u0, v0, s, y, -s, u1, v0, s, y, s, u1, v1, -s, y, -s, u0, v0, s, y, s, u1, v1, -s, y, s, u0, v1};
        drawTex(q, 6);
    }

    private FloatBuffer texBuf = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private void drawTex(float[] q, int verts) {
        FloatBuffer fb = texBuf; // reused (no per-frame allocation)
        fb.clear();
        fb.put(q).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(tPos);
        GLES20.glEnableVertexAttribArray(tUv);
        fb.position(0);
        GLES20.glVertexAttribPointer(tPos, 3, GLES20.GL_FLOAT, false, 20, fb);
        fb.position(3);
        GLES20.glVertexAttribPointer(tUv, 2, GLES20.GL_FLOAT, false, 20, fb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
        GLES20.glDisableVertexAttribArray(tPos);
        GLES20.glDisableVertexAttribArray(tUv);
    }

    private void buildDome(float sr, float sg, float sb, float[] fog) {
        // rings from straight up to below the horizon; colour from the sky colour to the fog colour
        int segs = 24;
        float[] el = {90, 40, 15, 5, 0, -30, -90};
        FloatBuffer fb = dome != null && dome.capacity() >= segs * (el.length - 1) * 6 * 7 ? dome
                : ByteBuffer.allocateDirect(segs * (el.length - 1) * 6 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.clear();
        int n = 0;
        for (int r = 0; r + 1 < el.length; r++)
            for (int s = 0; s < segs; s++) {
                double a0 = s * Math.PI * 2 / segs, a1 = (s + 1) * Math.PI * 2 / segs;
                double e0 = Math.toRadians(el[r]), e1 = Math.toRadians(el[r + 1]);
                double[][] p = {{a0, e0}, {a1, e0}, {a1, e1}, {a0, e0}, {a1, e1}, {a0, e1}};
                for (double[] v : p) {
                    float x = (float) (Math.cos(v[1]) * Math.cos(v[0])) * 50, y = (float) Math.sin(v[1]) * 50, z = (float) (Math.cos(v[1]) * Math.sin(v[0])) * 50;
                    float k = (float) Math.max(0, Math.min(1, Math.sin(v[1]) / Math.sin(Math.toRadians(35))));
                    fb.put(x).put(y).put(z).put(fog[0] + (sr - fog[0]) * k).put(fog[1] + (sg - fog[1]) * k).put(fog[2] + (sb - fog[2]) * k).put(1f);
                    n++;
                }
            }
        dome = fb;
        domeVerts = n;
    }

    /** 1500 stars at fixed random places on the sky sphere (like the game's seeded star field). */
    private void buildStars() {
        Random rnd = new Random(10842L);
        FloatBuffer fb = ByteBuffer.allocateDirect(1500 * 6 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        int n = 0;
        for (int i = 0; i < 1500; i++) {
            double x = rnd.nextFloat() * 2 - 1, y = rnd.nextFloat() * 2 - 1, z = rnd.nextFloat() * 2 - 1;
            double size = 0.15 + rnd.nextFloat() * 0.1;
            double len = x * x + y * y + z * z;
            if (len >= 1 || len < 0.01) continue;
            len = 1 / Math.sqrt(len);
            x *= len * 100;
            y *= len * 100;
            z *= len * 100;
            // a small square facing the centre
            double[] up = Math.abs(y) < 90 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            double[] f = {x / 100, y / 100, z / 100};
            double[] r = {up[1] * f[2] - up[2] * f[1], up[2] * f[0] - up[0] * f[2], up[0] * f[1] - up[1] * f[0]};
            double rl = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
            for (int k = 0; k < 3; k++) r[k] = r[k] / rl * size;
            double[] u = {f[1] * r[2] - f[2] * r[1], f[2] * r[0] - f[0] * r[2], f[0] * r[1] - f[1] * r[0]};
            double[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
            for (double[] cc : c) {
                fb.put((float) (x + r[0] * cc[0] + u[0] * cc[1])).put((float) (y + r[1] * cc[0] + u[1] * cc[1])).put((float) (z + r[2] * cc[0] + u[2] * cc[1]));
                fb.put(1f).put(1f).put(1f).put(1f);
                n++;
            }
        }
        stars = fb;
        starVerts = n;
    }

    private static final String[] PHASES = {"full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon",
            "waxing_crescent", "first_quarter", "waxing_gibbous"};

    private void upload(GameAssetStore.Loaded a) {
        String sun = a.texture("environment/celestial/sun") != null ? "environment/celestial/sun" : "environment/sun";
        sunTex = texture(a.pixels(sun), a.texture(sun), false);
        moonTex = texture(a.pixels("environment/moon_phases"), a.texture("environment/moon_phases"), false);
        for (int i = 0; i < 8; i++) {
            String m = "environment/celestial/moon/" + PHASES[i];
            moonPhaseTex[i] = texture(a.pixels(m), a.texture(m), false);
        }
        cloudTex = texture(a.pixels("environment/clouds"), a.texture("environment/clouds"), true);
        uploaded = a;
    }

    private static int texture(int[] px, android.graphics.Bitmap b, boolean repeat) {
        if (px == null || b == null) return 0;
        int w = b.getWidth(), h = b.getHeight();
        ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int p : px) rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        rgba.position(0);
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        int wrap = repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        return ids[0];
    }
}
