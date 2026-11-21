package app.afkbot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State that only the Play screen (the full game view) needs: hunger, XP, time of day, weather, game mode and other
 * players' block-breaking. Bot fills it from packets that are only decoded while the Play screen is open (see
 * {@link #open}), so the AFK bot, map and light 3D view stay as cheap as before.
 */
final class GameState {
    private GameState() {
    }

    /** True while the Play screen is open: decode the extra packets and keep light data. */
    static volatile boolean open;

    static volatile int food = 20;
    static volatile float saturation = 5;
    static volatile float xpBar;
    static volatile int xpLevel;
    /** Time of day in ticks (0 = sunrise, 6000 = noon, 18000 = midnight), and when we last heard it. */
    static volatile long dayTime = 6000;
    static volatile long dayTimeAt = System.currentTimeMillis();
    static volatile boolean dayTicks = true;
    static volatile float rain;
    /** 0 survival, 1 creative, 2 adventure, 3 spectator. */
    static volatile int gameMode;

    /** Block -> destroy stage 0..9 shown by other players breaking it (key from {@link #key}). */
    static final Map<Long, Integer> breaking = new ConcurrentHashMap<Long, Integer>();

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Current time of day, advanced smoothly between the server's updates (20 ticks a second). */
    static float dayTimeNow() {
        long t = dayTime;
        if (dayTicks) t += (System.currentTimeMillis() - dayTimeAt) / 50;
        return ((t % 24000) + 24000) % 24000;
    }

    /** How bright the sky is, 0 (night) .. 1 (day), like vanilla's sky darkening (rain makes it darker). */
    static float daylight() {
        float t = dayTimeNow() / 24000f - 0.25f; // vanilla: celestial angle, 0 at noon
        if (t < 0) t += 1;
        float angle = t;
        float c = (float) Math.cos(angle * Math.PI * 2) * 2 + 0.5f;
        c = Math.max(0, Math.min(1, c));
        c *= 1 - rain * 0.3125f;
        return c;
    }

    static void reset() {
        food = 20;
        saturation = 5;
        xpBar = 0;
        xpLevel = 0;
        rain = 0;
        gameMode = 0;
        breaking.clear();
    }
}
