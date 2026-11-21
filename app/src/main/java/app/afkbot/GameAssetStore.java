package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The Play screen's game files on the phone: downloaded once from Mojang (about 3 MB: block states, models, block and
 * item textures, HUD sprites) into files/game/&lt;version&gt;/assets.zip, then turned into an atlas and baked models.
 */
final class GameAssetStore {
    private GameAssetStore() {
    }

    static final class Loaded {
        final String version;
        final Map<String, byte[]> files;
        final GameAtlas atlas;
        final GameModels models;
        final Bitmap atlasBitmap;
        volatile GameBiomes biomes; // biome colours (grass, foliage, water)

        Loaded(String version, Map<String, byte[]> files, GameAtlas atlas, GameModels models, Bitmap atlasBitmap) {
            this.version = version;
            this.files = files;
            this.atlas = atlas;
            this.models = models;
            this.atlasBitmap = atlasBitmap;
        }

        /** A HUD sprite (e.g. "hud/hotbar"), decoded at its own size, or null. */
        Bitmap sprite(String name) {
            return texture("gui/sprites/" + name);
        }

        /** Any texture by its path under textures/ (e.g. "font/ascii"), decoded at its own size, or null. */
        Bitmap texture(String path) {
            byte[] b = files.get("assets/minecraft/textures/" + path + ".png");
            if (b == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o);
        }

        /** A texture's ARGB pixels (row by row), or null. */
        int[] pixels(String path) {
            Bitmap b = texture(path);
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = b.getPixel(x, y);
            return px;
        }
    }

    static volatile Loaded current;
    static volatile String status = "";

    static File file(Context c, String version) {
        return new File(c.getFilesDir(), "game/" + version + "/assets-v" + GameAssets.FORMAT + ".zip");
    }

    /** Loads (downloading the first time) the files for a version and builds atlas + models. Call off the UI thread. */
    static synchronized Loaded load(Context c, String version, ProtoDef.VersionData vd) throws IOException {
        Loaded have = current;
        if (have != null && have.version.equals(version)) return have;
        File f = file(c, version);
        Map<String, byte[]> files;
        if (f.exists()) {
            status = "Loading game files...";
            files = readZip(f);
        } else {
            status = "Finding Minecraft " + version + "...";
            RemoteZip z = RemoteZip.forVersion(version);
            status = "Reading the file list...";
            List<RemoteZip.Entry> want = new ArrayList<RemoteZip.Entry>();
            for (RemoteZip.Entry e : z.list()) if (GameAssets.wanted(e.name)) want.add(e);
            files = z.fetch(want, new RemoteZip.Progress() {
                @Override
                public void update(String what, long done, long total) {
                    status = String.format(java.util.Locale.US, "Downloading game files from Mojang: %.1f / %.1f MB", done / 1e6, total / 1e6);
                }
            });
            writeZip(f, files);
        }
        loadLanguage(files);
        status = "Building textures...";
        GameAtlas atlas = GameAtlas.build(files, IMAGES);
        Bitmap bmp = Bitmap.createBitmap(atlas.pixels, GameAtlas.SIZE, GameAtlas.SIZE, Bitmap.Config.ARGB_8888);
        status = "Ready";
        Loaded l = new Loaded(version, files, atlas, new GameModels(files, atlas, vd), bmp);
        l.biomes = new GameBiomes(files, IMAGES);
        current = l;
        return l;
    }

    /** Chat and death messages in proper English (the server sends many as translation keys). */
    static void loadLanguage(Map<String, byte[]> files) {
        byte[] b = files.get("assets/minecraft/lang/en_us.json");
        if (b == null) return;
        try {
            org.json.JSONObject j = new org.json.JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> m = new HashMap<String, String>(j.length() * 2);
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, j.optString(k));
            }
            Text.lang = m;
        } catch (org.json.JSONException ignored) {
        }
    }

    static final GameAtlas.Images IMAGES = new GameAtlas.Images() {
        @Override
        public int[] decodeFull(byte[] png, int[] wh) {
            Bitmap b = decode(png);
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            wh[0] = w;
            wh[1] = h;
            return px;
        }

        @Override
        public int[] decode16(byte[] png) {
            Bitmap b = decode(png);
            if (b == null) return null;
            int w = b.getWidth(), h = Math.min(b.getHeight(), w); // animated textures: first frame
            int[] px = new int[256];
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) px[y * 16 + x] = b.getPixel(x * w / 16, y * h / 16);
            b.recycle();
            return px;
        }

        @Override
        public int[] decode(byte[] png, int size) {
            Bitmap b = decode(png);
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) px[y * size + x] = b.getPixel(x * w / size, y * h / size);
            b.recycle();
            return px;
        }

        private Bitmap decode(byte[] png) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(png, 0, png.length, o);
        }
    };

    private static Map<String, byte[]> readZip(File f) throws IOException {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = in.getNextEntry()) != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                out.put(e.getName(), bo.toByteArray());
            }
        } finally {
            in.close();
        }
        return out;
    }

    private static void writeZip(File f, Map<String, byte[]> files) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".part");
        ZipOutputStream o = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        try {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                o.putNextEntry(new ZipEntry(e.getKey()));
                o.write(e.getValue());
                o.closeEntry();
            }
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't save the game files");
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
