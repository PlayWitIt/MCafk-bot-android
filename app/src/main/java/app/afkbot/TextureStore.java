package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Downloads block textures straight from Mojang (only the ~1 MB of files needed, using partial downloads),
 * builds a texture atlas on the phone and caches it. Nothing from Mojang is bundled in the app.
 */
final class TextureStore {
    private TextureStore() {
    }

    static final class Loaded {
        final Bitmap atlas;
        final TexturePack.Map3D map;
        final String version;

        Loaded(Bitmap atlas, TexturePack.Map3D map, String version) {
            this.atlas = atlas;
            this.map = map;
            this.version = version;
        }
    }

    static volatile Loaded current;
    static volatile String status = "";
    static volatile boolean busy;

    static File dir(Context c, String version) {
        return new File(c.getFilesDir(), "textures/v2-" + version); // v2: includes chest textures
    }

    static boolean have(Context c, String version) {
        File d = dir(c, version);
        return new File(d, "atlas.png").exists() && new File(d, "map.json").exists();
    }

    /** Loads the cached atlas for a version (null if not downloaded). */
    static Loaded load(Context c, String version) {
        Loaded cur = current;
        if (cur != null && cur.version.equals(version)) return cur;
        if (!have(c, version)) return null;
        try {
            File d = dir(c, version);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(new File(d, "atlas.png").getPath(), o);
            String json = ModStore.read(new File(d, "map.json"));
            if (bm == null) return null;
            current = new Loaded(bm, new TexturePack.Map3D(json), version);
            return current;
        } catch (Exception e) {
            status = "Couldn't load textures: " + e.getMessage();
            return null;
        }
    }

    static void delete(Context c, String version) {
        File d = dir(c, version);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
        current = null;
    }

    /** Downloads and builds textures for a version. Call off the main thread. */
    static void download(Context c, String version, String[] blockIds) throws IOException {
        busy = true;
        try {
            status = "Finding Minecraft " + version + "...";
            RemoteZip z = RemoteZip.forVersion(version);
            status = "Reading file list...";
            List<RemoteZip.Entry> want = new ArrayList<RemoteZip.Entry>();
            for (RemoteZip.Entry e : z.list()) if (TexturePack.wanted(e.name)) want.add(e);
            Map<String, byte[]> files = z.fetch(want, new RemoteZip.Progress() {
                @Override
                public void update(String what, long done, long total) {
                    status = String.format(java.util.Locale.US, "%s: %.1f / %.1f MB", what, done / 1e6, total / 1e6);
                }
            });
            status = "Building texture atlas...";
            TexturePack.Result r = TexturePack.build(files, blockIds, new TexturePack.Images() {
                @Override
                public int[] decode16(byte[] png) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inScaled = false;
                    Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length, o);
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
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inScaled = false;
                    Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length, o);
                    if (b == null) return null;
                    int w = b.getWidth(), h = b.getHeight();
                    int[] px = new int[size * size];
                    for (int y = 0; y < size; y++)
                        for (int x = 0; x < size; x++) px[y * size + x] = b.getPixel(x * w / size, y * h / size);
                    b.recycle();
                    return px;
                }
            });
            File d = dir(c, version);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            Bitmap atlas = Bitmap.createBitmap(r.atlas, TexturePack.SIZE, TexturePack.SIZE, Bitmap.Config.ARGB_8888);
            FileOutputStream o = new FileOutputStream(new File(d, "atlas.png"));
            try {
                atlas.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
            }
            FileOutputStream m = new FileOutputStream(new File(d, "map.json"));
            try {
                m.write(TexturePack.mapJson(r).getBytes(StandardCharsets.UTF_8));
            } finally {
                m.close();
            }
            current = new Loaded(atlas, new TexturePack.Map3D(TexturePack.mapJson(r)), version);
            status = "Textures ready (" + r.tiles + " textures)";
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        } finally {
            busy = false;
        }
    }
}
