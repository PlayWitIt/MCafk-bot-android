package android.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** Desktop stand-in drawing with Java2D onto a BufferedImage (what the Play HUD uses). */
public class Canvas {
    private final Graphics2D g;
    private final ArrayDeque<Object[]> stack = new ArrayDeque<Object[]>();

    public Canvas(BufferedImage img) {
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    private void paint(Paint p) {
        g.setColor(new java.awt.Color(p.color, true));
        g.setStroke(new BasicStroke(p.strokeWidth));
    }

    private void shape(java.awt.Shape s, Paint p) {
        paint(p);
        if (p.style == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
    }

    public void drawColor(int c) {
        java.awt.Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new java.awt.Color(c, true));
        AffineTransform t = g.getTransform();
        g.setTransform(new AffineTransform());
        g.fillRect(0, 0, 100000, 100000);
        g.setTransform(t);
        g.setComposite(old);
    }

    public void drawRect(float l, float t, float r, float b, Paint p) {
        shape(new Rectangle2D.Float(l, t, r - l, b - t), p);
    }

    public void drawRect(RectF r, Paint p) {
        drawRect(r.left, r.top, r.right, r.bottom, p);
    }

    public void drawRoundRect(RectF r, float rx, float ry, Paint p) {
        shape(new RoundRectangle2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top, rx * 2, ry * 2), p);
    }

    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {
        paint(p);
        g.setStroke(new BasicStroke(p.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Float(x0, y0, x1, y1));
    }

    public void drawCircle(float cx, float cy, float rad, Paint p) {
        shape(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2), p);
    }

    private static BufferedImage filtered(Bitmap b, Paint p) {
        if (p == null || !(p.filter instanceof LightingColorFilter)) return b.img;
        int mul = ((LightingColorFilter) p.filter).mul;
        BufferedImage o = new BufferedImage(b.img.getWidth(), b.img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < o.getHeight(); y++)
            for (int x = 0; x < o.getWidth(); x++) {
                int c = b.img.getRGB(x, y);
                int r = ((c >> 16) & 255) * ((mul >> 16) & 255) / 255, gg = ((c >> 8) & 255) * ((mul >> 8) & 255) / 255, bb = (c & 255) * (mul & 255) / 255;
                o.setRGB(x, y, (c & 0xFF000000) | r << 16 | gg << 8 | bb);
            }
        return o;
    }

    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        if (b == null) return;
        int sx0 = src == null ? 0 : src.left, sy0 = src == null ? 0 : src.top, sx1 = src == null ? b.getWidth() : src.right, sy1 = src == null ? b.getHeight() : src.bottom;
        AffineTransform t = g.getTransform();
        g.translate(dst.left, dst.top);
        g.scale(dst.width() / (sx1 - sx0), dst.height() / (sy1 - sy0));
        g.drawImage(filtered(b, p), 0, 0, sx1 - sx0, sy1 - sy0, sx0, sy0, sx1, sy1, null);
        g.setTransform(t);
    }

    public void drawBitmap(Bitmap b, float left, float top, Paint p) {
        if (b == null) return;
        AffineTransform t = g.getTransform();
        g.translate(left, top);
        g.drawImage(filtered(b, p), 0, 0, null);
        g.setTransform(t);
    }

    public void drawText(String s, float x, float y, Paint p) {
        g.setFont(p.font());
        float w = p.measureText(s);
        if (p.align == Paint.Align.CENTER) x -= w / 2;
        else if (p.align == Paint.Align.RIGHT) x -= w;
        int alpha = p.color >>> 24;
        if (p.shadowR > 0 || p.shadowDx != 0 || p.shadowDy != 0) {
            int sc = (p.shadowColor & 0x00FFFFFF) | (alpha * (p.shadowColor >>> 24) / 255) << 24;
            g.setColor(new java.awt.Color(sc, true));
            g.drawString(s, x + p.shadowDx, y + p.shadowDy);
        }
        g.setColor(new java.awt.Color(p.color, true));
        g.drawString(s, x, y);
    }

    public int save() {
        stack.push(new Object[]{g.getTransform(), g.getClip()});
        return stack.size();
    }

    public void restore() {
        Object[] s = stack.pop();
        g.setTransform((AffineTransform) s[0]);
        g.setClip((java.awt.Shape) s[1]);
    }

    public void concat(Matrix m) {
        g.transform(m.t);
    }

    public boolean clipRect(Rect r) {
        g.clip(new Rectangle2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top));
        return true;
    }
}
