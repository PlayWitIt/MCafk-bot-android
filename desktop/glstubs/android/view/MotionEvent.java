package android.view;

/** Desktop stand-in: a single-pointer event the test can build. */
public class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6;
    private final int action, id;
    private final float x, y;

    public MotionEvent(int action, int id, float x, float y) {
        this.action = action;
        this.id = id;
        this.x = x;
        this.y = y;
    }

    public int getActionMasked() {
        return action;
    }

    public int getActionIndex() {
        return 0;
    }

    public int getPointerId(int i) {
        return id;
    }

    public int getPointerCount() {
        return 1;
    }

    public float getX(int i) {
        return x;
    }

    public float getY(int i) {
        return y;
    }
}
