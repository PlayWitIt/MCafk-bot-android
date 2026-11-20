package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Top-down radar, north up. Draws only entity dots from the bot's tracked list (no terrain),
 * so it costs almost nothing. Pinch to zoom, tap to cycle the range.
 */
public class RadarView extends View {
    static final int C_PLAYER = 0xFFF472B6, C_FRIEND = 0xFF60A5FA, C_HOSTILE = 0xFFF87171, C_ANIMAL = 0xFF34D399,
            C_ITEM = 0xFFFBBF24, C_OTHER = 0xFF8A93A6, C_SELF = 0xFF8B5CF6;
    private static final float[] STEPS = {16, 32, 64, 128};

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ScaleGestureDetector scaler;
    private float range = 32;
    private boolean compact;
    boolean showPlayers = true, showHostile = true, showAnimals = true, showItems = true, showOther = false;
    private boolean scaling;
    Runnable onRangeChanged;

    // terrain image (1 pixel per block), rebuilt only when chunks or our block position change
    private Bitmap terrain;
    private int[] pixels;
    private int builtVersion = -1, builtX, builtZ, builtR;
    private final Paint bmp = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    public RadarView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * d);
        label.setColor(0xFFE8EAF0);
        label.setTextSize(11 * d);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        label.setShadowLayer(3 * d, 0, 0, Color.BLACK);
        small.setColor(0xFF8A93A6);
        small.setTextSize(9.5f * d);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                range = Math.max(8, Math.min(256, range / g.getScaleFactor()));
                scaling = true;
                invalidate();
                if (onRangeChanged != null) onRangeChanged.run();
                return true;
            }
        });
    }

    void setCompact(boolean c) {
        compact = c;
    }

    float getRange() {
        return range;
    }

    void setRange(float r) {
        range = r;
        invalidate();
        if (onRangeChanged != null) onRangeChanged.run();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, compact ? Math.round(size * 0.62f) : size);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (compact) return super.onTouchEvent(e);
        scaler.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) scaling = false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && !scaling && e.getPointerCount() == 1) {
            int i = 0;
            while (i < STEPS.length && STEPS[i] <= range + 0.5f) i++;
            setRange(i >= STEPS.length ? STEPS[0] : STEPS[i]);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - 6 * d;

        bg.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.7f, 0xFF1B2030, 0xFF0B0D12, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, 18 * d, 18 * d, bg);

        Bot.Config mc = Hub.config;
        boolean map = World.inWorld && MapData.supported && MapData.chunkCount() > 0 && (mc == null || mc.minimap);
        if (map) {
            c.save();
            path.reset();
            path.addRoundRect(new RectF(0, 0, w, h), 18 * d, 18 * d, Path.Direction.CW);
            c.clipPath(path);
            drawTerrain(c, cx, cy, Math.max(w, h) / 2f, r / range);
            c.restore();
        }

        // rings + crosshair (faint over the map)
        for (int i = 1; i <= 4; i++) {
            if (map && i < 4) continue;
            ring.setColor(map ? 0x44FFFFFF : i == 4 ? 0x553B4257 : 0x332B3145);
            c.drawCircle(cx, cy, r * i / 4f, ring);
        }
        if (!map) {
            ring.setColor(0x222B3145);
            c.drawLine(cx - r, cy, cx + r, cy, ring);
            c.drawLine(cx, cy - r, cx, cy + r, ring);
        }
        if (!compact && !map) {
            for (int i = 1; i <= 4; i++) {
                String t = String.format(Locale.US, "%.0f", range * i / 4f);
                c.drawText(t, cx + 3 * d, cy - r * i / 4f + 11 * d, small);
            }
        }
        // compass letters
        small.setColor(0xFFB4BCD0);
        c.drawText("N", cx - 3 * d, cy - r + 12 * d, small);
        small.setColor(0xFF8A93A6);

        if (!World.inWorld) {
            label.setTextAlign(Paint.Align.CENTER);
            c.drawText("Not in a world yet", cx, cy + r / 2, label);
            label.setTextAlign(Paint.Align.LEFT);
            drawSelf(c, cx, cy, d);
            return;
        }

        Bot.Config cfg = Hub.config;
        World.Friends friends = cfg != null ? cfg.friendSet() : null;
        List<World.Dot> dots = World.snapshot(friends);
        float scale = r / range;
        int drawn = 0;
        // far-to-near so close things draw on top
        for (int i = dots.size() - 1; i >= 0; i--) {
            World.Dot e = dots.get(i);
            if (!visible(e)) continue;
            if (++drawn > 500) break;
            float px = (float) e.dx * scale, pz = (float) e.dz * scale;
            float len = (float) Math.sqrt(px * px + pz * pz);
            boolean edge = len > r;
            if (edge && e.kind != 'p') continue;
            if (edge) {
                px = px / len * (r - 4 * d);
                pz = pz / len * (r - 4 * d);
            }
            float x = cx + px, y = cy + pz;
            int col = color(e);
            dot.setColor(col);
            if (e.kind == 'p') {
                if (edge) {
                    c.drawCircle(x, y, 3.5f * d, dot);
                } else {
                    dot.setColor(col & 0x40FFFFFF);
                    c.drawCircle(x, y, 9 * d, dot);
                    dot.setColor(col);
                    c.drawCircle(x, y, 5 * d, dot);
                }
                String t = e.label + (Math.abs(e.dy) > 4 ? (e.dy > 0 ? " ▲" : " ▼") : "");
                if (!compact || !edge) {
                    label.setColor(col);
                    c.drawText(t, x + 8 * d, y + 4 * d, label);
                    if (!compact) c.drawText(String.format(Locale.US, "%.0fm", e.dist), x + 8 * d, y + 15 * d, small);
                }
            } else {
                float rad = e.kind == 'i' || e.kind == 'o' ? 2f * d : 3f * d;
                c.drawCircle(x, y, rad, dot);
            }
        }
        drawSelf(c, cx, cy, d);
    }

    /** Draws the terrain around us; halfWidth = half the view size in pixels, scale = pixels per block. */
    private void drawTerrain(Canvas c, float cx, float cy, float halfWidth, float scale) {
        int R = (int) Math.ceil(halfWidth / scale) + 1;
        R = Math.min(R, 300);
        int size = R * 2;
        int bx = (int) Math.floor(World.selfX), bz = (int) Math.floor(World.selfZ);
        if (terrain == null || terrain.getWidth() != size) {
            terrain = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            pixels = new int[size * size];
            builtVersion = -1;
        }
        int ver = MapData.version;
        if (ver != builtVersion || bx != builtX || bz != builtZ || R != builtR) {
            int x0 = bx - R, z0 = bz - R;
            for (int j = 0; j < size; j++) {
                int wz = z0 + j;
                int row = j * size;
                for (int i = 0; i < size; i++) {
                    int wx = x0 + i;
                    int col = MapData.colorAt(wx, wz);
                    if (col == 0) {
                        pixels[row + i] = 0xFF0E1117;
                        continue;
                    }
                    int hh = MapData.heightAt(wx, wz), hn = MapData.heightAt(wx, wz - 1);
                    float f = hn == Integer.MIN_VALUE ? 1f : hh > hn ? 1.12f : hh < hn ? 0.80f : 0.95f;
                    int rr = Math.min(255, (int) (((col >> 16) & 255) * f));
                    int gg = Math.min(255, (int) (((col >> 8) & 255) * f));
                    int b2 = Math.min(255, (int) ((col & 255) * f));
                    pixels[row + i] = 0xFF000000 | rr << 16 | gg << 8 | b2;
                }
            }
            terrain.setPixels(pixels, 0, size, 0, 0, size, size);
            builtVersion = ver;
            builtX = bx;
            builtZ = bz;
            builtR = R;
        }
        // our exact position inside the block, so the map scrolls smoothly
        float fx = (float) (World.selfX - bx), fz = (float) (World.selfZ - bz);
        src.set(0, 0, size, size);
        float left = cx - (R + fx) * scale, top = cy - (R + fz) * scale;
        dst.set(left, top, left + size * scale, top + size * scale);
        bmp.setFilterBitmap(scale < 1.5f);
        c.drawBitmap(terrain, src, dst, bmp);
        // slight vignette so dots stay readable
        bg.setShader(new RadialGradient(cx, cy, halfWidth * 1.2f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), bg);
    }

    private void drawSelf(Canvas c, float cx, float cy, float d) {
        // heading arrow: Minecraft yaw 0 = south (+Z, screen down), 90 = west (-X)
        double yaw = Math.toRadians(World.selfYaw);
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
        float s = 9 * d;
        path.reset();
        path.moveTo(cx + fx * s, cy + fz * s);
        path.lineTo(cx - fx * s * 0.6f + -fz * s * 0.6f, cy - fz * s * 0.6f + fx * s * 0.6f);
        path.lineTo(cx - fx * s * 0.25f, cy - fz * s * 0.25f);
        path.lineTo(cx - fx * s * 0.6f + fz * s * 0.6f, cy - fz * s * 0.6f - fx * s * 0.6f);
        path.close();
        dot.setColor(0x558B5CF6);
        c.drawCircle(cx, cy, 13 * d, dot);
        dot.setColor(C_SELF);
        c.drawPath(path, dot);
    }

    boolean visible(World.Dot e) {
        switch (e.kind) {
            case 'p':
                return showPlayers;
            case 'h':
                return showHostile;
            case 'a':
                return showAnimals;
            case 'i':
                return showItems;
            default:
                return showOther;
        }
    }

    static int color(World.Dot e) {
        switch (e.kind) {
            case 'p':
                return e.friend ? C_FRIEND : C_PLAYER;
            case 'h':
                return C_HOSTILE;
            case 'a':
                return C_ANIMAL;
            case 'i':
                return C_ITEM;
            default:
                return C_OTHER;
        }
    }
}
