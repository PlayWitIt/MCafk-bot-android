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
        // what the app shows for it: a settings form (mod.settings), buttons (mod.buttons) and status lines (mod.status)
        volatile JSONArray settingsSpec, buttons, status;
        /** Its status lines also show on the Play screen (top left). */
        volatile boolean statusOnScreen = true;
        /** Chat commands it handles ("hh" for /hh ...): typed in the app, they go to the mod, not the server. */
        final java.util.Set<String> commands = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        volatile JSONObject config;

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

    private static final int BUDGET = 20_000_000; // instructions per event/timer call (a big farm scan fits; an endless loop is stopped within a second or two)
    private static final int MAX_ERRORS = 5;

    static volatile Host host;
    private static final Map<String, Mod> MODS = new LinkedHashMap<String, Mod>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static ScheduledExecutorService timer;
    static volatile int version; // bumps when the mod list/state changes (for the UI)
    static volatile int statusVersion; // bumps when a mod's status lines change (shown in place, no rebuild)

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
            // ---- settings, buttons and status the app shows; walking, clicking, inventory and the world
            "var __lastButtons = null, __lastStatus = null;\n" +
            "mod.config = {};\n" +
            "mod.settings = function(spec) { mod.config = JSON.parse(__api('settings', JSON.stringify(spec))); return mod.config; };\n" +
            "mod.saveSettings = function() { __api('saveConfig', JSON.stringify(mod.config)); };\n" +
            "mod.buttons = function(list) { var j = JSON.stringify(list || []); if (j !== __lastButtons) { __lastButtons = j; __api('buttons', j); } };\n" +
            "mod.status = function(lines, opts) { var j = JSON.stringify({lines: lines || [], screen: !(opts && opts.screen === false)}); if (j !== __lastStatus) { __lastStatus = j; __api('status', j); } };\n" +
            "mod.say = function(t) { __api('say', String(t)); };\n" +
            "var __cmds = {};\n" +
            "mod.command = function(names, fn) { if (typeof names === 'string') names = [names]; for (var i = 0; i < names.length; i++) { var n = String(names[i]).toLowerCase(); __cmds[n] = fn; __api('command', n); } };\n" +
            "function __command(name, args) { var f = __cmds[name]; if (f) f(args === '' ? [] : args.split(' ').filter(function(s) { return s.length > 0; })); }\n" +
            "function __setcfg(k, j) { mod.config[k] = JSON.parse(j); __dispatch('settings', JSON.stringify({key: k, value: mod.config[k]})); }\n" +
            "bot.body = function() { var j = __api('body'); return j ? JSON.parse(j) : null; };\n" +
            "bot.control = function(on) { __api('control', !!on); };\n" +
            "bot.look = function(yaw, pitch) { __api('look', yaw, pitch); };\n" +
            "bot.lookAt = function(x, y, z) { var b = bot.body(); if (!b) return; var dx = x - b.x, dy = y - (b.y + 1.62), dz = z - b.z;\n" +
            "  bot.look(Math.atan2(dz, dx) * 180 / Math.PI - 90, -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI); };\n" +
            "bot.hit = function(x, y, z, face) { return __api('hit', x, y, z, face === undefined ? 1 : face); };\n" +
            "bot.mine = function(x, y, z, face) { __api('mine', x, y, z, face === undefined ? 1 : face); };\n" +
            "bot.mining = function() { return __api('mining'); };\n" +
            "bot.stopMining = function() { __api('stopMining'); };\n" +
            "bot.useOn = function(x, y, z, face, cx, cy, cz) { __api('useOn', x, y, z, face, cx === undefined ? 0.5 : cx, cy === undefined ? 0.5 : cy, cz === undefined ? 0.5 : cz); };\n" +
            "bot.useItem = function() { __api('useItem'); };\n" +
            "bot.releaseUse = function() { __api('releaseUse'); };\n" +
            "bot.swing = function() { __api('swing'); };\n" +
            "var inv = {\n" +
            "  items: function() { return JSON.parse(__api('inv')); },\n" +
            "  held: function() { return __api('held'); },\n" +
            "  select: function(i) { __api('select', i); },\n" +
            "  swap: function(i, hotbar) { __api('swap', i, hotbar); },\n" +
            "  container: function() { var j = __api('container'); return j ? JSON.parse(j) : null; },\n" +
            "  quickMove: function(slot) { return __api('quickMove', slot); },\n" +
            "  close: function() { __api('closeContainer'); }\n" +
            "};\n" +
            "var world = {\n" +
            "  block: function(x, y, z) { return __api('block', x, y, z); },\n" +
            "  prop: function(x, y, z, p) { return __api('prop', x, y, z, p); },\n" +
            "  passable: function(x, y, z) { return __api('passable', x, y, z); },\n" +
            "  standable: function(x, y, z) { return __api('standable', x, y, z); },\n" +
            "  wet: function(x, y, z) { return __api('wet', x, y, z); },\n" +
            "  fluid: function(x, y, z) { return __api('fluid', x, y, z); },\n" +
            "  replaceable: function(x, y, z) { return __api('replaceable', x, y, z); },\n" +
            "  hardness: function(x, y, z) { return __api('hardness', x, y, z); },\n" +
            "  blockEntity: function(x, y, z) { return __api('blockEntity', x, y, z); },\n" +
            "  solidSide: function(x, y, z, dir) { return __api('solidSide', x, y, z, dir); },\n" +
            "  loaded: function(x, z) { return __api('loaded', x, z); },\n" +
            "  waterNear: function(x, y, z, r, dy) { return __api('waterNear', x, y, z, r, dy === undefined ? 0 : dy); },\n" +
            "  lookedAt: function() { var j = __api('lookedAt'); return j ? JSON.parse(j) : null; },\n" +
            "  outlineTop: function(x, y, z) { return __api('outlineTop', x, y, z); },\n" +
            "  predict: function(x, y, z, id) { __api('predict', x, y, z, id); },\n" +
            "  items: function() { return JSON.parse(__api('items')).map(function(a) { return {entity: a[0], id: a[1], count: a[2], x: a[3], y: a[4], z: a[5], age: a[6]}; }); },\n" +
            "  mobs: function(r) { return JSON.parse(__api('mobs', r)); },\n" +
            "  flood: function(x, y, z, r) { return JSON.parse(__api('flood', x, y, z, r)); }\n" +
            "};\n" +
            "var nav = {\n" +
            "  plan: function(goal, bounds) { return __api('navPlan', JSON.stringify(goal), JSON.stringify(bounds)); },\n" +
            "  tick: function() { return __api('navTick'); },\n" +
            "  arrived: function() { return __api('navArrived'); },\n" +
            "  moving: function() { return __api('navMoving'); },\n" +
            "  stop: function() { __api('navStop'); },\n" +
            "  clear: function() { __api('navClear'); },\n" +
            "  sprint: function(on) { __api('navSprint', !!on); },\n" +
            "  careful: function(on) { __api('navCareful', !!on); },\n" +
            "  magmaOk: function(on) { __api('magmaOk', !!on); }\n" +
            "};\n" +
            "function __dispatch(ev, json) { var hs = __h[ev]; if (!hs) return; var d = json ? JSON.parse(json) : {};\n" +
            "  for (var i = 0; i < hs.length; i++) hs[i](d); }\n" +
            "function __has(ev) { return !!__h[ev]; }\n" +
            "function __tick() { var hs = __h.tick; if (!hs) return; for (var i = 0; i < hs.length; i++) hs[i]({}); }\n" +
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
                    case "command":
                        mod.commands.add(arg(args, 1));
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
                    default: {
                        Object r = ModBridge.call(mod, fn, args);
                        if (r == ModBridge.UNHANDLED) return Undefined.instance;
                        if (r instanceof Integer) return ((Integer) r).doubleValue();
                        return r;
                    }
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
                m.status = null;
                m.buttons = null;
                releaseBot();
                version++;
            }
        });
    }

    /** A mod stopped: the bot walks for nobody now (anti-AFK takes over again). */
    private static void releaseBot() {
        Bot b = Hub.bot;
        if (b == null || !b.modControl) return;
        for (Mod o : all()) if (o.running && o.status != null) return; // another mod may still be using it
        b.modControl = false;
        b.nav.clear();
    }

    // ---------------------------------------------------------------- the app's side

    /** You changed a setting in the mod's settings form. */
    static void setConfig(final Mod m, final String key, final Object value) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject c = m.config == null ? new JSONObject() : m.config;
                    c.put(key, value);
                    m.config = c;
                    m.storage.put("__config", c.toString());
                    saveStorage(m);
                    String arr = new JSONArray().put(value).toString(); // the value as JSON: "[v]" without the brackets
                    if (m.running) call(m, "__setcfg", new Object[]{key, arr.substring(1, arr.length() - 1)});
                } catch (org.json.JSONException ignored) {
                }
                version++;
            }
        });
    }

    /**
     * A chat line you typed: if it's a command a running mod registered (/hh ...), the mod gets it and true comes
     * back (it isn't sent to the server).
     */
    static boolean handleCommand(String text) {
        if (text == null || !text.startsWith("/")) return false;
        String body = text.substring(1).trim();
        int sp = body.indexOf(' ');
        final String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ROOT);
        final String args = sp < 0 ? "" : body.substring(sp + 1).trim();
        for (final Mod m : all()) {
            if (!m.running || !m.commands.contains(name)) continue;
            EXEC.execute(new Runnable() {
                @Override
                public void run() {
                    if (m.running) call(m, "__command", new Object[]{name, args});
                }
            });
            return true;
        }
        return false;
    }

    /** You pressed one of the mod's buttons. */
    static void press(final Mod m, final String id) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (m.running) call(m, "__dispatch", new Object[]{"button", new JSONObject().put("id", id).toString()});
                } catch (org.json.JSONException ignored) {
                }
            }
        });
    }

    static void saveStorage(Mod m) {
        Host h = host;
        if (h != null) h.saveStorage(m.id, m.storage.toString());
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TICK_QUEUED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Every game tick (20 a second): the "tick" event for mods that listen to it. If mods are still busy with the
     * last one, this one is skipped rather than piling up.
     */
    static void tick() {
        boolean any = false;
        for (Mod m : all()) any |= m.running;
        if (!any || !TICK_QUEUED.compareAndSet(false, true)) return;
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (Mod m : all()) {
                        if (m.running) call(m, "__tick", null);
                    }
                } finally {
                    TICK_QUEUED.set(false);
                }
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
        if (args == null) args = new Object[0];
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
                m.status = null;
                m.buttons = null;
                releaseBot();
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
