package android.content.res;

/** Desktop stand-in: reads the app's assets folder. */
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        return new java.io.FileInputStream("app/src/main/assets/" + name);
    }

    public String[] list(String dir) {
        String[] l = new java.io.File("app/src/main/assets/" + dir).list();
        return l == null ? new String[0] : l;
    }
}
