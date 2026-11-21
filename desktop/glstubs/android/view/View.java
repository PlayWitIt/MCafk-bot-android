package android.view;

/** Desktop stand-in: a fixed-size view whose onDraw the test calls itself. */
public class View {
    private final android.content.Context ctx;
    private int w, h;

    public View(android.content.Context c) {
        ctx = c;
    }

    public void setDesktopSize(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public int getWidth() {
        return w;
    }

    public int getHeight() {
        return h;
    }

    public android.content.Context getContext() {
        return ctx;
    }

    public void invalidate() {
    }

    public void postInvalidateOnAnimation() {
    }

    public boolean performHapticFeedback(int c) {
        return true;
    }

    public void setKeepScreenOn(boolean on) {
    }

    protected void onDraw(android.graphics.Canvas c) {
    }

    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }
}
