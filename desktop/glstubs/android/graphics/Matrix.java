package android.graphics;

import java.awt.geom.AffineTransform;

/** Desktop stand-in: only setPolyToPoly for parallelograms (exact as an affine transform). */
public class Matrix {
    AffineTransform t = new AffineTransform();

    public boolean setPolyToPoly(float[] src, int si, float[] dst, int di, int count) {
        // affine map from src points 0,1,3 to dst points 0,1,3
        double sx0 = src[si], sy0 = src[si + 1], sx1 = src[si + 2], sy1 = src[si + 3], sx3 = src[si + 6], sy3 = src[si + 7];
        double dx0 = dst[di], dy0 = dst[di + 1], dx1 = dst[di + 2], dy1 = dst[di + 3], dx3 = dst[di + 6], dy3 = dst[di + 7];
        AffineTransform s = new AffineTransform(sx1 - sx0, sy1 - sy0, sx3 - sx0, sy3 - sy0, sx0, sy0);
        AffineTransform d = new AffineTransform(dx1 - dx0, dy1 - dy0, dx3 - dx0, dy3 - dy0, dx0, dy0);
        try {
            d.concatenate(s.createInverse());
        } catch (Exception e) {
            return false;
        }
        t = d;
        return true;
    }
}
