package android.content;
/** Desktop stand-in: preferences are saved as .properties files in desktop/state/. */
public class Context {
  public static final int MODE_PRIVATE = 0;
  public SharedPreferences getSharedPreferences(String name, int mode) { return new SharedPreferences(new java.io.File("desktop/state/" + name + ".properties")); }
  public Context getApplicationContext() { return this; }
  public java.io.File getFilesDir() { java.io.File f = new java.io.File("desktop/state/files"); f.mkdirs(); return f; }
}
