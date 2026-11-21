package android.graphics;

/** Desktop stand-in with the properties the Play HUD uses. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;

    public enum Style { FILL, STROKE }

    public enum Align { LEFT, CENTER, RIGHT }

    int color = 0xFF000000;
    float textSize = 12, strokeWidth = 1, shadowR, shadowDx, shadowDy;
    int shadowColor;
    Style style = Style.FILL;
    Align align = Align.LEFT;
    Typeface typeface = Typeface.DEFAULT;
    ColorFilter filter;

    public Paint() {
    }

    public Paint(int flags) {
    }

    public void setColor(int c) {
        color = c;
    }

    public void setAlpha(int a) {
        color = (color & 0x00FFFFFF) | (a & 255) << 24;
    }

    public void setTextSize(float s) {
        textSize = s;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextAlign(Align a) {
        align = a;
    }

    public void setTypeface(Typeface t) {
        typeface = t;
    }

    public void setShadowLayer(float r, float dx, float dy, int c) {
        shadowR = r;
        shadowDx = dx;
        shadowDy = dy;
        shadowColor = c;
    }

    public void setStyle(Style s) {
        style = s;
    }

    public void setStrokeCap(Cap c) {
    }

    public enum Cap { BUTT, ROUND, SQUARE }

    public void setStrokeWidth(float w) {
        strokeWidth = w;
    }

    public void setColorFilter(ColorFilter f) {
        filter = f;
    }

    public void setFilterBitmap(boolean b) {
    }

    public void setAntiAlias(boolean b) {
    }

    java.awt.Font font() {
        return new java.awt.Font(typeface.mono ? java.awt.Font.MONOSPACED : java.awt.Font.SANS_SERIF, typeface.bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN, Math.max(1, Math.round(textSize)));
    }

    private static final java.awt.Graphics2D MEASURE = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();

    public float measureText(String s) {
        return (float) MEASURE.getFontMetrics(font()).getStringBounds(s, MEASURE).getWidth();
    }
}
