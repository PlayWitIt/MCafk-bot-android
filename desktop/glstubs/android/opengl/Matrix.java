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
