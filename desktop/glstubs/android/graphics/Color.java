package android.graphics;

public class Color {
    public static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000, TRANSPARENT = 0;

    public static int argb(int a, int r, int g, int b) {
        return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    public static int rgb(int r, int g, int b) {
        return argb(255, r, g, b);
    }
}
