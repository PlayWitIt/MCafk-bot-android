package android.util;

/** Desktop stand-in: a typical phone's density. */
public class DisplayMetrics {
    public float density = Float.parseFloat(System.getProperty("afk.density", "2.75"));
}
