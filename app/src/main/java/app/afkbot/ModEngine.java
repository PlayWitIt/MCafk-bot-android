package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs script mods (JavaScript) in a sandbox: no Java classes, no files, no network, an instruction
 * budget per call, and automatic shutdown of mods that keep failing. All mod code runs on one thread.
 */
final class ModEngine {
    private ModEngine() {
    }

    /** What mods can see and do. Implemented by the app (Bot/World/Hub). */
    interface Host {
        String name();

        String server();

        boolean online();

        double[] pos(); // x, y, z, yaw

        float health();

        List<World.Dot> entities();

        void chat(String text);

        void jump();

        void disconnect(String reason);

        void log(String modName, String msg);

        void alert(String title, String text);

        void saveStorage(String modId, String json);
    }

    static final class Mod {
        final String id;
        String name, description = "", version = "", author = "", code;
        volatile boolean running;
        String lastError;
        int errors;
        final LinkedList<String> log = new LinkedList<String>();
        Scriptable scope;
        JSONObject storage = new JSONObject();
        long chatWindowStart;
        int chatInWindow;
        long lastChat;

        Mod(String id, String code) {
            this.id = id;
            setCode(code);
        }

        void setCode(String c) {
            code = c;
            name = meta(c, "name", id);
            description = meta(c, "description", "");
            version = meta(c, "version", "");
            author = meta(c, "author", "");
        }

        String logText() {
            StringBuilder sb = new StringBuilder();
            synchronized (log) {
                for (String s : log) sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    /** Reads "// @key value" headers. */
    static String meta(String code, String key, String def) {
        for (String line : code.split("\n", 40)) {
            String t = line.trim();
            if (!t.startsWith("//")) {
                if (t.isEmpty()) continue;
                break;
            }
            t = t.substring(2).trim();
            if (t.startsWith("@" + key + " ") || t.equals("@" + key)) return t.substring(key.length() + 1).trim();
        }
        return def;
    }

    private static final int BUDGET = 3_000_000; // instructions per event/timer call
    private static final int MAX_ERRORS = 5;

    static volatile Host host;
    private static final Map<String, Mod> MODS = new LinkedHashMap<String, Mod>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static ScheduledExecutorService timer;
    static volatile int version; // bumps when the mod list/state changes (for the UI)

    // ---------------------------------------------------------------- sandboxed Rhino

    private static final class Budget {
        long used;
    }

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1); // interpreter (Android can't load generated classes)
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(20000);
            cx.setClassShutter(new ClassShutter() {
                @Override
                public boolean visibleToScripts(String className) {
                    return false; // no Java access at all
                }
            });
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int count) {
            Budget b = (Budget) cx.getThreadLocal("budget");
            if (b != null && (b.used += count) > BUDGET) throw new Error("mod used too much time (endless loop?)");
        }
    };

    private static final String PRELUDE =
            "var __h = {}, __t = [], __tid = 0;\n" +
            "var mod = {\n" +
            "  on: function(ev, fn) { if (typeof fn !== 'function') throw new Error('mod.on needs a function'); (__h[ev] = __h[ev] || []).push(fn); },\n" +
            "  every: function(ms, fn) { ms = Math.max(250, ms | 0); var t = {id: ++__tid, ms: ms, next: Date.now() + ms, fn: fn, rep: true}; __t.push(t); return t.id; },\n" +
            "  after: function(ms, fn) { ms = Math.max(0, ms | 0); var t = {id: ++__tid, ms: ms, next: Date.now() + ms, fn: fn, rep: false}; __t.push(t); return t.id; },\n" +
            "  cancel: function(id) { __t = __t.filter(function(t) { return t.id !== id; }); },\n" +
            "  storage: {\n" +
            "    get: function(k, d) { var v = __api('sget', String(k)); return (v === null || v === undefined) ? d : JSON.parse(v); },\n" +
            "    set: function(k, v) { __api('sset', String(k), v === undefined ? null : JSON.stringify(v)); }\n" +
            "  }\n" +
            "};\n" +
            "var bot = {\n" +
            "  get name() { return __api('name'); },\n" +
            "  get server() { return __api('server'); },\n" +
            "  online: function() { return __api('online'); },\n" +
            "  pos: function() { return JSON.parse(__api('pos')); },\n" +
            "  health: function() { return __api('health'); },\n" +
            "  players: function() { return JSON.parse(__api('entities', 'p')); },\n" +
            "  entities: function() { return JSON.parse(__api('entities', '')); },\n" +
            "  chat: function(t) { __api('chat', String(t)); },\n" +
            "  command: function(c) { c = String(c); __api('chat', c.charAt(0) === '/' ? c : '/' + c); },\n" +
            "  jump: function() { __api('jump'); },\n" +
            "  log: function(m) { __api('log', String(m)); },\n" +
            "  alert: function(t, m) { __api('alert', String(t), m === undefined ? '' : String(m)); },\n" +
            "  disconnect: function(r) { __api('disconnect', String(r || 'disconnected by a mod')); }\n" +
            "};\n" +
            "var console = { log: function() { bot.log(Array.prototype.join.call(arguments, ' ')); } };\n" +
            "function __dispatch(ev, json) { var hs = __h[ev]; if (!hs) return; var d = json ? JSON.parse(json) : {};\n" +
            "  for (var i = 0; i < hs.length; i++) hs[i](d); }\n" +
            "function __has(ev) { return !!__h[ev]; }\n" +
            "function __timers(now) { var due = __t.filter(function(t) { return t.next <= now; });\n" +
            "  for (var i = 0; i < due.length; i++) { var t = due[i]; if (t.rep) t.next = now + t.ms; else mod.cancel(t.id); t.fn(); } }\n";

    /** Native bridge: __api(name, args...). */
    private static final class Api extends BaseFunction {
        final Mod mod;

        Api(Mod mod) {
            this.mod = mod;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            String fn = args.length > 0 ? Context.toString(args[0]) : "";
            Host h = host;
            if (h == null) return Undefined.instance;
            try {
                switch (fn) {
                    case "name":
                        return h.name();
                    case "server":
                        return h.server();
                    case "online":
                        return h.online();
                    case "health":
                        return (double) h.health();
                    case "pos": {
                        double[] p = h.pos();
                        return new JSONObject().put("x", p[0]).put("y", p[1]).put("z", p[2]).put("yaw", p[3]).toString();
                    }
                    case "entities": {
                        String kind = arg(args, 1);
                        JSONArray a = new JSONArray();
                        for (World.Dot d : h.entities()) {
                            if (!kind.isEmpty() && kind.charAt(0) != d.kind) continue;
                            a.put(new JSONObject().put("name", d.label).put("kind", kindName(d.kind)).put("distance", round(d.dist))
                                    .put("dx", round(d.dx)).put("dy", round(d.dy)).put("dz", round(d.dz)).put("friend", d.friend));
                            if (a.length() >= 200) break;
                        }
                        return a.toString();
                    }
                    case "chat":
                        chat(mod, arg(args, 1));
                        return Undefined.instance;
                    case "jump":
                        h.jump();
                        return Undefined.instance;
                    case "log":
                        modLog(mod, arg(args, 1));
                        return Undefined.instance;
                    case "alert":
                        h.alert(mod.name + ": " + arg(args, 1), arg(args, 2));
                        return Undefined.instance;
                    case "disconnect":
                        modLog(mod, "requested disconnect: " + arg(args, 1));
                        h.disconnect(mod.name + ": " + arg(args, 1));
                        return Undefined.instance;
                    case "sget": {
                        String k = arg(args, 1);
                        return mod.storage.has(k) ? mod.storage.get(k).toString() : null;
                    }
                    case "sset": {
                        String k = arg(args, 1);
                        Object v = args.length > 2 ? args[2] : null;
                        if (v == null || v instanceof Undefined) mod.storage.remove(k);
                        else mod.storage.put(k, Context.toString(v));
                        if (mod.storage.toString().length() > 200_000) {
                            mod.storage.remove(k);
                            throw new Error("mod storage is full (200 KB)");
                        }
                        h.saveStorage(mod.id, mod.storage.toString());
                        return Undefined.instance;
                    }
                    default:
                        return Undefined.instance;
                }
            } catch (org.json.JSONException e) {
                return Undefined.instance;
            }
        }

        private static String arg(Object[] a, int i) {
            return a.length > i && a[i] != null && !(a[i] instanceof Undefined) ? Context.toString(a[i]) : "";
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String kindName(char k) {
        switch (k) {
            case 'p':
                return "player";
            case 'h':
                return "hostile";
            case 'a':
                return "animal";
            case 'i':
                return "item";
            default:
                return "other";
        }
    }

    /** Chat from mods is rate-limited (1 message per 1.5 s, 15 per minute) so a mod can't get you kicked for spam. */
    private static void chat(final Mod m, final String text) {
        long now = System.currentTimeMillis();
        if (now - m.chatWindowStart > 60_000) {
            m.chatWindowStart = now;
            m.chatInWindow = 0;
        }
        if (m.chatInWindow >= 15) {
            modLog(m, "chat blocked (more than 15 messages a minute): " + text);
            return;
        }
        m.chatInWindow++;
        long sendAt = Math.max(now, m.lastChat + 1500);
        if (sendAt - now > 8000) {
            modLog(m, "chat blocked (too many queued): " + text);
            return;
        }
        m.lastChat = sendAt;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Host h = host;
                if (h != null) h.chat(text);
            }
        };
        if (sendAt <= now) r.run();
        else {
            ensureTimer();
            timer.schedule(r, sendAt - now, TimeUnit.MILLISECONDS);
        }
    }

    static void modLog(Mod m, String msg) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + msg;
        synchronized (m.log) {
            m.log.add(line);
            while (m.log.size() > 100) m.log.removeFirst();
        }
        Host h = host;
        if (h != null) h.log(m.name, msg);
        version++;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts (or restarts) a mod. */
    static void start(final Mod m, final String storageJson) {
        synchronized (MODS) {
            MODS.put(m.id, m);
        }
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                m.errors = 0;
                m.lastError = null;
                try {
                    m.storage = storageJson == null || storageJson.isEmpty() ? new JSONObject() : new JSONObject(storageJson);
                } catch (Exception e) {
                    m.storage = new JSONObject();
                }
                Context cx = FACTORY.enterContext();
                try {
                    cx.putThreadLocal("budget", new Budget());
                    ScriptableObject scope = cx.initSafeStandardObjects();
                    ScriptableObject.putProperty(scope, "__api", new Api(m));
                    cx.evaluateString(scope, PRELUDE, "prelude", 1, null);
                    cx.evaluateString(scope, m.code, m.name, 1, null);
                    m.scope = scope;
                    m.running = true;
                    modLog(m, "started");
                } catch (Throwable t) {
                    m.running = false;
                    m.scope = null;
                    m.lastError = describe(t);
                    modLog(m, "failed to start: " + m.lastError);
                } finally {
                    Context.exit();
                    version++;
                }
            }
        });
        ensureTimer();
    }

    static void stop(final Mod m) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                if (m.running) modLog(m, "stopped");
                m.running = false;
                m.scope = null;
                version++;
            }
        });
    }

    static void remove(String id) {
        Mod m;
        synchronized (MODS) {
            m = MODS.remove(id);
        }
        if (m != null) stop(m);
    }

    static Mod get(String id) {
        synchronized (MODS) {
            return MODS.get(id);
        }
    }

    static List<Mod> all() {
        synchronized (MODS) {
            return new ArrayList<Mod>(MODS.values());
        }
    }

    /** Registers a mod without starting it (so it shows in the list). */
    static void register(Mod m) {
        synchronized (MODS) {
            MODS.put(m.id, m);
        }
        version++;
    }

    private static synchronized void ensureTimer() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                final long now = System.currentTimeMillis();
                for (final Mod m : all()) {
                    if (!m.running) continue;
                    EXEC.execute(new Runnable() {
                        @Override
                        public void run() {
                            call(m, "__timers", new Object[]{(double) now});
                        }
                    });
                }
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** Sends an event to every running mod. data may be null. */
    static void emit(final String event, final JSONObject data) {
        boolean any = false;
        for (Mod m : all()) any |= m.running;
        if (!any) return;
        final String json = data == null ? null : data.toString();
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                for (Mod m : all()) {
                    if (m.running) call(m, "__dispatch", new Object[]{event, json});
                }
            }
        });
    }

    /** Must run on EXEC. */
    private static void call(Mod m, String fn, Object[] args) {
        Scriptable scope = m.scope;
        if (scope == null) return;
        Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal("budget", new Budget());
            Object f = ScriptableObject.getProperty(scope, fn);
            if (f instanceof Function) ((Function) f).call(cx, scope, scope, args);
        } catch (Throwable t) {
            m.errors++;
            m.lastError = describe(t);
            modLog(m, "error: " + m.lastError);
            if (m.errors >= MAX_ERRORS) {
                m.running = false;
                m.scope = null;
                modLog(m, "turned off after " + MAX_ERRORS + " errors - fix it and switch it back on");
                Host h = host;
                if (h != null) h.alert("Mod turned off", m.name + ": " + m.lastError);
            }
        } finally {
            Context.exit();
        }
    }

    static String describe(Throwable t) {
        if (t instanceof RhinoException) {
            RhinoException r = (RhinoException) t;
            String msg = r.details();
            return (msg != null ? msg : r.getMessage()) + (r.lineNumber() > 0 ? " (line " + r.lineNumber() + ")" : "");
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    /** Waits until queued mod work is done (tests). */
    static void flush() {
        try {
            EXEC.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
