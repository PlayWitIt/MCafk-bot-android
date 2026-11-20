package app.afkbot;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Shared log + status that the service writes and the screen shows. */
final class Hub {
    private Hub() {
    }

    interface Listener {
        void onChange();
    }

    static final int GREY = 0xFF9E9E9E, RED = 0xFFE53935, YELLOW = 0xFFFFB300, GREEN = 0xFF43A047;

    private static final LinkedList<String> LOG = new LinkedList<String>();
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static volatile String status = "Stopped";
    static volatile String detail = "";
    static volatile int color = GREY;
    static volatile boolean running = false;

    // Sign-in screen state
    static volatile String authCode = null;
    static volatile String authUrl = null;
    static volatile String authError = null;

    // Live stats for the dashboard
    static volatile float statHealth = 20;
    static volatile String statPos = "-", statUptime = "-", statNextAction = "-", statNearest = "none";
    static volatile int statActions;
    static volatile int serverViewDistance;
    /** The running bot (for sending chat), or null. */
    static volatile Bot bot;
    static volatile Bot.Config config;

    private static final LinkedList<String> CHAT = new LinkedList<String>();
    static volatile int chatVersion;

    static void chat(String msg) {
        String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + "  " + msg;
        }
        synchronized (CHAT) {
            CHAT.add(line);
            while (CHAT.size() > 300) CHAT.removeFirst();
        }
        chatVersion++;
        fire();
    }

    static String chatText() {
        StringBuilder sb = new StringBuilder();
        synchronized (CHAT) {
            for (String s : CHAT) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** Last few log lines, newest last. */
    static java.util.List<String> recentLog(int n) {
        synchronized (LOG) {
            int from = Math.max(0, LOG.size() - n);
            return new ArrayList<String>(LOG.subList(from, LOG.size()));
        }
    }

    static void log(String msg) {
        String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + "  " + msg;
        }
        synchronized (LOG) {
            LOG.add(line);
            while (LOG.size() > 400) LOG.removeFirst();
        }
        android.util.Log.i("AFKBot", msg);
        fire();
    }

    static void status(String s, int c) {
        status = s;
        color = c;
        fire();
    }

    static void detail(String d) {
        detail = d;
        fire();
    }

    static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (String s : LOG) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    static void addListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.add(l);
        }
    }

    static void removeListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    interface AlertSink {
        void alert(String title, String text);
    }

    /** Set by the service; shows a phone notification for important events. */
    static volatile AlertSink alertSink;

    static void alert(String title, String text) {
        AlertSink a = alertSink;
        if (a != null) a.alert(title, text);
    }

    static void post(Runnable r) {
        MAIN.post(r);
    }

    static void fire() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (LISTENERS) {
                    copy = new ArrayList<Listener>(LISTENERS);
                }
                for (Listener l : copy) l.onChange();
            }
        });
    }
}
