package android.graphics;

/** Desktop stand-in, just enough for GameAssetStore to compile and hold an atlas. */
public class Bitmap {
    public enum Config { ARGB_8888 }

    private final int[] px;
    private final int w, h;

    private Bitmap(int[] px, int w, int h) {
        this.px = px;
        this.w = w;
        this.h = h;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config c) {
        return new Bitmap(colors.clone(), width, height);
    }

    public int getWidth() {
        return w;
    }

    public int getHeight() {
        return h;
    }

    public int getPixel(int x, int y) {
        return px[y * w + x];
    }

    public void recycle() {
    }
}
