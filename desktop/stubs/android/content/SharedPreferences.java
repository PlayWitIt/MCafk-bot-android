package android.content;
import java.io.*;
import java.util.Properties;
/** File-backed stand-in for Android's SharedPreferences (only what the bot uses). */
public class SharedPreferences {
  private final File file; private final Properties p = new Properties();
  SharedPreferences(File f) { file = f; try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException ignored) {} }
  public String getString(String k, String d) { return p.getProperty(k, d); }
  public long getLong(String k, long d) { String v = p.getProperty(k); return v == null ? d : Long.parseLong(v); }
  public int getInt(String k, int d) { String v = p.getProperty(k); return v == null ? d : Integer.parseInt(v); }
  public boolean getBoolean(String k, boolean d) { String v = p.getProperty(k); return v == null ? d : Boolean.parseBoolean(v); }
  public Editor edit() { return new Editor(); }
  public class Editor {
    public Editor putString(String k, String v) { if (v == null) p.remove(k); else p.setProperty(k, v); return this; }
    public Editor putLong(String k, long v) { p.setProperty(k, String.valueOf(v)); return this; }
    public Editor putInt(String k, int v) { p.setProperty(k, String.valueOf(v)); return this; }
    public Editor putBoolean(String k, boolean v) { p.setProperty(k, String.valueOf(v)); return this; }
    public Editor remove(String k) { p.remove(k); return this; }
    public Editor clear() { p.clear(); return this; }
    public void apply() { commit(); }
    public boolean commit() { file.getParentFile().mkdirs(); try (OutputStream o = new FileOutputStream(file)) { p.store(o, "AFK Bot desktop state - contains login tokens, keep private"); return true; } catch (IOException e) { return false; } }
  }
}
