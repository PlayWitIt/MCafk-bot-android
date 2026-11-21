package app.afkbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Play screen's HUD and touch controls, drawn with the game's own art at the game's GUI scale: its pixel font,
 * HUD sprites (crosshair, hotbar, hearts, hunger, XP), the real inventory and chest screens, and its buttons. Controls
 * work like the touch edition: a floating stick on the left moves (push all the way to sprint), dragging anywhere else
 * looks around, a tap uses/places (or hits a mob), holding breaks.
 */
final class GameHud extends View {
    interface Host {
        void openChat();

        void leave();

        void settingsChanged();
    }

    private final GameRenderer r;
    private final Host host;
    private final SharedPreferences prefs;
    volatile GameAssetStore.Loaded assets;
    private GameAssetStore.Loaded built; // what icons/font/sprites below were made from
    private GameIcons icons;
    private GameFont font;
    private Bitmap fontBmp;
    private final Map<String, Bitmap> sprites = new HashMap<String, Bitmap>();
    private final Paint pix = new Paint(), ui = new Paint(Paint.ANTI_ALIAS_FLAG), tint = new Paint();
    private final float dp;

    // settings (kept in the "play" preferences)
    int renderDistance, fov, sensitivity;
    boolean showCoords;

    GameHud(Context c, GameRenderer r, Host host) {
        super(c);
        this.r = r;
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        prefs = c.getSharedPreferences("play", Context.MODE_PRIVATE);
        renderDistance = prefs.getInt("renderDistance", 6);
        fov = prefs.getInt("fov", 70);
        sensitivity = prefs.getInt("sensitivity", 100);
        showCoords = prefs.getBoolean("coords", false);
        pix.setFilterBitmap(false);
        pix.setAntiAlias(false);
        tint.setFilterBitmap(false);
        setKeepScreenOn(true);
    }

    void saveSettings() {
        prefs.edit().putInt("renderDistance", renderDistance).putInt("fov", fov).putInt("sensitivity", sensitivity).putBoolean("coords", showCoords).apply();
        host.settingsChanged();
    }

    private Bitmap sprite(String name) {
        GameAssetStore.Loaded a = assets;
        if (a == null) return null;
        if (sprites.containsKey(name)) return sprites.get(name);
        Bitmap b = a.sprite(name);
        sprites.put(name, b);
        return b;
    }

    private Bitmap texture(String path) {
        GameAssetStore.Loaded a = assets;
        if (a == null) return null;
        if (sprites.containsKey(path)) return sprites.get(path);
        Bitmap b = a.texture(path);
        sprites.put(path, b);
        return b;
    }

    // ================================================================== text in the game's font

    /** Draws text with its top-left at (x, y); s = screen pixels per font pixel. Returns the width drawn. */
    private float text(Canvas c, String str, float x, float y, int color, boolean shadow, float s) {
        str = GameFont.strip(str);
        if (font == null || fontBmp == null) {
            ui.setColor(color);
            ui.setTextSize(8 * s);
            c.drawText(str, x, y + 7 * s, ui);
            return ui.measureText(str);
        }
        if (shadow) drawGlyphs(c, str, x + s, y + s, shade(color), s);
        return drawGlyphs(c, str, x, y, color, s);
    }

    private static int shade(int color) { // the game's text shadow: the colour at 25% brightness
        return (color & 0xFF000000) | (((color >> 16) & 255) / 4) << 16 | (((color >> 8) & 255) / 4) << 8 | ((color & 255) / 4);
    }

    private float drawGlyphs(Canvas c, String str, float x, float y, int color, float s) {
        tint.setColorFilter(new LightingColorFilter(color & 0xFFFFFF, 0));
        tint.setAlpha(color >>> 24);
        float x0 = x;
        int cell = font.cell;
        for (int i = 0; i < str.length(); i++) {
            int g = GameFont.glyph(str.charAt(i));
            int gx = (g % 16) * cell, gy = (g / 16) * cell;
            if (g != ' ') c.drawBitmap(fontBmp, new Rect(gx, gy, gx + cell, gy + cell), new RectF(x, y, x + 8 * s, y + 8 * s), tint);
            x += font.advance[g] * s;
        }
        return x - x0;
    }

    private float textWidth(String str, float s) {
        str = GameFont.strip(str);
        if (font == null) {
            ui.setTextSize(8 * s);
            return ui.measureText(str);
        }
        return font.width(str) * s;
    }

    private void textCentered(Canvas c, String str, float cx, float y, int color, float s) {
        text(c, str, cx - textWidth(str, s) / 2, y, color, true, s);
    }

    // ================================================================== drawing

    private int gui = 2; // the game's GUI scale: the largest that still fits 320x240
    private final List<Object[]> buttons = new ArrayList<Object[]>(); // {RectF, String id}
    private long frames, fpsAt;
    private int fps;

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        gui = Math.max(1, Math.min(w / 320, h / 240));
        buttons.clear();
        GameAssetStore.Loaded a = assets;
        frames++;
        long now = System.currentTimeMillis();
        if (now - fpsAt > 1000) {
            fps = (int) frames;
            frames = 0;
            fpsAt = now;
        }
        if (a != null && a != built) setUp(a);
        if (a == null || !World.inWorld) {
            drawLoading(c, w, h);
            postInvalidateOnAnimation();
            return;
        }
        tickHold();
        boolean survival = GameState.gameMode == 0 || GameState.gameMode == 2;
        boolean menu = screen != NONE || Inv.openId >= 0 || Hub.statHealth <= 0;
        if (!menu) drawCrosshair(c, w, h);
        float hotTop = drawHotbar(c, w, h);
        if (survival) hotTop = drawStatus(c, w, hotTop);
        drawChat(c, hotTop);
        drawNameTags(c);
        if (!menu) {
            drawTargetName(c, w);
            if (showCoords) drawDebug(c);
            drawControls(c, w, h);
        }
        if (Hub.statHealth <= 0) drawDeath(c, w, h);
        else if (Inv.openId >= 0) drawContainer(c, w, h);
        else if (screen == INVENTORY) drawInventory(c, w, h);
        else if (screen == PAUSE) drawPause(c, w, h);
        postInvalidateOnAnimation();
    }

    private void setUp(GameAssetStore.Loaded a) {
        built = a;
        sprites.clear();
        icons = new GameIcons(a.files, a.atlas);
        fontBmp = a.texture("font/ascii");
        int[] px = a.pixels("font/ascii");
        font = px == null || fontBmp == null ? null : new GameFont(px, fontBmp.getWidth());
    }

    private void drawLoading(Canvas c, int w, int h) {
        c.drawColor(0xFF1B1410);
        String s = !World.inWorld ? (Hub.running ? "Joining the server..." : "Not connected - start AFK first") : GameAssetStore.status;
        textCentered(c, s, w / 2f, h / 2f - 4 * gui, 0xFFFFFFFF, gui);
        textCentered(c, "Play (experimental)", w / 2f, h / 2f - 20 * gui, 0xFFA0A0A0, gui);
        menuButton(c, "leave", w / 2f - 50 * gui, h / 2f + 12 * gui, 100 * gui, 20 * gui, "Back");
    }

    private void drawCrosshair(Canvas c, int w, int h) {
        Bitmap b = sprite("hud/crosshair");
        float s = 15 * gui;
        if (b != null) c.drawBitmap(b, null, new RectF(w / 2f - s / 2, h / 2f - s / 2, w / 2f + s / 2, h / 2f + s / 2), pix);
    }

    /** Returns the top of the hotbar. */
    private float drawHotbar(Canvas c, int w, int h) {
        float bw = 182 * gui, bh = 22 * gui, x = (w - bw) / 2, y = h - bh;
        Bitmap bar = sprite("hud/hotbar"), sel = sprite("hud/hotbar_selection");
        if (bar != null) c.drawBitmap(bar, null, new RectF(x, y, x + bw, y + bh), pix);
        int held = Math.max(0, Math.min(8, Inv.held));
        if (sel != null) c.drawBitmap(sel, null, new RectF(x - gui + held * 20 * gui, y - gui, x - gui + held * 20 * gui + 24 * gui, y - gui + 23 * gui), pix);
        List<Inv.Item> inv = Inv.inventory;
        for (int i = 0; i < 9; i++) {
            float sx = x + 3 * gui + i * 20 * gui, sy = y + 3 * gui;
            buttons.add(new Object[]{new RectF(sx - 2 * gui, y - 4 * gui, sx + 18 * gui, y + bh), "slot" + i});
            Inv.Item it = 36 + i < inv.size() ? inv.get(36 + i) : null;
            if (it != null) drawItem(c, it, sx, sy, gui);
        }
        // the touch edition's "..." button next to the hotbar opens the inventory
        float ix = x + bw + 2 * gui, iy = y + 2 * gui, is = bh - 4 * gui;
        RectF ib = new RectF(ix, iy, ix + is, iy + is);
        if (bar != null) c.drawBitmap(bar, new Rect(0, 0, 1, 22), ib, pix);
        ui.setColor(0xFF000000 | 0x8B8B8B);
        c.drawRect(new RectF(ib.left + gui, ib.top + gui, ib.right - gui, ib.bottom - gui), ui);
        ui.setColor(pressed.containsValue("inventory") ? 0xFFFFFFA0 : 0xFFFFFFFF);
        for (int k = -1; k <= 1; k++) c.drawRect(ib.centerX() + k * 4 * gui - gui, ib.centerY() - gui, ib.centerX() + k * 4 * gui + gui, ib.centerY() + gui, ui);
        buttons.add(new Object[]{new RectF(ib.left - 2 * gui, ib.top - 4 * gui, ib.right + 6 * gui, ib.bottom + 2 * gui), "inventory"});
        return y;
    }

    private float drawStatus(Canvas c, int w, float hotTop) {
        float bw = 182 * gui, x = (w - bw) / 2;
        // XP bar and level
        float xy = hotTop - 7 * gui;
        Bitmap bg = sprite("hud/experience_bar_background"), pr = sprite("hud/experience_bar_progress");
        if (bg != null) c.drawBitmap(bg, null, new RectF(x, xy, x + bw, xy + 5 * gui), pix);
        float p = Math.max(0, Math.min(1, GameState.xpBar));
        if (pr != null && p > 0) {
            int sw = Math.max(1, Math.round(pr.getWidth() * p));
            c.drawBitmap(pr, new Rect(0, 0, sw, pr.getHeight()), new RectF(x, xy, x + bw * p, xy + 5 * gui), pix);
        }
        if (GameState.xpLevel > 0) { // the game's green level number with a black outline
            String lv = String.valueOf(GameState.xpLevel);
            float lx = w / 2f - textWidth(lv, gui) / 2, ly = xy - 6 * gui;
            for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) text(c, lv, lx + o[0] * gui, ly + o[1] * gui, 0xFF000000, false, gui);
            text(c, lv, lx, ly, 0xFF80FF20, false, gui);
        }
        // hearts (left) and hunger (right)
        float hy = xy - 10 * gui;
        float health = Hub.statHealth;
        Bitmap cont = sprite("hud/heart/container"), full = sprite("hud/heart/full"), half = sprite("hud/heart/half");
        for (int i = 0; i < 10; i++) {
            RectF d = new RectF(x + i * 8 * gui, hy, x + i * 8 * gui + 9 * gui, hy + 9 * gui);
            if (cont != null) c.drawBitmap(cont, null, d, pix);
            float v = health - i * 2;
            Bitmap b = v >= 2 ? full : v >= 1 ? half : null;
            if (b != null) c.drawBitmap(b, null, d, pix);
        }
        int food = GameState.food;
        Bitmap fe = sprite("hud/food_empty"), ff = sprite("hud/food_full"), fh = sprite("hud/food_half");
        for (int i = 0; i < 10; i++) {
            float fx = x + bw - 9 * gui - i * 8 * gui;
            RectF d = new RectF(fx, hy, fx + 9 * gui, hy + 9 * gui);
            if (fe != null) c.drawBitmap(fe, null, d, pix);
            int v = food - i * 2;
            Bitmap b = v >= 2 ? ff : v >= 1 ? fh : null;
            if (b != null) c.drawBitmap(b, null, d, pix);
        }
        return hy;
    }

    /** Chat like the game: the last lines fade out after ten seconds (all of them while the chat box is open). */
    private void drawChat(Canvas c, float bottom) {
        List<Object[]> lines = Hub.recentChat(10);
        long now = System.currentTimeMillis();
        float s = Math.max(1, gui * 0.75f), lh = 9 * s, y = bottom - 4 * gui - lh, maxW = getWidth() * 0.5f;
        for (int i = lines.size() - 1; i >= 0 && y > getHeight() * 0.2f; i--) {
            String msg = GameFont.strip((String) lines.get(i)[0]);
            long age = now - (Long) lines.get(i)[1];
            if (age > 10000) continue;
            float alpha = age < 8000 ? 1 : 1 - (age - 8000) / 2000f;
            while (textWidth(msg, s) > maxW && msg.length() > 4) msg = msg.substring(0, msg.length() - 2);
            ui.setColor(Color.argb((int) (128 * alpha), 0, 0, 0));
            c.drawRect(0, y - s, maxW + 4 * s, y + lh - s, ui);
            text(c, msg, 2 * s, y, Color.argb((int) (255 * alpha), 255, 255, 255), true, s);
            y -= lh;
        }
    }

    /** Player names above their heads, like the game: white text on a dark translucent strip. */
    private void drawNameTags(Canvas c) {
        float s = Math.max(1, gui * 0.6f);
        for (Object[] l : r.labels) {
            String name = (String) l[2];
            float x = (Float) l[0], y = (Float) l[1], tw = textWidth(name, s);
            ui.setColor(0x40000000);
            c.drawRect(x - tw / 2 - s, y - s, x + tw / 2 + s, y + 8 * s, ui);
            text(c, name, x - tw / 2, y, 0xFFFFFFFF, false, s);
        }
    }

    /** What you're looking at (top centre), and how far breaking it has got. */
    private void drawTargetName(Canvas c, int w) {
        String s = null;
        World.Dot e = r.targetEntity;
        int[] t = r.targetBlock;
        if (e != null) s = e.label;
        else if (t != null && Hub.versionData != null) {
            int st = MapData.stateAt(t[0], t[1], t[2]);
            if (st > 0) s = Hub.versionData.blockName(st);
            Bot b = Hub.bot;
            if (s != null && b != null && b.isMining() && Bot.miningProgress >= 0) s += String.format(Locale.US, "  %d%%", Math.round(Bot.miningProgress * 100));
        }
        if (s == null) return;
        float sc = Math.max(1, gui * 0.75f), tw = textWidth(s, sc);
        ui.setColor(0x90000000);
        c.drawRect(w / 2f - tw / 2 - 4 * sc, 4 * sc, w / 2f + tw / 2 + 4 * sc, 16 * sc, ui);
        text(c, s, w / 2f - tw / 2, 6 * sc, 0xFFFFFFFF, true, sc);
    }

    private void drawDebug(Canvas c) {
        double[] cam = r.camera();
        String[] dirs = {"south (+Z)", "west (-X)", "north (-Z)", "east (+X)"};
        int d = Math.round(((r.yaw % 360) + 360) % 360 / 90f) & 3;
        float s = Math.max(1, gui * 0.75f), y = 2 * s;
        for (String line : new String[]{fps + " fps", String.format(Locale.US, "XYZ: %.3f / %.5f / %.3f", cam[0], cam[1] - 1.62, cam[2]), "Facing: " + dirs[d]}) {
            float tw = textWidth(line, s);
            ui.setColor(0x90505050);
            c.drawRect(s, y - s, 3 * s + tw, y + 8 * s, ui);
            text(c, line, 2 * s, y, 0xFFE0E0E0, false, s);
            y += 9 * s;
        }
    }

    // ---- touch buttons (drawn icons, thumb sized) ----

    private void drawControls(Canvas c, int w, int h) {
        float m = 14 * dp;
        iconButton(c, "pause", w - m - 44 * dp, m, 44 * dp);
        iconButton(c, "chat", w - m - 96 * dp, m, 44 * dp);
        float big = 64 * dp, mid = 52 * dp, by = h - 24 * gui - m - big;
        iconButton(c, "jump", w - m - big, by, big);
        iconButton(c, "sneak", w - m - big - 10 * dp - mid, by + big - mid, mid);
        // the floating stick while a thumb is on it, else a faint hint where it lives
        float rr = 56 * dp;
        if (stickId >= 0) {
            ui.setColor(0x40FFFFFF);
            c.drawCircle(stickX, stickY, rr, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2 * dp);
            ui.setColor(0x80FFFFFF);
            c.drawCircle(stickX, stickY, rr, ui);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(sprinting ? 0xD0FFD080 : 0xC0FFFFFF);
            c.drawCircle(stickX + kx * rr, stickY + ky * rr, 24 * dp, ui);
        } else {
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2 * dp);
            ui.setColor(0x30FFFFFF);
            c.drawCircle(m + 20 * dp + rr, h - 24 * gui - m - rr, rr, ui);
            ui.setStyle(Paint.Style.FILL);
        }
    }

    private void iconButton(Canvas c, String id, float x, float y, float s) {
        RectF rr = new RectF(x, y, x + s, y + s);
        boolean down = pressed.containsValue(id) || (id.equals("sneak") && sneak);
        ui.setColor(down ? 0x90FFFFFF : 0x50000000);
        c.drawRoundRect(rr, 10 * dp, 10 * dp, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(1.5f * dp);
        ui.setColor(0x80FFFFFF);
        c.drawRoundRect(rr, 10 * dp, 10 * dp, ui);
        ui.setStrokeWidth(3 * dp);
        ui.setColor(down ? 0xFF202020 : 0xF0FFFFFF);
        float cx = rr.centerX(), cy = rr.centerY(), k = s * 0.22f;
        switch (id) {
            case "jump": // up chevron
                c.drawLine(cx - k, cy + k / 2, cx, cy - k / 2, ui);
                c.drawLine(cx, cy - k / 2, cx + k, cy + k / 2, ui);
                break;
            case "sneak": // down chevron over a bar
                c.drawLine(cx - k, cy - k / 2, cx, cy + k / 3, ui);
                c.drawLine(cx, cy + k / 3, cx + k, cy - k / 2, ui);
                c.drawLine(cx - k, cy + k, cx + k, cy + k, ui);
                break;
            case "pause":
                c.drawLine(cx - k / 2, cy - k, cx - k / 2, cy + k, ui);
                c.drawLine(cx + k / 2, cy - k, cx + k / 2, cy + k, ui);
                break;
            default: // chat: a speech bubble
                ui.setStrokeWidth(2.5f * dp);
                c.drawRoundRect(new RectF(cx - k * 1.2f, cy - k * 0.9f, cx + k * 1.2f, cy + k * 0.6f), 4 * dp, 4 * dp, ui);
                c.drawLine(cx - k * 0.6f, cy + k * 0.6f, cx - k * 0.9f, cy + k * 1.2f, ui);
                break;
        }
        ui.setStyle(Paint.Style.FILL);
        buttons.add(new Object[]{rr, id});
    }

    // ---- item icons ----

    /** An item at (x, y) in a 16x16 slot scaled by s, with its count like the game. */
    void drawItem(Canvas c, Inv.Item it, float x, float y, float s) {
        GameAssetStore.Loaded a = assets;
        if (a == null || Hub.versionData == null || icons == null) return;
        GameIcons.Icon ic = icons.get(Hub.versionData.itemId(it.id));
        Bitmap atlas = a.atlasBitmap;
        float size = 16 * s;
        if (ic.flat()) {
            for (int[] l : ic.layers) {
                // fit tall textures (shield) into the square, keeping their shape
                float fw = size * Math.min(1f, (float) l[2] / l[3]), fh = size * Math.min(1f, (float) l[3] / l[2]);
                RectF d = new RectF(x + (size - fw) / 2, y + (size - fh) / 2, x + (size + fw) / 2, y + (size + fh) / 2);
                Rect src = new Rect(l[0], l[1], l[0] + l[2], l[1] + l[3]);
                if (l[4] != 0) {
                    tint.setColorFilter(new LightingColorFilter(l[4], 0));
                    tint.setAlpha(255);
                    c.drawBitmap(atlas, src, d, tint);
                } else c.drawBitmap(atlas, src, d, pix);
            }
        } else {
            // a small isometric cube like the game's inventory: top bright, front 80%, side 60%
            float m = size * 0.06f, cs = size - 2 * m, ox = x + m, oy = y + m;
            face(c, atlas, ic.top, new float[]{ox + cs / 2, oy, ox + cs, oy + cs / 4, ox + cs / 2, oy + cs / 2, ox, oy + cs / 4}, 1f, ic.tint);
            face(c, atlas, ic.front, new float[]{ox, oy + cs / 4, ox + cs / 2, oy + cs / 2, ox + cs / 2, oy + cs, ox, oy + cs * 3 / 4}, 0.8f, ic.tintSides ? ic.tint : 0);
            face(c, atlas, ic.side, new float[]{ox + cs / 2, oy + cs / 2, ox + cs, oy + cs / 4, ox + cs, oy + cs * 3 / 4, ox + cs / 2, oy + cs}, 0.6f, ic.tintSides ? ic.tint : 0);
        }
        if (it.count > 1) {
            String n = String.valueOf(it.count);
            text(c, n, x + 17 * s - textWidth(n, s), y + 9 * s, 0xFFFFFFFF, true, s);
        }
    }

    private final Paint shadePaint = new Paint();

    private void face(Canvas c, Bitmap atlas, int[] r, float[] dst, float shade, int tintRgb) {
        if (r == null) return;
        float[] s = {r[0], r[1], r[0] + r[2], r[1], r[0] + r[2], r[1] + r[3], r[0], r[1] + r[3]};
        Matrix m = new Matrix();
        m.setPolyToPoly(s, 0, dst, 0, 4);
        int tr = tintRgb != 0 ? (tintRgb >> 16) & 255 : 255, tg = tintRgb != 0 ? (tintRgb >> 8) & 255 : 255, tb = tintRgb != 0 ? tintRgb & 255 : 255;
        shadePaint.setFilterBitmap(false);
        shadePaint.setColorFilter(new LightingColorFilter(Color.rgb((int) (tr * shade), (int) (tg * shade), (int) (tb * shade)), 0));
        c.save();
        c.concat(m);
        c.clipRect(new Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]));
        c.drawBitmap(atlas, 0, 0, shadePaint);
        c.restore();
    }

    // ================================================================== screens

    static final int NONE = 0, INVENTORY = 1, PAUSE = 2;
    int screen = NONE;

    private void dim(Canvas c) {
        c.drawColor(0xA0101010);
    }

    /** A row-major grid of 18px slots at GUI pixel (gx, gy) inside a screen whose top-left is (ox, oy). */
    private void slots(Canvas c, List<Inv.Item> items, int from, int count, float ox, float oy, int gx, int gy, String prefix) {
        for (int i = 0; i < count; i++) {
            float sx = ox + (gx + (i % 9) * 18) * gui, sy = oy + (gy + (i / 9) * 18) * gui;
            int idx = from + i;
            Inv.Item it = idx < items.size() ? items.get(idx) : null;
            if (it != null) drawItem(c, it, sx, sy, gui);
            buttons.add(new Object[]{new RectF(sx - gui, sy - gui, sx + 17 * gui, sy + 17 * gui), prefix + idx});
        }
    }

    /** The game's inventory screen (textures/gui/container/inventory.png) with the exact slot positions. */
    private void drawInventory(Canvas c, int w, int h) {
        dim(c);
        Bitmap bg = texture("gui/container/inventory");
        float pw = 176 * gui, ph = 166 * gui, x = (w - pw) / 2, y = (h - ph) / 2;
        if (bg != null) c.drawBitmap(bg, new Rect(0, 0, 176, 166), new RectF(x, y, x + pw, y + ph), pix);
        text(c, "Crafting", x + 97 * gui, y + 8 * gui, 0xFF404040, false, gui);
        List<Inv.Item> inv = Inv.inventory;
        slots(c, inv, 9, 27, x, y, 8, 84, "inv");
        slots(c, inv, 36, 9, x, y, 8, 142, "inv");
        for (int k = 0; k < 4; k++) slots(c, inv, 5 + k, 1, x, y, 8, 8 + k * 18, "armor"); // helmet..boots (shown)
        slots(c, inv, 45, 1, x, y, 77, 62, "armor"); // off-hand
        text(c, "Tap an item: it goes to your selected hotbar slot", x, y + ph + 3 * gui, 0xFFFFFFFF, true, Math.max(1, gui * 0.75f));
        closeButton(c, x + pw + 3 * gui, y);
    }

    /** Chests and other containers in the game's generic screen (gui/container/generic_54.png). */
    private void drawContainer(Canvas c, int w, int h) {
        dim(c);
        List<Inv.Item> s = Inv.slots;
        int n = Inv.containerSize();
        int rows = Math.max(1, Math.min(6, (n + 8) / 9));
        Bitmap bg = texture("gui/container/generic_54");
        float pw = 176 * gui, ph = (114 + rows * 18) * gui, x = (w - pw) / 2, y = Math.max(0, (h - ph) / 2);
        if (bg != null) { // top part with the container rows, then the player inventory part
            c.drawBitmap(bg, new Rect(0, 0, 176, 17 + rows * 18), new RectF(x, y, x + pw, y + (17 + rows * 18) * gui), pix);
            c.drawBitmap(bg, new Rect(0, 126, 176, 222), new RectF(x, y + (17 + rows * 18) * gui, x + pw, y + ph), pix);
        }
        String title = Inv.title.isEmpty() ? "Container" : Inv.title;
        if (title.startsWith("container.")) title = Character.toUpperCase(title.charAt(10)) + title.substring(11).replace("shulkerBox", "shulker box");
        text(c, title, x + 8 * gui, y + 6 * gui, 0xFF404040, false, gui);
        text(c, "Inventory", x + 8 * gui, y + (rows * 18 + 20) * gui, 0xFF404040, false, gui);
        slots(c, s, 0, Math.min(n, rows * 9), x, y, 8, 18, "con");
        slots(c, s, n, 27, x, y, 8, 31 + rows * 18, "con");
        slots(c, s, n + 27, 9, x, y, 8, 89 + rows * 18, "con");
        text(c, "Tap to move a stack across", x, y + ph + 3 * gui, 0xFFFFFFFF, true, Math.max(1, gui * 0.75f));
        closeButton(c, x + pw + 3 * gui, y);
        menuButton(c, "takeall", x + pw + 3 * gui, y + 24 * gui, 50 * gui, 20 * gui, "Take all");
    }

    private void closeButton(Canvas c, float x, float y) {
        menuButton(c, "close", x, y, 20 * gui, 20 * gui, "X");
    }

    private void drawPause(Canvas c, int w, int h) {
        dim(c);
        textCentered(c, "Game Menu", w / 2f, 16 * gui, 0xFFFFFFFF, gui);
        float bw = 204 * gui, bh = 20 * gui, x = (w - bw) / 2, gap = 24 * gui, y = Math.max(32 * gui, h / 2f - gap * 3);
        float half = (bw - 4 * gui) / 2;
        menuButton(c, "resume", x, y, bw, bh, "Back to Game");
        menuButton(c, "rd", x, y + gap, half, bh, "Render: " + renderDistance + " chunks");
        menuButton(c, "fov", x + half + 4 * gui, y + gap, half, bh, "FOV: " + fov);
        menuButton(c, "sens", x, y + gap * 2, half, bh, "Sensitivity: " + sensitivity + "%");
        menuButton(c, "coords", x + half + 4 * gui, y + gap * 2, half, bh, "Coordinates: " + (showCoords ? "ON" : "OFF"));
        menuButton(c, "chatbtn", x, y + gap * 3, bw, bh, "Chat");
        menuButton(c, "leave", x, y + gap * 4, bw, bh, "Leave Play mode");
        textCentered(c, "Experimental. The bot stays online when you leave.", w / 2f, y + gap * 5 + 2 * gui, 0xFFA0A0A0, Math.max(1, gui * 0.75f));
    }

    /** The game's button (gui/sprites/widget/button.png, nine-sliced so it stretches cleanly). */
    private void menuButton(Canvas c, String id, float x, float y, float bw, float bh, String label) {
        Bitmap b = sprite(pressed.containsValue(id) ? "widget/button_highlighted" : "widget/button");
        RectF rr = new RectF(x, y, x + bw, y + bh);
        if (b != null) nineSlice(c, b, rr, 3);
        else {
            ui.setColor(0xFF6F6F6F);
            c.drawRect(rr, ui);
        }
        float s = gui;
        String l = label;
        while (textWidth(l, s) > bw - 8 * gui && l.length() > 3) l = l.substring(0, l.length() - 1);
        textCentered(c, l, rr.centerX(), rr.centerY() - 4 * s, 0xFFFFFFFF, s);
        buttons.add(new Object[]{rr, id});
    }

    private void nineSlice(Canvas c, Bitmap b, RectF d, int border) {
        int bw = b.getWidth(), bh = b.getHeight();
        float e = border * gui;
        int[] sx = {0, border, bw - border, bw}, sy = {0, border, bh - border, bh};
        float[] dx = {d.left, d.left + e, d.right - e, d.right}, dy = {d.top, d.top + e, d.bottom - e, d.bottom};
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                c.drawBitmap(b, new Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]), new RectF(dx[i], dy[j], dx[i + 1], dy[j + 1]), pix);
    }

    private void drawDeath(Canvas c, int w, int h) {
        c.drawColor(0x80600000);
        float big = gui * 2;
        textCentered(c, "You Died!", w / 2f, h * 0.28f, 0xFFFFFFFF, big);
        textCentered(c, "Auto Respawn brings you back in a moment", w / 2f, h * 0.28f + 24 * gui, 0xFFFFFFFF, gui);
        menuButton(c, "leave", (w - 200 * gui) / 2f, h * 0.55f, 200 * gui, 20 * gui, "Leave Play mode");
    }

    // ================================================================== touch

    private final Map<Integer, String> pressed = new HashMap<Integer, String>(); // pointer -> button id
    private int stickId = -1, lookId = -1;
    private float stickX, stickY, kx, ky, lookX, lookY, lookDownX, lookDownY;
    private long lookDownAt;
    private boolean holding, sneak, sprinting;
    private int[] holdTarget;
    private long lastHit;

    private String hit(float x, float y) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Object[] b = buttons.get(i);
            if (((RectF) b[0]).contains(x, y)) return (String) b[1];
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        int i = e.getActionIndex();
        int id = e.getPointerId(i);
        Bot b = Hub.bot;
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(i), y = e.getY(i);
                String btn = hit(x, y);
                boolean menu = screen != NONE || Inv.openId >= 0 || Hub.statHealth <= 0;
                if (btn != null) {
                    pressed.put(id, btn);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    press(btn, true, b);
                } else if (menu) {
                    // a tap outside the menu does nothing
                } else if (x < getWidth() * 0.4f && y > getHeight() * 0.25f && stickId < 0) {
                    stickId = id;
                    stickX = x;
                    stickY = y;
                    kx = ky = 0;
                } else if (lookId < 0) {
                    lookId = id;
                    lookX = lookDownX = x;
                    lookY = lookDownY = y;
                    lookDownAt = System.currentTimeMillis();
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < e.getPointerCount(); p++) {
                    int pid = e.getPointerId(p);
                    float x = e.getX(p), y = e.getY(p);
                    if (pid == stickId) {
                        float rr = 56 * dp;
                        kx = (x - stickX) / rr;
                        ky = (y - stickY) / rr;
                        float len = (float) Math.sqrt(kx * kx + ky * ky);
                        if (len > 1) {
                            kx /= len;
                            ky /= len;
                        }
                        sprinting = -ky > 0.92f && !sneak; // push all the way forward to sprint
                        if (b != null) {
                            b.inForward = Math.abs(ky) < 0.2f ? 0 : -ky;
                            b.inStrafe = Math.abs(kx) < 0.2f ? 0 : -kx;
                            b.inSprint = sprinting;
                        }
                    } else if (pid == lookId) {
                        float k = 0.33f / dp * sensitivity / 100f; // degrees per pixel
                        r.yaw += (x - lookX) * k;
                        r.pitch = Math.max(-90, Math.min(90, r.pitch + (y - lookY) * k));
                        lookX = x;
                        lookY = y;
                        if (b != null) {
                            b.lookYaw = r.yaw;
                            b.lookPitch = r.pitch;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                String btn = pressed.remove(id);
                if (btn != null) press(btn, false, b);
                if (id == stickId) {
                    stickId = -1;
                    kx = ky = 0;
                    sprinting = false;
                    if (b != null) {
                        b.inForward = b.inStrafe = 0;
                        b.inSprint = false;
                    }
                }
                if (id == lookId) {
                    lookId = -1;
                    boolean still = Math.abs(e.getX(i) - lookDownX) + Math.abs(e.getY(i) - lookDownY) < 14 * dp;
                    if (holding) stopHold(b);
                    else if (still && System.currentTimeMillis() - lookDownAt < 300 && act != MotionEvent.ACTION_CANCEL) tap(b);
                }
                break;
            }
        }
        return true;
    }

    /** Hold detection and hold-to-break, run every frame. */
    private void tickHold() {
        Bot b = Hub.bot;
        if (b == null) return;
        if (lookId >= 0 && !holding && System.currentTimeMillis() - lookDownAt >= 300
                && Math.abs(lookX - lookDownX) + Math.abs(lookY - lookDownY) < 14 * dp) {
            holding = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (!holding) return;
        World.Dot e = r.targetEntity;
        long now = System.currentTimeMillis();
        if (e != null) {
            if (b.isMining()) b.stopMining(true);
            if (now - lastHit > 450) { // keep hitting while held (about the attack cooldown)
                lastHit = now;
                b.attack(e);
            }
            return;
        }
        int[] t = r.targetBlock;
        if (t == null) return;
        boolean same = holdTarget != null && holdTarget[0] == t[0] && holdTarget[1] == t[1] && holdTarget[2] == t[2];
        if (!same || !b.isMining()) {
            if (same && !b.isMining() && MapData.stateAt(t[0], t[1], t[2]) <= 0) return; // just broke it
            holdTarget = t.clone();
            b.startMining(t);
        }
    }

    private void stopHold(Bot b) {
        holding = false;
        holdTarget = null;
        if (b != null && b.isMining()) b.stopMining(true);
    }

    /** A short tap: hit the mob you're looking at, or use/place on the block, or use the held item. */
    private void tap(Bot b) {
        if (b == null) return;
        World.Dot e = r.targetEntity;
        if (e != null) {
            b.attack(e);
            return;
        }
        int[] t = r.targetBlock;
        if (t != null) b.useBlock(t[0], t[1], t[2], t[3]);
        else b.useItem();
    }

    private void press(String id, boolean down, Bot b) {
        if (id.equals("jump")) {
            if (b != null) b.inJump = down;
            return;
        }
        if (!down) return;
        if (id.startsWith("slot")) {
            if (b != null) b.selectSlot(Integer.parseInt(id.substring(4)));
        } else if (id.equals("sneak")) {
            sneak = !sneak;
            r.sneaking = sneak;
            if (b != null) {
                b.inSneak = sneak;
                if (sneak) b.inSprint = false;
            }
        } else if (id.equals("inventory")) {
            screen = screen == INVENTORY ? NONE : INVENTORY;
        } else if (id.equals("pause")) {
            screen = PAUSE;
        } else if (id.equals("chat") || id.equals("chatbtn")) {
            screen = NONE;
            host.openChat();
        } else if (id.equals("close")) {
            if (Inv.openId >= 0 && b != null) b.closeContainer();
            screen = NONE;
        } else if (id.equals("takeall")) {
            if (b != null) for (int i = 0; i < Inv.containerSize() && i < Inv.slots.size(); i++) if (Inv.slots.get(i) != null) b.clickSlot(i, 0, 1);
        } else if (id.startsWith("con")) {
            if (b != null) b.clickSlot(Integer.parseInt(id.substring(3)), 0, 1); // shift-click: moves the stack across
        } else if (id.startsWith("armor")) {
            // armour and off-hand are shown; moving them comes later
        } else if (id.startsWith("inv")) {
            int slot = Integer.parseInt(id.substring(3));
            if (b == null) return;
            if (slot >= 36) b.selectSlot(slot - 36);
            else b.clickInventory(slot, Math.max(0, Math.min(8, Inv.held)), 2); // swap into the selected hotbar slot
        } else if (id.equals("resume")) {
            screen = NONE;
        } else if (id.equals("rd")) {
            renderDistance = renderDistance >= 12 ? 2 : renderDistance + 2;
            saveSettings();
        } else if (id.equals("fov")) {
            fov = fov >= 110 ? 50 : fov + 10;
            saveSettings();
        } else if (id.equals("sens")) {
            sensitivity = sensitivity >= 200 ? 50 : sensitivity + 25;
            saveSettings();
        } else if (id.equals("coords")) {
            showCoords = !showCoords;
            saveSettings();
        } else if (id.equals("leave")) {
            host.leave();
        }
    }
}
