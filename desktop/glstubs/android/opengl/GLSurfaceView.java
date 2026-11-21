package android.opengl;

/** Desktop stand-in: only the Renderer interface the Play renderer implements. */
public class GLSurfaceView {
    public static final int RENDERMODE_WHEN_DIRTY = 0, RENDERMODE_CONTINUOUSLY = 1;

    public interface Renderer {
        void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config);

        void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height);

        void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl);
    }
}
