package app.afkbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real mob models: Mojang's official entity geometry and textures (bedrock-samples resource pack),
 * fetched the first time a mob type is seen and cached on the phone. Nothing is bundled in the app
 * except a small index of which model/texture each mob uses.
 */
final class EntityModels {
    private EntityModels() {
    }

    private static final String BASE = "https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/";

    static final class Model {
        final float[] mesh; // Geo.STRIDE floats per vertex
        final Bitmap texture;
        int glTex; // set by the renderer on the GL thread

        Model(float[] mesh, Bitmap texture) {
            this.mesh = mesh;
            this.texture = texture;
        }
    }

    private static final Map<String, Model> MODELS = new HashMap<String, Model>();
    private static final Set<String> LOADING = new HashSet<String>(), FAILED = new HashSet<String>();
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static JSONObject index;
    private static File cacheDir;
    private static volatile JSONObject mobsFile;

    static synchronized void init(Context c) {
        if (index != null) return;
        try {
            InputStream in = c.getAssets().open("entities.json");
            index = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            index = new JSONObject();
        }
        cacheDir = new File(c.getFilesDir(), "entities-v1");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    /** Returns the model for an entity type (e.g. "copper_golem"), starting a download if needed; null until ready. */
    static synchronized Model get(final String type) {
        if (type == null || index == null) return null;
        Model m = MODELS.get(type);
        if (m != null || LOADING.contains(type) || FAILED.contains(type) || !index.has(type)) return m;
        LOADING.add(type);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Model loaded = null;
                try {
                    loaded = load(type);
                } catch (Exception e) {
                    Hub.log("Couldn't load the " + type + " model: " + e.getMessage());
                }
                synchronized (EntityModels.class) {
                    LOADING.remove(type);
                    if (loaded != null) MODELS.put(type, loaded);
                    else FAILED.add(type);
                }
            }
        });
        return null;
    }

    private static Model load(String type) throws Exception {
        JSONObject e = index.getJSONObject(type);
        File meshFile = new File(cacheDir, type + ".mesh"), texFile = new File(cacheDir, type + ".png");
        float[] mesh = null;
        Bitmap tex = null;
        if (meshFile.exists() && texFile.exists()) {
            mesh = readMesh(meshFile);
            tex = BitmapFactory.decodeFile(texFile.getPath());
        }
        if (mesh == null || tex == null) {
            JSONObject geoFile = json(e.getString("file"));
            Map<String, JSONObject> others = new HashMap<String, JSONObject>();
            others.put("mobs", mobs());
            Object[] g = Geo.find(geoFile, e.getString("geo"), others);
            if (g == null) g = Geo.find(mobs(), e.getString("geo"), others);
            if (g == null) throw new IOException("model " + e.getString("geo") + " not found");
            mesh = Geo.mesh((Integer) g[0], (Integer) g[1], (org.json.JSONArray) g[2]);
            byte[] img = fetch(e.getString("tex"));
            tex = e.getString("tex").endsWith(".tga") ? decodeTga(img) : BitmapFactory.decodeByteArray(img, 0, img.length);
            if (tex == null) throw new IOException("couldn't read texture");
            writeMesh(meshFile, mesh);
            FileOutputStream o = new FileOutputStream(texFile);
            try {
                tex.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
            }
        }
        return new Model(mesh, tex);
    }

    private static JSONObject mobs() throws Exception {
        if (mobsFile == null) {
            File f = new File(cacheDir, "mobs.json");
            String s = f.exists() ? ModStore.read(f) : null;
            if (s == null) {
                s = new String(fetch("models/mobs.json"), StandardCharsets.UTF_8);
                FileOutputStream o = new FileOutputStream(f);
                try {
                    o.write(s.getBytes(StandardCharsets.UTF_8));
                } finally {
                    o.close();
                }
            }
            mobsFile = new JSONObject(stripComments(s));
        }
        return mobsFile;
    }

    private static JSONObject json(String path) throws Exception {
        return new JSONObject(stripComments(new String(fetch(path), StandardCharsets.UTF_8)));
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?m)^\\s*//.*$", "");
    }

    private static byte[] fetch(String path) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(BASE + path).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        try {
            if (h.getResponseCode() != 200) throw new IOException(path + ": HTTP " + h.getResponseCode());
            return readAll(h.getInputStream());
        } finally {
            h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static float[] readMesh(File f) {
        try {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f)));
            try {
                int n = in.readInt();
                float[] a = new float[n];
                for (int i = 0; i < n; i++) a[i] = in.readFloat();
                return a;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMesh(File f, float[] a) throws IOException {
        DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(f)));
        try {
            o.writeInt(a.length);
            for (float v : a) o.writeFloat(v);
        } finally {
            o.close();
        }
    }

    /** Minimal TGA reader (true-colour, plain or RLE, 24/32 bit) for the textures Mojang ships as .tga. */
    static Bitmap decodeTga(byte[] d) {
        int idLen = d[0] & 255, type = d[2] & 255;
        int w = (d[12] & 255) | (d[13] & 255) << 8, h = (d[14] & 255) | (d[15] & 255) << 8;
        int bpp = d[16] & 255, desc = d[17] & 255;
        if ((type != 2 && type != 10) || (bpp != 24 && bpp != 32) || w <= 0 || h <= 0) return null;
        int bytes = bpp / 8, p = 18 + idLen;
        int[] px = new int[w * h];
        int i = 0;
        while (i < px.length && p < d.length) {
            if (type == 10) {
                int hdr = d[p++] & 255, count = (hdr & 127) + 1;
                if ((hdr & 128) != 0) {
                    int c = pixel(d, p, bytes);
                    p += bytes;
                    for (int k = 0; k < count && i < px.length; k++) px[i++] = c;
                } else {
                    for (int k = 0; k < count && i < px.length; k++, p += bytes) px[i++] = pixel(d, p, bytes);
                }
            } else {
                px[i++] = pixel(d, p, bytes);
                p += bytes;
            }
        }
        boolean topDown = (desc & 0x20) != 0;
        if (!topDown) { // flip rows
            int[] f = new int[px.length];
            for (int y = 0; y < h; y++) System.arraycopy(px, y * w, f, (h - 1 - y) * w, w);
            px = f;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int pixel(byte[] d, int p, int bytes) {
        int b = d[p] & 255, g = d[p + 1] & 255, r = d[p + 2] & 255, a = bytes == 4 ? d[p + 3] & 255 : 255;
        return a << 24 | r << 16 | g << 8 | b;
    }
}
