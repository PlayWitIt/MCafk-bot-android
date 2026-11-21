package android.graphics;

import java.awt.image.BufferedImage;

/** Desktop stand-in backed by a BufferedImage. */
public class Bitmap {
    public enum Config { ARGB_8888 }

    public enum CompressFormat { PNG }

    public boolean compress(CompressFormat f, int quality, java.io.OutputStream out) {
        try {
            return javax.imageio.ImageIO.write(img, "png", out);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    final BufferedImage img;

    Bitmap(BufferedImage img) {
        this.img = img;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config c) {
        BufferedImage b = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, width, height, colors, 0, width);
        return new Bitmap(b);
    }

    public int getWidth() {
        return img.getWidth();
    }

    public int getHeight() {
        return img.getHeight();
    }

    public int getPixel(int x, int y) {
        return img.getRGB(x, y);
    }

    public void recycle() {
    }
}
