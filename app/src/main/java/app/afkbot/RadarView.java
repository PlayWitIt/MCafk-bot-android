package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Top-down minimap, north up: terrain (one pixel per block column) with entity dots on top.
 * Drag to pan, pinch to zoom, double-tap to re-centre on yourself.
 */
public class RadarView extends View {
    static final int C_PLAYER = 0xFFF472B6, C_FRIEND = 0xFF60A5FA, C_HOSTILE = 0xFFF87171, C_ANIMAL = 0xFF34D399,
            C_ITEM = 0xFFFBBF24, C_OTHER = 0xFF8A93A6, C_SELF = 0xFF8B5CF6;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float range = 32; // blocks from the centre to the nearest edge
    private float panX, panZ; // view centre offset from the player, in blocks
    private boolean compact;
    boolean showPlayers = true, showHostile = true, showAnimals = true, showItems = true, showOther = false;
    Runnable onRangeChanged;

    // terrain image (1 pixel per block), rebuilt only when chunks, layer or the centre block change
    private Bitmap terrain;
    private int[] pixels;
    private int builtVersion = -1, builtX, builtZ, builtR, builtLayer;
    private final Paint bmp = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    public RadarView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * d);
        label.setColor(0xFFE8EAF0);
        label.setTextSize(11.5f * d);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        label.setShadowLayer(3 * d, 0, 0, Color.BLACK);
        small.setColor(0xFFE8EAF0);
        small.setTextSize(10f * d);
        small.setShadowLayer(2 * d, 0, 0, Color.BLACK);
        chip.setColor(0xB30B0D12);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector g) {
                setRange(range / g.getScaleFactor());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (scaler.isInProgress()) return false;
                float ppb = pixelsPerBlock();
                panX += dx / ppb;
                panZ += dy / ppb;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                recenter();
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
        range = Math.max(6, Math.min(256, r));
        invalidate();
        if (onRangeChanged != null) onRangeChanged.run();
    }

    void zoom(float factor) {
        setRange(range * factor);
    }

    void recenter() {
        panX = panZ = 0;
        invalidate();
    }

    boolean isPanned() {
        return Math.abs(panX) > 0.5f || Math.abs(panZ) > 0.5f;
    }

    private float pixelsPerBlock() {
        float d = getResources().getDisplayMetrics().density;
        return (Math.min(getWidth(), getHeight()) / 2f - 6 * d) / range;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        if (compact) {
            setMeasuredDimension(width, Math.round(width * 0.62f));
        } else {
            int height = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? width : MeasureSpec.getSize(h);
            setMeasuredDimension(width, height);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (compact) return super.onTouchEvent(e);
        // keep the page from stealing drags/pinches
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - 6 * d;
        float scale = r / range;
        float radius = 18 * d;

        bg.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.7f, 0xFF1B2030, 0xFF0B0D12, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, w, h, radius, radius, bg);

        Bot.Config mc = Hub.config;
        boolean map = World.inWorld && MapData.supported && MapData.chunkCount() > 0 && (mc == null || mc.minimap);
        c.save();
        path.reset();
        path.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        c.clipPath(path);
        if (map) drawTerrain(c, cx, cy, w, h, scale);

        // where the player is on screen (moves away from the centre when panned)
        float sx = cx - panX * scale, sy = cy - panZ * scale;

        // distance rings around the player
        ring.setColor(map ? 0x33FFFFFF : 0x333B4257);
        for (int i = 1; i <= 2; i++) c.drawCircle(sx, sy, r * i / 2f, ring);

        if (!World.inWorld) {
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(0xFFE8EAF0);
            c.drawText("Not in a world yet", cx, cy + r / 2, label);
            label.setTextAlign(Paint.Align.LEFT);
            drawSelf(c, sx, sy, d);
            c.restore();
            return;
        }

        World.Friends friends = mc != null ? mc.friendSet() : null;
        List<World.Dot> dots = World.snapshot(friends);
        int drawn = 0;
        for (int i = dots.size() - 1; i >= 0; i--) { // far-to-near so close things draw on top
            World.Dot e = dots.get(i);
            if (!visible(e)) continue;
            if (++drawn > 500) break;
            float x = sx + (float) e.dx * scale, y = sy + (float) e.dz * scale;
            boolean edge = x < 0 || y < 0 || x > w || y > h;
            if (edge && e.kind != 'p') continue;
            if (edge) { // clamp off-screen players to the border so you know where they are
                x = Math.max(8 * d, Math.min(w - 8 * d, x));
                y = Math.max(8 * d, Math.min(h - 8 * d, y));
            }
            int col = color(e);
            dot.setColor(col);
            if (e.kind == 'p') {
                if (!edge) {
                    dot.setColor(col & 0x55FFFFFF);
                    c.drawCircle(x, y, 9 * d, dot);
                    dot.setColor(col);
                }
                c.drawCircle(x, y, edge ? 4 * d : 5 * d, dot);
                dot.setColor(Color.WHITE);
                c.drawCircle(x, y, 1.6f * d, dot);
                if (!compact || !edge) {
                    String t = e.label + (Math.abs(e.dy) > 4 ? (e.dy > 0 ? " ▲" : " ▼") : "");
                    float tx = Math.min(x + 9 * d, w - label.measureText(t) - 4 * d);
                    label.setColor(col);
                    c.drawText(t, tx, y + 4 * d, label);
                    if (!compact) c.drawText(String.format(Locale.US, "%.0fm", e.dist), tx, y + 16 * d, small);
                }
            } else {
                float rad = e.kind == 'i' || e.kind == 'o' ? 2.2f * d : 3.4f * d;
                dot.setColor(0xAA000000);
                c.drawCircle(x, y, rad + 1.2f * d, dot);
                dot.setColor(col);
                c.drawCircle(x, y, rad, dot);
            }
        }
        drawSelf(c, sx, sy, d);

        // compass + scale overlay
        if (!compact) {
            String n = "N ↑";
            c.drawRoundRect(cx - 18 * d, 8 * d, cx + 18 * d, 28 * d, 10 * d, 10 * d, chip);
            small.setTextAlign(Paint.Align.CENTER);
            c.drawText(n, cx, 22 * d, small);
            String sc = String.format(Locale.US, "%.0f blocks", range);
            float tw = small.measureText(sc) + 16 * d;
            c.drawRoundRect(8 * d, h - 28 * d, 8 * d + tw, h - 8 * d, 10 * d, 10 * d, chip);
            c.drawText(sc, 8 * d + tw / 2, h - 14 * d, small);
            int ly = MapData.layerY;
            if (ly != Integer.MIN_VALUE && map) {
                String lt = "Layer Y " + ly;
                float lw = small.measureText(lt) + 16 * d;
                c.drawRoundRect(w - 8 * d - lw, 8 * d, w - 8 * d, 28 * d, 10 * d, 10 * d, chip);
                c.drawText(lt, w - 8 * d - lw / 2, 22 * d, small);
            }
            small.setTextAlign(Paint.Align.LEFT);
        }
        c.restore();
    }

    /** Draws the terrain image so that block (selfX+panX, selfZ+panZ) is at the view centre. */
    private void drawTerrain(Canvas c, float cx, float cy, float w, float h, float scale) {
        int R = (int) Math.ceil(Math.max(w, h) / 2f / scale) + 2;
        R = Math.min(R, 320);
        int size = R * 2;
        double ox = World.selfX + panX, oz = World.selfZ + panZ;
        int bx = (int) Math.floor(ox), bz = (int) Math.floor(oz);
        if (terrain == null || terrain.getWidth() != size) {
            terrain = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            pixels = new int[size * size];
            builtVersion = -1;
        }
        int ver = MapData.version, layer = MapData.layerY;
        if (ver != builtVersion || bx != builtX || bz != builtZ || R != builtR || layer != builtLayer) {
            int x0 = bx - R, z0 = bz - R;
            for (int j = 0; j < size; j++) {
                int wz = z0 + j, row = j * size;
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
            builtLayer = layer;
        }
        float fx = (float) (ox - bx), fz = (float) (oz - bz);
        src.set(0, 0, size, size);
        float left = cx - (R + fx) * scale, top = cy - (R + fz) * scale;
        dst.set(left, top, left + size * scale, top + size * scale);
        bmp.setFilterBitmap(scale < 1.5f);
        c.drawBitmap(terrain, src, dst, bmp);
        bg.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.75f, 0x00000000, 0x55000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
    }

    private void drawSelf(Canvas c, float cx, float cy, float d) {
        // heading arrow: Minecraft yaw 0 = south (+Z, screen down), 90 = west (-X)
        double yaw = Math.toRadians(World.selfYaw);
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
        float s = 10 * d;
        path.reset();
        path.moveTo(cx + fx * s, cy + fz * s);
        path.lineTo(cx - fx * s * 0.6f - fz * s * 0.6f, cy - fz * s * 0.6f + fx * s * 0.6f);
        path.lineTo(cx - fx * s * 0.25f, cy - fz * s * 0.25f);
        path.lineTo(cx - fx * s * 0.6f + fz * s * 0.6f, cy - fz * s * 0.6f - fx * s * 0.6f);
        path.close();
        dot.setColor(0x668B5CF6);
        c.drawCircle(cx, cy, 14 * d, dot);
        dot.setColor(Color.WHITE);
        c.drawCircle(cx, cy, 2 * d, dot);
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
