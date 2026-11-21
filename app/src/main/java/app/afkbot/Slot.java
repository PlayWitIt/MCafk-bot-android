package app.afkbot;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Bot slots: up to {@link #MAX} bots, each with its own account and settings, all able to run at once.
 * <p>
 * Each slot lives in its own Android process (slot 0 in the app's main process, slot n in ":botn"). A process has
 * its own copy of every static field, so each bot keeps its own world, map, inventory, chat and log without the rest
 * of the app having to know there are several. What crosses between slots goes through small files in
 * files/slots/: which slots exist (n.on) and each one's live status (n.status), which the switcher at the top of the
 * screen shows.
 */
final class Slot {
    private Slot() {
    }

    static final int MAX = 4;

    private static int current = -1;

    /** This process's slot: 0 for the main process, n for ":botn". */
    static int current() {
        if (current < 0) {
            String p = processName();
            int s = 0;
            int i = p == null ? -1 : p.lastIndexOf(":bot");
            if (i >= 0) {
                try {
                    s = Integer.parseInt(p.substring(i + 4));
                } catch (NumberFormatException ignored) {
                }
            }
            current = s >= 0 && s < MAX ? s : 0;
        }
        return current;
    }

    /** This process's name (Android: the package name, plus ":botn" for slots 1..3). Plain Java, so desktop tests run it. */
    private static String processName() {
        String forced = System.getProperty("afk.process"); // desktop tests run slot n as "app.afkbot:botn"
        if (forced != null) return forced;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath());
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Names of per-slot preference files: slot 0 keeps the original names, so existing sign-ins and settings stay. */
    static String prefs(String base) {
        int s = current();
        return s == 0 ? base : base + "_bot" + s;
    }

    /** A per-slot folder name, for data a slot writes (slot 0 keeps the original name). */
    static String dir(String base) {
        return prefs(base);
    }

    // ---------------------------------------------------------------- the classes of each slot (one per process)

    // Slot n's screens and service are subclasses (MainActivity.Bot1 ...) declared in the manifest with
    // android:process=":botn"; slot 0 uses the classes themselves.
    private static Class<?> slotClass(String base, int slot) {
        try {
            return Class.forName("app.afkbot." + base + (slot == 0 ? "" : "$Bot" + slot));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The main screen of a slot. */
    static Class<?> mainActivity(int slot) {
        return slotClass("MainActivity", slot);
    }

    /** This slot's Play screen. */
    static Class<?> gameActivity() {
        return slotClass("GameActivity", current());
    }

    /** This slot's bot service. */
    static Class<?> service() {
        return slotClass("AfkService", current());
    }

    // ---------------------------------------------------------------- which slots exist, and their status

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "slots");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Whether a slot has been added (slot 0 always exists). */
    static boolean exists(Context c, int slot) {
        return slot == 0 || new File(dir(c), slot + ".on").exists();
    }

    /** Adds the first free slot and returns it, or -1 if all are in use. */
    static int add(Context c) {
        for (int s = 1; s < MAX; s++) {
            if (!exists(c, s)) {
                try {
                    write(new File(dir(c), s + ".on"), "1");
                } catch (IOException e) {
                    return -1;
                }
                new File(dir(c), s + ".status").delete();
                return s;
            }
        }
        return -1;
    }

    /** Removes this process's slot (never slot 0). Its sign-in and settings are cleared by the caller first. */
    static void removeCurrent(Context c) {
        int s = current();
        if (s == 0) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), s + ".on").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), s + ".status").delete();
    }

    /** A slot's status as the switcher shows it. */
    static final class Status {
        String name = "";
        String status = "Stopped";
        int color = Hub.GREY;
        boolean running;
        long at;

        /** Running, but no heartbeat for a minute: its process is gone. */
        boolean stale() {
            return running && System.currentTimeMillis() - at > 60000;
        }
    }

    /** How long a running bot waits between heartbeats. */
    static final long HEARTBEAT_MS = 15000;

    private static String lastWritten = "";

    /**
     * Saves this slot's status for the other slots' switchers (cheap; skipped when nothing changed unless force, which
     * a running bot uses as a heartbeat so a slot whose process died isn't shown as connected forever).
     */
    static synchronized void publish(Context c, String name, String status, int color, boolean running, boolean force) {
        String line = (name == null ? "" : name.replace('\n', ' ')) + "\n" + status.replace('\n', ' ') + "\n" + color + "\n" + running;
        if (line.equals(lastWritten) && !force) return;
        lastWritten = line;
        try {
            write(new File(dir(c), current() + ".status"), line + "\n" + System.currentTimeMillis());
        } catch (IOException ignored) {
        }
    }

    /** A slot's last published status, or a blank "Stopped" one. */
    static Status read(Context c, int slot) {
        Status st = new Status();
        File f = new File(dir(c), slot + ".status");
        try {
            String[] l = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
            if (l.length >= 4) {
                st.name = l[0];
                st.status = l[1];
                st.color = Integer.parseInt(l[2]);
                st.running = Boolean.parseBoolean(l[3]);
                if (l.length >= 5) st.at = Long.parseLong(l[4].trim());
            }
        } catch (Exception ignored) {
        }
        return st;
    }

    // ---------------------------------------------------------------- safe writes when several processes share a file

    /** A temp file next to f that no other process uses. */
    static File temp(File f) {
        return new File(f.getPath() + "." + pid() + ".tmp");
    }

    private static String pid;

    private static String pid() {
        if (pid == null) {
            try {
                pid = new File("/proc/self").getCanonicalFile().getName();
            } catch (IOException e) {
                pid = Long.toString(System.nanoTime());
            }
        }
        return pid;
    }

    /** Writes a file all at once (temp file, then rename), so another process never reads half of it. */
    static void write(File f, byte[] data) throws IOException {
        File tmp = temp(f);
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("couldn't save " + f.getName());
        }
    }

    static void write(File f, String text) throws IOException {
        write(f, text.getBytes(StandardCharsets.UTF_8));
    }
}
