package app.afkbot;

/**
 * How bright a vertex is from its sky and block light (0..15) and the time of day. The GL shader in GameRenderer
 * computes exactly the same thing; this copy is used by the desktop preview and for picking the sky/fog colour.
 *
 * Shape follows vanilla's light map: brightness(l) = l / (4 - 3l) (each level ~roughly 80% of the next), lifted by
 * the default "brightness" setting, never fully black, block light slightly warm, sky light dimmed at night.
 */
final class GameLight {
    private GameLight() {
    }

    static final float AMBIENT = 0.04f;

    static float curve(float level) { // level 0..1
        float b = level / (4f - 3f * level);
        float lifted = 1f - (1f - b) * (1f - b) * (1f - b) * (1f - b); // vanilla's gamma lift
        return b + (lifted - b) * 0.5f; // "brightness: 50%" (the default)
    }

    /** RGB multipliers (0..1) for sky light s and block light b (both 0..15) at the given daylight (0..1). */
    static void color(int s, int b, float daylight, float[] out) {
        float sky = curve(s / 15f * (0.2f + 0.8f * daylight));
        float blk = curve(b / 15f);
        // block light: warm (torches); sky light: neutral by day, a little blue at night
        float r = Math.max(sky * (0.85f + 0.15f * daylight), blk);
        float g = Math.max(sky * (0.88f + 0.12f * daylight), blk * 0.9f);
        float bl = Math.max(sky, blk * 0.75f);
        out[0] = Math.min(1, AMBIENT + r * (1 - AMBIENT));
        out[1] = Math.min(1, AMBIENT + g * (1 - AMBIENT));
        out[2] = Math.min(1, AMBIENT + bl * (1 - AMBIENT));
    }

    /** Sky colour for the time of day (clear-sky blue at noon, dark blue at night, orange tint at sunrise/sunset). */
    static int skyColor(float daylight, float rain) {
        float r = 0.47f, g = 0.65f, b = 1.0f; // vanilla plains sky
        float d = daylight;
        r *= d;
        g *= d;
        b *= d;
        r += 0.01f;
        g += 0.01f;
        b += 0.04f;
        if (rain > 0) {
            float grey = (r * 0.3f + g * 0.59f + b * 0.11f) * 0.6f;
            r += (grey - r) * rain * 0.75f;
            g += (grey - g) * rain * 0.75f;
            b += (grey - b) * rain * 0.75f;
        }
        return 0xFF000000 | (int) (Math.min(1, r) * 255) << 16 | (int) (Math.min(1, g) * 255) << 8 | (int) (Math.min(1, b) * 255);
    }
}
