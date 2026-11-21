package app.afkbot;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Small line icons drawn with paths, so they look the same on every phone (unicode symbols like ⛶ or ⚇ are missing
 * from many Android fonts and show up as empty boxes or colour emoji). Drawn on a 24x24 grid, scaled to the bounds.
 */
final class Icons extends Drawable {
    static final int HOME = 0, MAP = 1, CUBE = 2, MODULES = 3, CHAT = 4, ACCOUNT = 5, FULLSCREEN = 6,
            EXIT_FULLSCREEN = 7, RECENTER = 8, PLUS = 9, MINUS = 10, CLOSE = 11, HAND = 12, SEND = 13, EYE = 14;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Icons(int kind, int color) {
        this.kind = kind;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) {
        p.setColor(c);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        p.setStrokeWidth(2f);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case HOME: // house
                path.moveTo(3.5f, 11f);
                path.lineTo(12f, 3.8f);
                path.lineTo(20.5f, 11f);
                path.moveTo(5.5f, 9.5f);
                path.lineTo(5.5f, 20f);
                path.lineTo(18.5f, 20f);
                path.lineTo(18.5f, 9.5f);
                path.moveTo(10f, 20f);
                path.lineTo(10f, 14.5f);
                path.lineTo(14f, 14.5f);
                path.lineTo(14f, 20f);
                break;
            case MAP: // folded map
                path.moveTo(3.5f, 6f);
                path.lineTo(9f, 4f);
                path.lineTo(15f, 6f);
                path.lineTo(20.5f, 4f);
                path.lineTo(20.5f, 18f);
                path.lineTo(15f, 20f);
                path.lineTo(9f, 18f);
                path.lineTo(3.5f, 20f);
                path.close();
                path.moveTo(9f, 4f);
                path.lineTo(9f, 18f);
                path.moveTo(15f, 6f);
                path.lineTo(15f, 20f);
                break;
            case CUBE: // isometric block
                path.moveTo(12f, 3f);
                path.lineTo(20f, 7.5f);
                path.lineTo(20f, 16.5f);
                path.lineTo(12f, 21f);
                path.lineTo(4f, 16.5f);
                path.lineTo(4f, 7.5f);
                path.close();
                path.moveTo(4f, 7.5f);
                path.lineTo(12f, 12f);
                path.lineTo(20f, 7.5f);
                path.moveTo(12f, 12f);
                path.lineTo(12f, 21f);
                break;
            case MODULES: // sliders
                path.moveTo(4f, 7f);
                path.lineTo(20f, 7f);
                path.moveTo(4f, 12f);
                path.lineTo(20f, 12f);
                path.moveTo(4f, 17f);
                path.lineTo(20f, 17f);
                c.drawPath(path, p);
                path.reset();
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(8.5f, 7f, 2.4f, p);
                c.drawCircle(15.5f, 12f, 2.4f, p);
                c.drawCircle(10f, 17f, 2.4f, p);
                break;
            case CHAT: // speech bubble
                path.addRoundRect(new RectF(3.5f, 4f, 20.5f, 16.5f), 4f, 4f, Path.Direction.CW);
                path.moveTo(8f, 16.5f);
                path.lineTo(7f, 20.5f);
                path.lineTo(12.5f, 16.5f);
                path.moveTo(8f, 9.2f);
                path.lineTo(16f, 9.2f);
                path.moveTo(8f, 12.2f);
                path.lineTo(13f, 12.2f);
                break;
            case ACCOUNT: // person
                path.addCircle(12f, 8.5f, 4f, Path.Direction.CW);
                path.moveTo(4.5f, 20.5f);
                path.cubicTo(5.5f, 15.5f, 18.5f, 15.5f, 19.5f, 20.5f);
                break;
            case FULLSCREEN: // four corners pointing out
                corners(false);
                break;
            case EXIT_FULLSCREEN:
                corners(true);
                break;
            case RECENTER: // crosshair
                path.addCircle(12f, 12f, 6.5f, Path.Direction.CW);
                path.moveTo(12f, 2.5f);
                path.lineTo(12f, 5.5f);
                path.moveTo(12f, 18.5f);
                path.lineTo(12f, 21.5f);
                path.moveTo(2.5f, 12f);
                path.lineTo(5.5f, 12f);
                path.moveTo(18.5f, 12f);
                path.lineTo(21.5f, 12f);
                c.drawPath(path, p);
                path.reset();
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 12f, 2.2f, p);
                break;
            case PLUS:
                path.moveTo(12f, 5f);
                path.lineTo(12f, 19f);
                path.moveTo(5f, 12f);
                path.lineTo(19f, 12f);
                break;
            case MINUS:
                path.moveTo(5f, 12f);
                path.lineTo(19f, 12f);
                break;
            case CLOSE:
                path.moveTo(6f, 6f);
                path.lineTo(18f, 18f);
                path.moveTo(18f, 6f);
                path.lineTo(6f, 18f);
                break;
            case HAND: // pointer / use
                path.moveTo(9f, 12f);
                path.lineTo(9f, 4.5f);
                path.cubicTo(9f, 3f, 11.5f, 3f, 11.5f, 4.5f);
                path.lineTo(11.5f, 11f);
                path.moveTo(11.5f, 10f);
                path.cubicTo(11.5f, 8.5f, 14f, 8.5f, 14f, 10f);
                path.lineTo(14f, 11.5f);
                path.moveTo(14f, 10.5f);
                path.cubicTo(14f, 9f, 16.5f, 9f, 16.5f, 10.5f);
                path.lineTo(16.5f, 12f);
                path.moveTo(16.5f, 11.5f);
                path.cubicTo(16.5f, 10f, 19f, 10f, 19f, 11.5f);
                path.lineTo(19f, 15f);
                path.cubicTo(19f, 19f, 16.5f, 21f, 13.5f, 21f);
                path.cubicTo(10.5f, 21f, 9f, 19.5f, 7.5f, 17f);
                path.lineTo(5.2f, 13.2f);
                path.cubicTo(4.5f, 12f, 6.2f, 10.8f, 7.2f, 11.8f);
                path.lineTo(9f, 14f);
                break;
            case SEND: // paper plane
                path.moveTo(3.5f, 11.5f);
                path.lineTo(20.5f, 4f);
                path.lineTo(14f, 20.5f);
                path.lineTo(11f, 13f);
                path.close();
                path.moveTo(11f, 13f);
                path.lineTo(20.5f, 4f);
                break;
            case EYE: // an eye: Naughty or Nice's intel
                path.moveTo(2.5f, 12f);
                path.quadTo(12f, 2.5f, 21.5f, 12f);
                path.quadTo(12f, 21.5f, 2.5f, 12f);
                path.close();
                path.addCircle(12f, 12f, 3.2f, Path.Direction.CW);
                break;
            default:
                break;
        }
        c.drawPath(path, p);
        c.restore();
    }

    private void corners(boolean in) {
        float[][] cs = {{4, 4, 1, 1}, {20, 4, -1, 1}, {4, 20, 1, -1}, {20, 20, -1, -1}};
        for (float[] k : cs) {
            float x = k[0], y = k[1], dx = k[2], dy = k[3];
            if (in) { // corners pointing in: the elbow sits 5 units inside
                x += dx * 5;
                y += dy * 5;
                dx = -dx;
                dy = -dy;
            }
            path.moveTo(x + dx * 5, y);
            path.lineTo(x, y);
            path.lineTo(x, y + dy * 5);
        }
    }

    @Override
    public void setAlpha(int a) {
        p.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
