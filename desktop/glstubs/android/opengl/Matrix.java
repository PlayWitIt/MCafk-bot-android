package android.opengl;

/** Desktop copy of android.opengl.Matrix's math (column-major, same formulas as Android). */
public class Matrix {
    public static void multiplyMM(float[] r, int ro, float[] a, int ao, float[] b, int bo) {
        float[] t = new float[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[ao + k * 4 + i] * b[bo + j * 4 + k];
                t[j * 4 + i] = s;
            }
        System.arraycopy(t, 0, r, ro, 16);
    }

    public static void setIdentityM(float[] m, int o) {
        java.util.Arrays.fill(m, o, o + 16, 0f);
        m[o] = m[o + 5] = m[o + 10] = m[o + 15] = 1f;
    }

    /** Rotates m in place by angle degrees around (x, y, z), like Android's rotateM (m = m * R). */
    public static void rotateM(float[] m, int o, float a, float x, float y, float z) {
        float[] r = new float[16];
        setRotateM(r, 0, a, x, y, z);
        float[] t = new float[16];
        multiplyMM(t, 0, m, o, r, 0);
        System.arraycopy(t, 0, m, o, 16);
    }

    public static void setRotateM(float[] rm, int o, float a, float x, float y, float z) {
        java.util.Arrays.fill(rm, o, o + 16, 0f);
        rm[o + 15] = 1;
        a *= (float) (Math.PI / 180.0);
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        x /= len;
        y /= len;
        z /= len;
        float nc = 1.0f - c, xy = x * y, yz = y * z, zx = z * x, xs = x * s, ys = y * s, zs = z * s;
        rm[o] = x * x * nc + c;
        rm[o + 4] = xy * nc - zs;
        rm[o + 8] = zx * nc + ys;
        rm[o + 1] = xy * nc + zs;
        rm[o + 5] = y * y * nc + c;
        rm[o + 9] = yz * nc - xs;
        rm[o + 2] = zx * nc - ys;
        rm[o + 6] = yz * nc + xs;
        rm[o + 10] = z * z * nc + c;
    }

    public static void multiplyMV(float[] r, int ro, float[] m, int mo, float[] v, int vo) {
        float[] t = new float[4];
        for (int i = 0; i < 4; i++) t[i] = m[mo + i] * v[vo] + m[mo + 4 + i] * v[vo + 1] + m[mo + 8 + i] * v[vo + 2] + m[mo + 12 + i] * v[vo + 3];
        System.arraycopy(t, 0, r, ro, 4);
    }

    public static void perspectiveM(float[] m, int o, float fovy, float aspect, float zNear, float zFar) {
        float f = 1.0f / (float) Math.tan(fovy * (Math.PI / 360.0));
        float rr = 1.0f / (zNear - zFar);
        java.util.Arrays.fill(m, o, o + 16, 0f);
        m[o] = f / aspect;
        m[o + 5] = f;
        m[o + 10] = (zFar + zNear) * rr;
        m[o + 11] = -1.0f;
        m[o + 14] = 2.0f * zFar * zNear * rr;
    }

    public static void setLookAtM(float[] rm, int o, float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float rlf = 1.0f / (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx *= rlf;
        fy *= rlf;
        fz *= rlf;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
        float rls = 1.0f / (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx *= rls;
        sy *= rls;
        sz *= rls;
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        rm[o] = sx;
        rm[o + 1] = vx;
        rm[o + 2] = -fx;
        rm[o + 3] = 0;
        rm[o + 4] = sy;
        rm[o + 5] = vy;
        rm[o + 6] = -fy;
        rm[o + 7] = 0;
        rm[o + 8] = sz;
        rm[o + 9] = vz;
        rm[o + 10] = -fz;
        rm[o + 11] = 0;
        rm[o + 12] = -(sx * ex + sy * ey + sz * ez);
        rm[o + 13] = -(vx * ex + vy * ey + vz * ez);
        rm[o + 14] = fx * ex + fy * ey + fz * ez;
        rm[o + 15] = 1;
    }
}
