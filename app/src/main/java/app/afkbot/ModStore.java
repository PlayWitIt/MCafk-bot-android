package app.afkbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Saves mods as .js files in the app's private storage and connects the engine to the bot. */
final class ModStore {
    private ModStore() {
    }

    private static boolean initialised;

    static synchronized void init(Context ctx) {
        if (initialised) return;
        initialised = true;
        final Context app = ctx.getApplicationContext();
        ModEngine.host = new Host(app);
        File[] files = dir(app).listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".js")) continue;
            String id = f.getName().substring(0, f.getName().length() - 3);
            try {
                ModEngine.Mod m = new ModEngine.Mod(id, read(f));
                ModEngine.register(m);
                if (prefs(app).getBoolean("on_" + id, false)) ModEngine.start(m, prefs(app).getString("data_" + id, ""));
            } catch (IOException e) {
                Hub.log("Couldn't load mod " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mods", Context.MODE_PRIVATE);
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "mods");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Installs (or replaces a mod with the same name) and turns it on. Returns the mod id. */
    static String install(Context c, String code) throws IOException {
        code = code.replace("\r\n", "\n");
        if (code.trim().isEmpty()) throw new IOException("The mod is empty");
        if (code.length() > 300_000) throw new IOException("The mod is too big (max 300 KB)");
        String name = ModEngine.meta(code, "name", "Mod");
        String id = slug(name);
        write(new File(dir(c), id + ".js"), code);
        ModEngine.Mod old = ModEngine.get(id);
        if (old != null) ModEngine.remove(id);
        ModEngine.Mod m = new ModEngine.Mod(id, code);
        ModEngine.register(m);
        boolean on = !"false".equalsIgnoreCase(ModEngine.meta(code, "enabled", "true"));
        setEnabled(c, id, on);
        Hub.log("Installed mod \"" + m.name + "\"" + (on ? "" : " (switched off - edit it, then turn it on)"));
        return id;
    }

    static void setEnabled(Context c, String id, boolean on) {
        prefs(c).edit().putBoolean("on_" + id, on).apply();
        ModEngine.Mod m = ModEngine.get(id);
        if (m == null) return;
        if (on) ModEngine.start(m, prefs(c).getString("data_" + id, ""));
        else ModEngine.stop(m);
    }

    static boolean isEnabled(Context c, String id) {
        return prefs(c).getBoolean("on_" + id, false);
    }

    static void saveCode(Context c, String id, String code) throws IOException {
        ModEngine.Mod m = ModEngine.get(id);
        if (m == null) return;
        write(new File(dir(c), id + ".js"), code.replace("\r\n", "\n"));
        m.setCode(code.replace("\r\n", "\n"));
        if (isEnabled(c, id)) ModEngine.start(m, prefs(c).getString("data_" + id, ""));
    }

    static void delete(Context c, String id) {
        ModEngine.remove(id);
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), id + ".js").delete();
        prefs(c).edit().remove("on_" + id).remove("data_" + id).apply();
        ModEngine.version++;
    }

    static void clearData(Context c, String id) {
        prefs(c).edit().remove("data_" + id).apply();
        ModEngine.Mod m = ModEngine.get(id);
        if (m != null && isEnabled(c, id)) ModEngine.start(m, "");
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "mod" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > 300_000) throw new IOException("The mod is too big (max 300 KB)");
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            o.close();
        }
    }

    /** Downloads a mod from a link (e.g. a raw GitHub file). Call off the main thread. */
    static String download(String url) throws IOException {
        if (url.contains("github.com/") && url.contains("/blob/")) {
            url = url.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/");
        }
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        try {
            if (h.getResponseCode() != 200) throw new IOException("Download failed (HTTP " + h.getResponseCode() + ")");
            return readAll(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    /** Bridges mods to the running bot. */
    private static final class Host implements ModEngine.Host {
        private final Context app;

        Host(Context app) {
            this.app = app;
        }

        @Override
        public String name() {
            Bot b = Hub.bot;
            String n = b != null ? b.playerName() : null;
            if (n != null) return n;
            String s = Auth.signedInName(app);
            return s != null ? s : "";
        }

        @Override
        public String server() {
            Bot.Config c = Hub.config;
            return c != null ? c.address : "";
        }

        @Override
        public boolean online() {
            return Hub.bot != null && World.inWorld;
        }

        @Override
        public double[] pos() {
            return new double[]{World.selfX, World.selfY, World.selfZ, World.selfYaw};
        }

        @Override
        public float health() {
            return Hub.statHealth;
        }

        @Override
        public List<World.Dot> entities() {
            Bot.Config c = Hub.config;
            return World.snapshot(c != null ? c.friendSet() : null);
        }

        @Override
        public void chat(String text) {
            Bot b = Hub.bot;
            if (b != null && World.inWorld) b.sendChat(text);
        }

        @Override
        public void jump() {
            Bot b = Hub.bot;
            if (b != null) b.forceAction();
        }

        @Override
        public void disconnect(String reason) {
            Bot b = Hub.bot;
            if (b != null) b.modDisconnect(reason);
        }

        @Override
        public void log(String modName, String msg) {
            Hub.log("[" + modName + "] " + msg);
        }

        @Override
        public void alert(String title, String text) {
            Hub.alert(title, text);
        }

        @Override
        public void saveStorage(String modId, String json) {
            prefs(app).edit().putString("data_" + modId, json).apply();
        }
    }
}
