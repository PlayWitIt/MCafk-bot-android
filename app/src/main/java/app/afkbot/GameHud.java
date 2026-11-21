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
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Play screen's HUD and touch controls, drawn with the game's own sprites at the game's GUI scale: crosshair,
 * hotbar with item icons, hearts, hunger, XP, fading chat, and (on top) the inventory/container screens, pause menu
 * and death screen. Controls work like the touch edition: a floating stick on the left moves (push all the way to
 * sprint), dragging anywhere else looks around, a tap uses/places (or hits a mob), holding breaks.
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
    private GameIcons icons;
    private final Map<String, Bitmap> sprites = new HashMap<String, Bitmap>();
    private final Paint pix = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), ui = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        showCoords = prefs.getBoolean("coords", true);
        pix.setFilterBitmap(false);
        pix.setAntiAlias(false);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setShadowLayer(1.5f * dp, dp, dp, 0xFF3F3F3F);
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

    // ================================================================== drawing

    private int gui = 2; // vanilla GUI scale: the largest that still fits 320x240
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
        if (a == null || !World.inWorld) {
            c.drawColor(0xFF1B1410);
            text.setTextSize(9 * gui);
            text.setTextAlign(Paint.Align.CENTER);
            String s = !World.inWorld ? (Hub.running ? "Joining the server..." : "Not connected - start AFK first") : GameAssetStore.status;
            c.drawText(s, w / 2f, h / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            button(c, "leave", w - 96 * dp, 16 * dp, 80 * dp, 36 * dp, "Back");
            postInvalidateOnAnimation();
            return;
        }
        if (icons == null) icons = new GameIcons(a.files, a.atlas);
        tickHold();
        boolean survival = GameState.gameMode == 0 || GameState.gameMode == 2;
        drawCrosshair(c, w, h);
        float hotTop = drawHotbar(c, w, h);
        if (survival) hotTop = drawStatus(c, w, hotTop);
        drawChat(c, hotTop);
        drawTargetName(c, w);
        if (showCoords) drawDebug(c);
        drawControls(c, w, h);
        if (Hub.statHealth <= 0) drawDeath(c, w, h);
        else if (Inv.openId >= 0) drawContainer(c, w, h, a);
        else if (screen == INVENTORY) drawInventory(c, w, h, a);
        else if (screen == PAUSE) drawPause(c, w, h);
        postInvalidateOnAnimation();
    }

    private void drawCrosshair(Canvas c, int w, int h) {
        if (screen != NONE || Inv.openId >= 0) return;
        Bitmap b = sprite("hud/crosshair");
        float s = 15 * gui;
        if (b != null) c.drawBitmap(b, null, new RectF(w / 2f - s / 2, h / 2f - s / 2, w / 2f + s / 2, h / 2f + s / 2), pix);
    }

    /** Returns the top of the hotbar. */
    private float drawHotbar(Canvas c, int w, int h) {
        float bw = 182 * gui, bh = 22 * gui, x = (w - bw) / 2, y = h - bh - 2 * gui;
        Bitmap bar = sprite("hud/hotbar"), sel = sprite("hud/hotbar_selection");
        if (bar != null) c.drawBitmap(bar, null, new RectF(x, y, x + bw, y + bh), pix);
        int held = Math.max(0, Math.min(8, Inv.held));
        if (sel != null) c.drawBitmap(sel, null, new RectF(x - gui + held * 20 * gui, y - gui, x - gui + held * 20 * gui + 24 * gui, y - gui + 23 * gui), pix);
        List<Inv.Item> inv = Inv.inventory;
        for (int i = 0; i < 9; i++) {
            float sx = x + 3 * gui + i * 20 * gui, sy = y + 3 * gui;
            buttons.add(new Object[]{new RectF(sx - 2 * gui, y, sx + 18 * gui, y + bh), "slot" + i});
            Inv.Item it = 36 + i < inv.size() ? inv.get(36 + i) : null;
            if (it != null) drawItem(c, it, sx, sy, 16 * gui);
        }
        // inventory button right of the hotbar ("..." like the touch edition)
        float ix = x + bw + 4 * gui;
        button(c, "inventory", ix, y + 2 * gui, 20 * gui, bh - 4 * gui, "•••");
        return y;
    }

    private float drawStatus(Canvas c, int w, float hotTop) {
        float bw = 182 * gui, x = (w - bw) / 2;
        // XP bar and level
        float xy = hotTop - 6 * gui;
        Bitmap bg = sprite("hud/experience_bar_background"), pr = sprite("hud/experience_bar_progress");
        if (bg != null) c.drawBitmap(bg, null, new RectF(x, xy, x + bw, xy + 5 * gui), pix);
        float p = Math.max(0, Math.min(1, GameState.xpBar));
        if (pr != null && p > 0) {
            int sw = Math.max(1, Math.round(pr.getWidth() * p));
            c.drawBitmap(pr, new Rect(0, 0, sw, pr.getHeight()), new RectF(x, xy, x + bw * p, xy + 5 * gui), pix);
        }
        if (GameState.xpLevel > 0) {
            text.setTextSize(9 * gui);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFF80FF20);
            c.drawText(String.valueOf(GameState.xpLevel), w / 2f, xy - gui, text);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.LEFT);
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

    /** Chat like the game: the last lines fade out after ten seconds. */
    private void drawChat(Canvas c, float bottom) {
        List<Object[]> lines = Hub.recentChat(8);
        long now = System.currentTimeMillis();
        text.setTextSize(7 * gui);
        float lh = 9 * gui, y = bottom - 4 * gui;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String s = (String) lines.get(i)[0];
            long age = now - (Long) lines.get(i)[1];
            if (age > 10000) continue;
            float alpha = age < 8000 ? 1 : 1 - (age - 8000) / 2000f;
            float tw = Math.min(text.measureText(s), getWidth() * 0.55f);
            ui.setColor(Color.argb((int) (110 * alpha), 0, 0, 0));
            c.drawRect(2 * gui, y - lh + 2 * gui, 6 * gui + tw, y + 2 * gui, ui);
            text.setAlpha((int) (255 * alpha));
            String shown = s;
            while (text.measureText(shown) > tw && shown.length() > 4) shown = shown.substring(0, shown.length() - 2);
            c.drawText(shown.equals(s) ? s : shown + "…", 4 * gui, y, text);
            y -= lh;
        }
        text.setAlpha(255);
    }

    /** What you're looking at, top centre (and how far mining has got). */
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
        text.setTextSize(8 * gui);
        float tw = text.measureText(s);
        ui.setColor(0x88000000);
        c.drawRoundRect(new RectF(w / 2f - tw / 2 - 4 * gui, 4 * gui, w / 2f + tw / 2 + 4 * gui, 16 * gui), 3 * gui, 3 * gui, ui);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, w / 2f, 13 * gui, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDebug(Canvas c) {
        text.setTextSize(6 * gui);
        float y = 8 * gui;
        double[] cam = r.camera();
        String[] dirs = {"south", "west", "north", "east"};
        int d = Math.round(((r.yaw % 360) + 360) % 360 / 90f) & 3;
        for (String s : new String[]{fps + " fps", String.format(Locale.US, "XYZ %.1f / %.1f / %.1f", cam[0], cam[1] - 1.62, cam[2]), "Facing " + dirs[d]}) {
            c.drawText(s, 3 * gui, y, text);
            y += 8 * gui;
        }
    }

    private void drawControls(Canvas c, int w, int h) {
        if (screen != NONE || Inv.openId >= 0) return;
        float s = 56 * dp, m = 18 * dp;
        button(c, "pause", w - m - 44 * dp, m, 44 * dp, 44 * dp, "II");
        button(c, "chat", w - m - 96 * dp, m, 44 * dp, 44 * dp, "T");
        button(c, "jump", w - m - s * 1.25f, h - m - s * 1.25f - 30 * dp, s * 1.25f, s * 1.25f, "▲");
        button(c, "sneak", w - m - s * 2.55f, h - m - s - 30 * dp, s, s, sneak ? "▼!" : "▼");
        // the floating stick while a thumb is on it
        if (stickId >= 0) {
            ui.setColor(0x44FFFFFF);
            c.drawCircle(stickX, stickY, 56 * dp, ui);
            ui.setColor(0xAAFFFFFF);
            c.drawCircle(stickX + kx * 56 * dp, stickY + ky * 56 * dp, 24 * dp, ui);
        } else {
            ui.setColor(0x22FFFFFF);
            c.drawCircle(m + 76 * dp, h - m - 76 * dp - 20 * dp, 56 * dp, ui); // hint where the stick lives
        }
    }

    /** A translucent touch button (also used inside menus). */
    private void button(Canvas c, String id, float x, float y, float bw, float bh, String label) {
        RectF rr = new RectF(x, y, x + bw, y + bh);
        boolean down = pressed.containsValue(id);
        ui.setColor(down ? 0x99FFFFFF : 0x55000000);
        c.drawRoundRect(rr, 8 * dp, 8 * dp, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(1.5f * dp);
        ui.setColor(0x99FFFFFF);
        c.drawRoundRect(rr, 8 * dp, 8 * dp, ui);
        ui.setStyle(Paint.Style.FILL);
        text.setTextSize(Math.min(bh * 0.42f, 16 * dp));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, rr.centerX(), rr.centerY() + text.getTextSize() * 0.35f, text);
        text.setTextAlign(Paint.Align.LEFT);
        buttons.add(new Object[]{rr, id});
    }

    // ---- item icons ----

    private final Paint shadePaint = new Paint();

    void drawItem(Canvas c, Inv.Item it, float x, float y, float size) {
        GameAssetStore.Loaded a = assets;
        if (a == null || Hub.versionData == null) return;
        GameIcons.Icon ic = icons.get(Hub.versionData.itemId(it.id));
        Bitmap atlas = a.atlasBitmap;
        if (ic.flat >= 0) {
            c.drawBitmap(atlas, tileRect(ic.flat), new RectF(x, y, x + size, y + size), pix);
        } else if (ic.top >= 0) {
            float s = size;
            // a small isometric cube like the game's inventory: top bright, left 80%, right 60%
            face(c, atlas, ic.top, new float[]{x + s / 2, y, x + s, y + s / 4, x + s / 2, y + s / 2, x, y + s / 4}, 1f, ic.tint);
            face(c, atlas, ic.front, new float[]{x, y + s / 4, x + s / 2, y + s / 2, x + s / 2, y + s, x, y + s * 3 / 4}, 0.8f, ic.tintSides ? ic.tint : 0);
            face(c, atlas, ic.side, new float[]{x + s / 2, y + s / 2, x + s, y + s / 4, x + s, y + s * 3 / 4, x + s / 2, y + s}, 0.6f, ic.tintSides ? ic.tint : 0);
        } else {
            text.setTextSize(size * 0.3f);
            c.drawText(it.name.length() > 4 ? it.name.substring(0, 4) : it.name, x, y + size * 0.6f, text);
        }
        if (it.count > 1) {
            text.setTextSize(8 * gui);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(it.count), x + size + gui, y + size + gui, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void face(Canvas c, Bitmap atlas, int tile, float[] dst, float shade, int tint) {
        if (tile < 0) return;
        Rect src = tileRect(tile);
        float[] s = {src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom};
        Matrix m = new Matrix();
        m.setPolyToPoly(s, 0, dst, 0, 4);
        int tr = tint != 0 ? (tint >> 16) & 255 : 255, tg = tint != 0 ? (tint >> 8) & 255 : 255, tb = tint != 0 ? tint & 255 : 255;
        shadePaint.setFilterBitmap(false);
        shadePaint.setColorFilter(new LightingColorFilter(Color.rgb((int) (tr * shade), (int) (tg * shade), (int) (tb * shade)), 0));
        c.save();
        c.concat(m);
        c.clipRect(src);
        c.drawBitmap(atlas, 0, 0, shadePaint);
        c.restore();
    }

    private static Rect tileRect(int tile) {
        int x = (tile % GameAtlas.GRID) * GameAtlas.TILE, y = (tile / GameAtlas.GRID) * GameAtlas.TILE;
        return new Rect(x, y, x + GameAtlas.TILE, y + GameAtlas.TILE);
    }

    // ================================================================== screens

    static final int NONE = 0, INVENTORY = 1, PAUSE = 2;
    int screen = NONE;

    private void dim(Canvas c) {
        c.drawColor(0xAA101010);
    }

    private float slotGrid(Canvas c, List<Inv.Item> items, int from, int count, float x, float y, String prefix, int idBase) {
        float s = 18 * gui;
        for (int i = 0; i < count; i++) {
            float sx = x + (i % 9) * s, sy = y + (i / 9) * s;
            ui.setColor(0xFF8B8B8B);
            c.drawRect(sx, sy, sx + s - gui, sy + s - gui, ui);
            ui.setColor(0xFF373737);
            c.drawRect(sx, sy, sx + s - gui, sy + gui, ui);
            c.drawRect(sx, sy, sx + gui, sy + s - gui, ui);
            int idx = from + i;
            Inv.Item it = idx < items.size() ? items.get(idx) : null;
            if (it != null) drawItem(c, it, sx + gui, sy + gui, 16 * gui);
            buttons.add(new Object[]{new RectF(sx, sy, sx + s, sy + s), prefix + (idBase + i)});
        }
        return y + ((count + 8) / 9) * s;
    }

    private void panel(Canvas c, float x, float y, float pw, float ph, String title) {
        ui.setColor(0xFFC6C6C6);
        c.drawRoundRect(new RectF(x, y, x + pw, y + ph), 3 * gui, 3 * gui, ui);
        text.setColor(0xFF404040);
        text.setShadowLayer(0, 0, 0, 0);
        text.setTextSize(8 * gui);
        c.drawText(title, x + 8 * gui, y + 12 * gui, text);
        text.setColor(Color.WHITE);
        text.setShadowLayer(1.5f * dp, dp, dp, 0xFF3F3F3F);
    }

    private void drawInventory(Canvas c, int w, int h, GameAssetStore.Loaded a) {
        dim(c);
        float pw = 176 * gui, ph = 104 * gui, x = (w - pw) / 2, y = (h - ph) / 2;
        panel(c, x, y, pw, ph, "Inventory  -  tap an item to put it in your selected hotbar slot");
        List<Inv.Item> inv = Inv.inventory;
        float gx = x + 8 * gui;
        float yy = slotGrid(c, inv, 9, 27, gx, y + 18 * gui, "inv", 9);
        slotGrid(c, inv, 36, 9, gx, yy + 4 * gui, "inv", 36);
        button(c, "close", x + pw - 26 * dp, y + 6 * dp, 20 * dp, 20 * dp, "✕");
    }

    private void drawContainer(Canvas c, int w, int h, GameAssetStore.Loaded a) {
        dim(c);
        List<Inv.Item> s = Inv.slots;
        int n = Inv.containerSize();
        int rows = (n + 8) / 9;
        float pw = 176 * gui, ph = (rows * 18 + 100) * gui, x = (w - pw) / 2, y = Math.max(4 * gui, (h - ph) / 2);
        String title = Inv.title.isEmpty() ? "Container" : Inv.title.replace("container.", "");
        panel(c, x, y, pw, ph, title + "  -  tap to move");
        float gx = x + 8 * gui;
        float yy = slotGrid(c, s, 0, n, gx, y + 18 * gui, "con", 0);
        yy = slotGrid(c, s, n, 27, gx, yy + 10 * gui, "con", n);
        slotGrid(c, s, n + 27, 9, gx, yy + 4 * gui, "con", n + 27);
        button(c, "close", x + pw - 26 * dp, y + 6 * dp, 20 * dp, 20 * dp, "✕");
        button(c, "takeall", x + pw - 100 * dp, y + 6 * dp, 68 * dp, 20 * dp, "Take all");
    }

    private void drawPause(Canvas c, int w, int h) {
        dim(c);
        text.setTextSize(10 * gui);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("Game Menu (experimental Play mode)", w / 2f, h * 0.16f, text);
        text.setTextAlign(Paint.Align.LEFT);
        float bw = Math.min(w * 0.6f, 200 * gui), bh = 20 * gui, x = (w - bw) / 2, y = h * 0.22f, gap = 24 * gui;
        menuButton(c, "resume", x, y, bw, bh, "Back to Game");
        menuButton(c, "rd", x, y + gap, bw, bh, "Render distance: " + renderDistance + " chunks");
        menuButton(c, "fov", x, y + gap * 2, bw, bh, "FOV: " + fov);
        menuButton(c, "sens", x, y + gap * 3, bw, bh, "Sensitivity: " + sensitivity + "%");
        menuButton(c, "coords", x, y + gap * 4, bw, bh, "Coordinates & FPS: " + (showCoords ? "ON" : "OFF"));
        menuButton(c, "leave", x, y + gap * 5, bw, bh, "Leave Play mode (the bot stays online)");
    }

    private void menuButton(Canvas c, String id, float x, float y, float bw, float bh, String label) {
        RectF rr = new RectF(x, y, x + bw, y + bh);
        ui.setColor(pressed.containsValue(id) ? 0xFF8C8CC0 : 0xFF6F6F6F);
        c.drawRect(rr, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(gui);
        ui.setColor(0xFF000000);
        c.drawRect(rr, ui);
        ui.setStyle(Paint.Style.FILL);
        text.setTextSize(8 * gui);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, rr.centerX(), rr.centerY() + 3 * gui, text);
        text.setTextAlign(Paint.Align.LEFT);
        buttons.add(new Object[]{rr, id});
    }

    private void drawDeath(Canvas c, int w, int h) {
        c.drawColor(0x88A00000);
        text.setTextSize(20 * gui);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("You Died!", w / 2f, h * 0.38f, text);
        text.setTextSize(8 * gui);
        c.drawText("Respawning (Auto Respawn) - or leave Play mode", w / 2f, h * 0.5f, text);
        text.setTextAlign(Paint.Align.LEFT);
        menuButton(c, "leave", (w - 160 * gui) / 2f, h * 0.6f, 160 * gui, 20 * gui, "Leave Play mode");
    }

    // ================================================================== touch

    private final Map<Integer, String> pressed = new HashMap<Integer, String>(); // pointer -> button id
    private int stickId = -1, lookId = -1;
    private float stickX, stickY, kx, ky, lookX, lookY, lookDownX, lookDownY;
    private long lookDownAt;
    private boolean holding, sneak;
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
                if (btn != null) {
                    pressed.put(id, btn);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    press(btn, true, b);
                } else if (screen != NONE || Inv.openId >= 0 || Hub.statHealth <= 0) {
                    // a tap outside a menu does nothing
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
                        float r = 56 * dp;
                        kx = (x - stickX) / r;
                        ky = (y - stickY) / r;
                        float len = (float) Math.sqrt(kx * kx + ky * ky);
                        if (len > 1) {
                            kx /= len;
                            ky /= len;
                        }
                        if (b != null) {
                            b.inForward = Math.abs(ky) < 0.2f ? 0 : -ky;
                            b.inStrafe = Math.abs(kx) < 0.2f ? 0 : -kx;
                            b.inSprint = -ky > 0.92f && !sneak; // push all the way forward to sprint
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
        } else if (id.equals("chat")) {
            host.openChat();
        } else if (id.equals("close")) {
            if (Inv.openId >= 0 && b != null) b.closeContainer();
            screen = NONE;
        } else if (id.equals("takeall")) {
            if (b != null) for (int i = 0; i < Inv.containerSize() && i < Inv.slots.size(); i++) if (Inv.slots.get(i) != null) b.clickSlot(i, 0, 1);
        } else if (id.startsWith("con")) {
            if (b != null) b.clickSlot(Integer.parseInt(id.substring(3)), 0, 1); // shift-click: moves the stack across
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
