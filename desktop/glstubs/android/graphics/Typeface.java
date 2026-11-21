package android.graphics;

public class Typeface {
    public static final int NORMAL = 0, BOLD = 1;
    public static final Typeface DEFAULT = new Typeface(false, false), MONOSPACE = new Typeface(true, false), DEFAULT_BOLD = new Typeface(false, true);
    final boolean mono, bold;

    Typeface(boolean mono, boolean bold) {
        this.mono = mono;
        this.bold = bold;
    }

    public static Typeface create(Typeface family, int style) {
        return new Typeface(family != null && family.mono, (style & BOLD) != 0);
    }
}
