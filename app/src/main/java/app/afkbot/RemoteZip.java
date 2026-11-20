package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Reads selected files out of a zip on a web server using HTTP range requests, without downloading
 * the whole thing. Used to fetch only block textures/models (~1 MB) from Mojang's ~30 MB client jar.
 */
final class RemoteZip {
    interface Progress {
        void update(String what, long done, long total);
    }

    private final String url;
    private final long size;

    static final class Entry {
        final String name;
        final int method;
        final long compressed, uncompressed, offset;

        Entry(String name, int method, long compressed, long uncompressed, long offset) {
            this.name = name;
            this.method = method;
            this.compressed = compressed;
            this.uncompressed = uncompressed;
            this.offset = offset;
        }
    }

    RemoteZip(String url, long size) {
        this.url = url;
        this.size = size;
    }

    /** Finds the client jar URL and size for a Minecraft version, e.g. "1.21.11". */
    static RemoteZip forVersion(String version) throws IOException {
        try {
            JSONObject vm = new JSONObject(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
            JSONArray vs = vm.getJSONArray("versions");
            String vurl = null;
            for (int i = 0; i < vs.length(); i++) {
                JSONObject v = vs.getJSONObject(i);
                if (v.getString("id").equals(version)) vurl = v.getString("url");
            }
            if (vurl == null) throw new IOException("Mojang doesn't list version " + version);
            JSONObject client = new JSONObject(get(vurl)).getJSONObject("downloads").getJSONObject("client");
            return new RemoteZip(client.getString("url"), client.getLong("size"));
        } catch (org.json.JSONException e) {
            throw new IOException("bad version data: " + e.getMessage());
        }
    }

    private static String get(String u) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        try {
            if (h.getResponseCode() != 200) throw new IOException(u + " returned HTTP " + h.getResponseCode());
            return new String(readAll(h.getInputStream(), -1), StandardCharsets.UTF_8);
        } finally {
            h.disconnect();
        }
    }

    private byte[] range(long from, long to) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Range", "bytes=" + from + "-" + to);
        try {
            int code = h.getResponseCode();
            if (code != 206) throw new IOException("the download server doesn't support partial downloads (HTTP " + code + ")");
            return readAll(h.getInputStream(), (int) (to - from + 1));
        } finally {
            h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int expect) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(expect > 0 ? expect : 8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | (b[p + 1] & 255) << 8;
    }

    private static long u32(byte[] b, int p) {
        return (u16(b, p) | (long) u16(b, p + 2) << 16) & 0xFFFFFFFFL;
    }

    /** Lists all entries from the central directory (two small range requests). */
    List<Entry> list() throws IOException {
        long tailStart = Math.max(0, size - 65557);
        byte[] tail = range(tailStart, size - 1);
        int e = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                e = i;
                break;
            }
        }
        if (e < 0) throw new IOException("not a zip file");
        long cdSize = u32(tail, e + 12), cdOff = u32(tail, e + 16);
        byte[] cd = range(cdOff, cdOff + cdSize - 1);
        List<Entry> out = new ArrayList<Entry>();
        int p = 0;
        while (p + 46 <= cd.length && u32(cd, p) == 0x02014b50L) {
            int method = u16(cd, p + 10);
            long csz = u32(cd, p + 20), usz = u32(cd, p + 24);
            int nl = u16(cd, p + 28), el = u16(cd, p + 30), cl = u16(cd, p + 32);
            long off = u32(cd, p + 42);
            String name = new String(cd, p + 46, nl, StandardCharsets.UTF_8);
            out.add(new Entry(name, method, csz, usz, off));
            p += 46 + nl + el + cl;
        }
        return out;
    }

    /** Downloads and unpacks the given entries, merging nearby ones into few requests. */
    Map<String, byte[]> fetch(List<Entry> wanted, Progress progress) throws IOException {
        List<Entry> sorted = new ArrayList<Entry>(wanted);
        Collections.sort(sorted, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(a.offset, b.offset);
            }
        });
        // group into ranges (local header is 30 bytes + name + extra; allow 512 bytes of slack)
        List<long[]> ranges = new ArrayList<long[]>();
        List<List<Entry>> groups = new ArrayList<List<Entry>>();
        for (Entry en : sorted) {
            long s = en.offset, t = en.offset + 30 + en.name.length() + 512 + en.compressed;
            if (!ranges.isEmpty() && s - ranges.get(ranges.size() - 1)[1] < 65536 && t - ranges.get(ranges.size() - 1)[0] < 8_000_000) {
                long[] r = ranges.get(ranges.size() - 1);
                r[1] = Math.max(r[1], t);
                groups.get(groups.size() - 1).add(en);
            } else {
                ranges.add(new long[]{s, t});
                List<Entry> g = new ArrayList<Entry>();
                g.add(en);
                groups.add(g);
            }
        }
        long total = 0, done = 0;
        for (long[] r : ranges) total += r[1] - r[0];
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        Inflater inf = new Inflater(true);
        for (int gi = 0; gi < ranges.size(); gi++) {
            long[] r = ranges.get(gi);
            long to = Math.min(size - 1, r[1]);
            byte[] data = range(r[0], to);
            done += data.length;
            if (progress != null) progress.update("Downloading textures", done, total);
            for (Entry en : groups.get(gi)) {
                int p = (int) (en.offset - r[0]);
                if (p + 30 > data.length || u32(data, p) != 0x04034b50L) throw new IOException("bad zip entry " + en.name);
                int nl = u16(data, p + 26), el = u16(data, p + 28);
                int start = p + 30 + nl + el;
                if (start + en.compressed > data.length) throw new IOException("short read for " + en.name);
                byte[] raw;
                if (en.method == 0) {
                    raw = new byte[(int) en.compressed];
                    System.arraycopy(data, start, raw, 0, raw.length);
                } else if (en.method == 8) {
                    inf.reset();
                    inf.setInput(data, start, (int) en.compressed);
                    raw = new byte[(int) en.uncompressed];
                    try {
                        int got = 0;
                        while (got < raw.length) {
                            int n = inf.inflate(raw, got, raw.length - got);
                            if (n == 0 && (inf.finished() || inf.needsInput())) break;
                            got += n;
                        }
                    } catch (java.util.zip.DataFormatException ex) {
                        throw new IOException("bad compressed data in " + en.name);
                    }
                } else {
                    continue;
                }
                out.put(en.name, raw);
            }
        }
        inf.end();
        return out;
    }
}
