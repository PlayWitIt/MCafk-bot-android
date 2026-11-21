package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Other players' real skins for the Play screen: downloaded from Mojang's skin server (the URL the server sends in
 * the tab list; World only accepts textures.minecraft.net), cached on the phone, and turned into a 64x64 image like
 * the game does (old 64x32 skins get their left arm and leg copied from the right).
 */
final class GameSkins {
    private GameSkins() {
    }

    private static final Map<String, int[]> SKINS = new HashMap<String, int[]>();
    private static final Set<String> LOADING = new HashSet<String>(), FAILED = new HashSet<String>();
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "skins");
            t.setDaemon(true); // never keeps the app's process alive
            return t;
        }
    });
    private static volatile File dir;

    static void init(Context c) {
        File d = new File(c.getFilesDir(), "skins");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        dir = d;
    }

    /** The skin as 64x64 ARGB pixels, or null while it downloads (or if it can't be had). */
    static synchronized int[] get(final String url) {
        if (url == null || dir == null) return null;
        int[] px = SKINS.get(url);
        if (px != null || LOADING.contains(url) || FAILED.contains(url)) return px;
        LOADING.add(url);
        EXEC.execute(new Runnable() {
            public void run() {
                int[] got = null;
                try {
                    got = load(url);
                } catch (Exception ignored) {
                }
                synchronized (GameSkins.class) {
                    LOADING.remove(url);
                    if (got != null) SKINS.put(url, got);
                    else FAILED.add(url);
                }
            }
        });
        return null;
    }

    private static int[] load(String url) throws Exception {
        String name = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9]", "");
        File f = new File(dir, name + ".png");
        byte[] png;
        if (f.exists()) {
            png = java.nio.file.Files.readAllBytes(f.toPath());
        } else {
            HttpURLConnection h = (HttpURLConnection) new URL(url.replace("http://", "https://")).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(10000);
            InputStream in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 512 * 1024) bo.write(buf, 0, n);
            in.close();
            png = bo.toByteArray();
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(png);
            } finally {
                o.close();
            }
        }
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        if (w != 64 || (h != 64 && h != 32)) return null;
        int[] px = new int[64 * 64];
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) px[y * 64 + x] = b.getPixel(x, y);
        if (h == 32) { // legacy skin: the left leg and arm are copies of the right ones
            copy(px, 0, 16, 16, 48, 16, 16);
            copy(px, 40, 16, 32, 48, 16, 16);
        }
        return px;
    }

    private static void copy(int[] px, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[(dy + y) * 64 + dx + x] = px[(sy + y) * 64 + sx + x];
    }
}
