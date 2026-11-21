package android.graphics;

/** Desktop stand-in (not used by the GL test). */
public class BitmapFactory {
    public static class Options {
        public boolean inScaled = true;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len, Options o) {
        return null;
    }
}
