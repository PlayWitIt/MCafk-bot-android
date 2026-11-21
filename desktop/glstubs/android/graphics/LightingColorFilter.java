package android.graphics;

public class LightingColorFilter extends ColorFilter {
    final int mul, add;

    public LightingColorFilter(int mul, int add) {
        this.mul = mul;
        this.add = add;
    }
}
