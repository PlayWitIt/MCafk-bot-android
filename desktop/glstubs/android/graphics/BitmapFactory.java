package android.graphics;

/** Desktop stand-in: decodes PNGs with ImageIO. */
public class BitmapFactory {
    public static class Options {
        public boolean inScaled = true;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeFile(String path) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return decodeByteArray(b, 0, b.length);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len) {
        return decodeByteArray(data, off, len, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len, Options o) {
        try {
            java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data, off, len));
            if (b == null) return null;
            java.awt.image.BufferedImage argb = new java.awt.image.BufferedImage(b.getWidth(), b.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(b, 0, 0, null);
            return new Bitmap(argb);
        } catch (Exception e) {
            return null;
        }
    }
}
