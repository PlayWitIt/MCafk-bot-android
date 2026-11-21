package app.afkbot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mod API's world, walking, clicking and inventory side (world.*, nav.*, act.*, inv.*), and the parts the app
 * shows (mod.settings, mod.buttons, mod.status, mod.say). Clicks go out inside the bot's next tick, after its move,
 * like a real client's. What a click changes is predicted at once (as the real client does) and the server's answer
 * corrects it. Platform neutral.
 */
final class ModBridge {
    private ModBridge() {
    }

    /** The block interaction reach (the game's default, 4.5 blocks from the eyes). */
    static final double REACH = 4.5;

    /** Returns UNHANDLED for names it doesn't know. */
    static final Object UNHANDLED = new Object();

    static Object call(ModEngine.Mod m, String fn, Object[] a) throws JSONException {
        Bot b = Hub.bot;
        switch (fn) {
            // ---------------------------------------------------------------- the app's side: settings, buttons, status
            case "settings": {
                m.settingsSpec = new JSONArray(s(a, 1));
                JSONObject saved = m.storage.has("__config") ? new JSONObject(m.storage.getString("__config")) : new JSONObject();
                JSONObject cfg = new JSONObject();
                for (int i = 0; i < m.settingsSpec.length(); i++) {
                    JSONObject f = m.settingsSpec.getJSONObject(i);
                    String k = f.optString("key", "");
                    if (k.isEmpty()) continue;
                    cfg.put(k, saved.has(k) ? saved.get(k) : f.opt("default"));
                }
                m.config = cfg;
                ModEngine.version++;
                return cfg.toString();
            }
            case "saveConfig": { // the mod changed its own settings (a command, say)
                m.config = new JSONObject(s(a, 1));
                m.storage.put("__config", m.config.toString());
                ModEngine.saveStorage(m);
                ModEngine.version++;
                return null;
            }
            case "buttons":
                m.buttons = new JSONArray(s(a, 1));
                ModEngine.version++;
                return null;
            case "status": {
                JSONObject st = new JSONObject(s(a, 1));
                m.status = st.optJSONArray("lines");
                m.statusOnScreen = st.optBoolean("screen", true);
                ModEngine.statusVersion++;
                Hub.fire();
                return null;
            }
            case "say": // to you only, in the chat (like a client mod's chat line)
                Hub.chat(s(a, 1));
                return null;

            // ---------------------------------------------------------------- the bot
            case "control": {
                boolean on = bool(a, 1);
                if (b != null) {
                    b.modControl = on;
                    if (!on) b.nav.clear();
                }
                return null;
            }
            case "body": {
                if (b == null || !World.inWorld) return null;
                double[] p = b.modBody();
                float[] yp = b.modLookAngles();
                return new JSONObject().put("x", p[0]).put("y", p[1]).put("z", p[2]).put("yaw", (double) yp[0]).put("pitch", (double) yp[1]).put("onGround", p[3] > 0).put("inWater", p[4] > 0)
                        .put("health", (double) Hub.statHealth).put("food", GameState.food).put("dimension", str(MapData.dimension))
                        .put("server", Hub.config != null ? Hub.config.address : "").put("mode", GameState.gameMode).toString();
            }
            case "look":
                if (b != null) b.modLook((float) num(a, 1), (float) num(a, 2));
                return null;
            case "hit": { // left click: blocks with no hardness (crops) go at once, and are shown gone at once
                final int x = i(a, 1), y = i(a, 2), z = i(a, 3), face = i(a, 4);
                if (b == null) return false;
                final Bot bot = b;
                b.modDo(new Runnable() {
                    public void run() {
                        try {
                            bot.sendDig(0, x, y, z, face);
                            bot.sendSwing();
                        } catch (IOException ignored) {
                        }
                    }
                });
                int st = MapData.stateAt(x, y, z);
                if (st > 0 && ModWorld.hardness(st) == 0) {
                    MapData.setBlock(x, y, z, 0);
                    return true;
                }
                return st == 0;
            }
            case "mine":
                if (b != null) b.modMine(i(a, 1), i(a, 2), i(a, 3), i(a, 4));
                return null;
            case "mining":
                return b != null && b.modMining();
            case "stopMining":
                if (b != null) b.modStopMining();
                return null;
            case "useOn": { // right click on a block at a point (0..1 on it)
                final int x = i(a, 1), y = i(a, 2), z = i(a, 3), face = i(a, 4);
                final float cx = (float) num(a, 5), cy = (float) num(a, 6), cz = (float) num(a, 7);
                if (b == null) return null;
                final Bot bot = b;
                Inv.modOpenAt = System.currentTimeMillis(); // a container that opens now is the mod's, not yours
                b.modDo(new Runnable() {
                    public void run() {
                        try {
                            bot.sendUseOn(x, y, z, face, cx, cy, cz);
                            bot.sendSwing();
                        } catch (IOException ignored) {
                        }
                    }
                });
                return null;
            }
            case "useItem":
            case "releaseUse":
            case "swing": {
                if (b == null) return null;
                final Bot bot = b;
                final String what = fn;
                b.modDo(new Runnable() {
                    public void run() {
                        try {
                            if (what.equals("useItem")) bot.sendUseItem();
                            else if (what.equals("releaseUse")) bot.sendReleaseUse();
                            else bot.sendSwing();
                        } catch (IOException ignored) {
                        }
                    }
                });
                return null;
            }

            // ---------------------------------------------------------------- inventory and containers
            case "inv":
                return invJson().toString();
            case "held":
                return (double) Inv.held;
            case "select": {
                final int slot = i(a, 1);
                if (b == null || slot < 0 || slot > 8) return null;
                Inv.held = slot;
                Inv.version++;
                final Bot bot = b;
                b.modDo(new Runnable() {
                    public void run() {
                        try {
                            bot.sendHeldSlot(slot);
                        } catch (IOException ignored) {
                        }
                    }
                });
                return null;
            }
            case "swap": // your inventory slot (0-35, 40 off hand) <-> hotbar slot, as pressing a number key over it
                swap(b, i(a, 1), i(a, 2));
                return null;
            case "container":
                return containerJson();
            case "quickMove": // shift-click a slot of the open container window
                return quickMove(b, i(a, 1));
            case "closeContainer": {
                final int wid = Inv.openId;
                Inv.openId = -1;
                Inv.openedByMod = false;
                Inv.version++;
                if (b != null && wid >= 0) {
                    final Bot bot = b;
                    b.modDo(new Runnable() {
                        public void run() {
                            try {
                                bot.sendCloseWindow(wid);
                            } catch (IOException ignored) {
                            }
                        }
                    });
                }
                return null;
            }

            // ---------------------------------------------------------------- the world
            case "block":
                return ModWorld.id(i(a, 1), i(a, 2), i(a, 3));
            case "prop":
                return ModWorld.prop(i(a, 1), i(a, 2), i(a, 3), s(a, 4));
            case "passable":
                return ModWorld.passable(i(a, 1), i(a, 2), i(a, 3));
            case "standable":
                return ModWorld.standable(i(a, 1), i(a, 2), i(a, 3));
            case "wet":
                return ModWorld.wet(i(a, 1), i(a, 2), i(a, 3));
            case "fluid": {
                char f = ModWorld.fluid(ModWorld.state(i(a, 1), i(a, 2), i(a, 3)));
                return f == 'w' ? "water" : f == 'l' ? "lava" : null;
            }
            case "replaceable":
                return ModWorld.replaceable(ModWorld.state(i(a, 1), i(a, 2), i(a, 3)));
            case "hardness":
                return ModWorld.hardness(ModWorld.state(i(a, 1), i(a, 2), i(a, 3)));
            case "blockEntity":
                return ModWorld.hasBlockEntity(ModWorld.state(i(a, 1), i(a, 2), i(a, 3)));
            case "solidSide":
                return ModWorld.solidSide(i(a, 1), i(a, 2), i(a, 3), i(a, 4));
            case "lookedAt": { // the block you look at (the Play screen's crosshair), within 5 blocks: [x, y, z, face] or null
                if (b == null) return null;
                double[] p = b.modBody();
                float[] yp = b.modLookAngles();
                double yaw = Math.toRadians(yp[0]), pitch = Math.toRadians(yp[1]);
                double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
                int[] hit = GameRay.cast(p[0], p[1] + 1.62, p[2], dx, dy, dz, 5);
                return hit == null ? null : new JSONArray().put(hit[0]).put(hit[1]).put(hit[2]).put(hit[3]).toString();
            }
            case "waterNear": // water within r blocks across, at this height or dy above (farmland stays wet)
                return waterNear(i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5));
            case "loaded":
                return MapData.loaded(i(a, 1), i(a, 2));
            case "outlineTop": { // top of a block's shape (where a seed lands on farmland: 15/16)
                int st = ModWorld.state(i(a, 1), i(a, 2), i(a, 3));
                float top = 0;
                for (float[] box : Shapes.outline(st)) top = Math.max(top, box[4]);
                return (double) top;
            }
            case "predict": { // show a block change before the server confirms it (as the real client does for crops)
                int x = i(a, 1), y = i(a, 2), z = i(a, 3);
                String id = s(a, 4);
                int st = id.equals("air") ? 0 : minState(id);
                if (st >= 0) MapData.setBlock(x, y, z, st);
                return null;
            }
            case "items": {
                World.wantItems = true;
                JSONArray out = new JSONArray();
                for (Object[] it : World.items()) {
                    out.put(new JSONArray().put(it[0]).put(it[1] == null ? JSONObject.NULL : it[1]).put(it[2]).put(it[3]).put(it[4]).put(it[5])
                            .put(((Long) it[6]) / 50));
                }
                return out.toString();
            }
            case "mobs": { // living things near you: {type, kind, x, y, z}
                double r = num(a, 1);
                JSONArray out = new JSONArray();
                double px = World.selfX, py = World.selfY, pz = World.selfZ;
                for (World.Dot d : World.snapshot(null)) {
                    if (d.kind == 'i' || d.kind == 'o' || d.dist > r) continue;
                    out.put(new JSONObject().put("type", d.type).put("kind", d.kind == 'p' ? "player" : d.kind == 'h' ? "hostile" : "animal")
                            .put("x", px + d.dx).put("y", py + d.dy).put("z", pz + d.dz).put("name", d.label));
                }
                return out.toString();
            }
            case "flood": {
                List<int[]> cells = ModNav.flood(i(a, 1), i(a, 2), i(a, 3), i(a, 4));
                // each cell: x, y, z, flags, and the block it stands on. Flags: 1 you can stand there out of the water,
                // 2 there's water (or lava) in it, 4 water within 4 blocks of the block under it, level or one up
                // (farmland there stays wet)
                StringBuilder sb = new StringBuilder(cells.size() * 24).append('[');
                for (int k = 0; k < cells.size(); k++) {
                    int[] c = cells.get(k);
                    if (k > 0) sb.append(',');
                    String under = ModWorld.id(c[0], c[1] - 1, c[2]);
                    int flags = c[3];
                    if (ModWorld.fluid(ModWorld.state(c[0], c[1], c[2])) != 0) flags |= 2;
                    if (waterNear(c[0], c[1] - 1, c[2], 4, 1)) flags |= 4;
                    sb.append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(',').append(flags).append(",\"")
                            .append(under == null ? "" : under).append('"');
                }
                return sb.append(']').toString();
            }

            // ---------------------------------------------------------------- walking
            case "navPlan": {
                if (b == null) return false;
                final JSONObject g = new JSONObject(s(a, 1));
                JSONArray bj = new JSONArray(s(a, 2));
                int[] bounds = new int[6];
                for (int k = 0; k < 6; k++) bounds[k] = bj.getInt(k);
                double[] p = b.modBody();
                boolean ok = b.nav.plan(p[0], p[1], p[2], goal(g), bounds);
                if (ok) b.nav.go();
                return ok;
            }
            case "navTick": // keep walking; false if it got stuck (and gave up on the path)
                if (b == null) return false;
                b.nav.go();
                return !b.nav.takeStuck();
            case "navArrived":
                return b == null || b.nav.arrived();
            case "navMoving":
                return b != null && b.nav.moving();
            case "navStop":
                if (b != null) b.nav.stop();
                return null;
            case "navClear":
                if (b != null) b.nav.clear();
                return null;
            case "navSprint":
                if (b != null) b.nav.sprint = bool(a, 1);
                return null;
            case "navCareful":
                if (b != null) b.nav.careful = bool(a, 1);
                return null;
            case "magmaOk":
                ModWorld.magmaOk = bool(a, 1);
                return null;
            default:
                return UNHANDLED;
        }
    }

    static boolean waterNear(int x, int y, int z, int r, int dy) {
        for (int qx = x - r; qx <= x + r; qx++)
            for (int qy = y; qy <= y + dy; qy++)
                for (int qz = z - r; qz <= z + r; qz++) if (ModWorld.water(qx, qy, qz)) return true;
        return false;
    }

    // ---------------------------------------------------------------- goals

    private static ModNav.Goal goal(JSONObject g) throws JSONException {
        final List<int[]> avoid = new ArrayList<int[]>();
        JSONArray av = g.optJSONArray("avoid");
        if (av != null) for (int k = 0; k < av.length(); k++) avoid.add(ints(av.getJSONArray(k)));
        if (g.has("near")) { // stand where the eyes are within reach of a point
            final double[] t = dbls(g.getJSONArray("near"));
            final double r2 = g.getDouble("reach") * g.getDouble("reach");
            return new ModNav.Goal() {
                public boolean test(int x, int y, int z) {
                    for (int[] v : avoid) if (v[0] == x && v[1] == y && v[2] == z) return false;
                    double dx = x + 0.5 - t[0], dy = y + 1.62 - t[1], dz = z + 0.5 - t[2];
                    return dx * dx + dy * dy + dz * dz <= r2;
                }
            };
        }
        if (g.has("column")) { // on the same column, up to a block above or below
            final int[] t = ints(g.getJSONArray("column"));
            return new ModNav.Goal() {
                public boolean test(int x, int y, int z) {
                    return x == t[0] && z == t[2] && Math.abs(y - t[1]) <= 1;
                }
            };
        }
        final int[] t = ints(g.getJSONArray("around")); // within r blocks every way
        final int r = g.optInt("r", 1);
        return new ModNav.Goal() {
            public boolean test(int x, int y, int z) {
                return Math.abs(x - t[0]) <= r && Math.abs(z - t[2]) <= r && Math.abs(y - t[1]) <= r;
            }
        };
    }

    // ---------------------------------------------------------------- items as mods see them

    /** Your inventory in the game's own order: 0-8 hotbar, 9-35 the rest, 36-39 armour (feet first), 40 off hand. */
    static JSONArray invJson() throws JSONException {
        List<Inv.Item> w = Inv.inventory;
        JSONArray out = new JSONArray();
        for (int i = 0; i <= 40; i++) {
            int ws = window0(i);
            Inv.Item it = ws >= 0 && ws < w.size() ? w.get(ws) : null;
            out.put(it == null ? JSONObject.NULL : itemJson(it));
        }
        return out;
    }

    /** Window 0's slot for an inventory index (as invJson numbers them). */
    static int window0(int i) {
        if (i >= 0 && i < 9) return 36 + i;
        if (i < 36) return i;
        if (i < 40) return 8 - (i - 36);
        if (i == 40) return 45;
        return -1;
    }

    static JSONObject itemJson(Inv.Item it) throws JSONException {
        JSONObject o = new JSONObject().put("id", it.key).put("name", it.name).put("count", it.count).put("max", it.maxCount);
        if (it.maxDamage > 0) o.put("damage", it.damage).put("maxDamage", it.maxDamage);
        if (it.enchants != null && !it.enchants.isEmpty()) {
            JSONObject e = new JSONObject();
            for (java.util.Map.Entry<String, Integer> en : it.enchants.entrySet()) e.put(en.getKey(), en.getValue());
            o.put("ench", e);
        }
        if (it.contents != null) {
            JSONArray c = new JSONArray();
            for (Inv.Item x : it.contents) c.put(new JSONObject().put("id", x.key).put("count", x.count).put("max", x.maxCount));
            o.put("contents", c);
        }
        return o;
    }

    private static String containerJson() throws JSONException {
        if (Inv.openId < 0) return null;
        List<Inv.Item> sl = Inv.slots;
        int n = Math.max(0, sl.size() - 36);
        JSONArray slots = new JSONArray();
        for (int k = 0; k < n; k++) slots.put(sl.get(k) == null ? JSONObject.NULL : itemJson(sl.get(k)));
        return new JSONObject().put("id", Inv.openId).put("type", Inv.type).put("size", n).put("byMod", Inv.openedByMod)
                .put("loaded", sl.size() > 0).put("slots", slots).toString();
    }

    /** The open window's slot for your inventory index (0-8 hotbar, 9-35 the rest): after the container's own. */
    static int windowSlot(int inv) {
        int n = Math.max(0, Inv.slots.size() - 36);
        return inv < 9 ? n + 27 + inv : n + inv - 9;
    }

    // ---------------------------------------------------------------- clicks, predicted

    private static void swap(Bot b, final int inv, final int hotbar) {
        if (b == null || hotbar < 0 || hotbar > 8 || Inv.openId >= 0) return;
        final int slot, button;
        if (inv == 40) { // from the off hand: the hotbar slot swaps with it (the F key over it)
            slot = 36 + hotbar;
            button = 40;
        } else {
            slot = window0(inv);
            button = hotbar;
        }
        List<Inv.Item> w = new ArrayList<Inv.Item>(Inv.inventory);
        int hs = 36 + hotbar, os = inv == 40 ? 45 : window0(inv);
        if (hs < w.size() && os >= 0 && os < w.size()) {
            Inv.Item t = w.get(hs);
            w.set(hs, w.get(os));
            w.set(os, t);
            Inv.inventory = w;
            Inv.version++;
        }
        final Bot bot = b;
        b.modDo(new Runnable() {
            public void run() {
                try {
                    bot.sendWindowClick(0, slot, button, 2);
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Shift-click in the open container: moves the stack across. Predicted like the game's own move. */
    private static boolean quickMove(Bot b, final int slot) {
        final int wid = Inv.openId;
        if (b == null || wid < 0) return false;
        List<Inv.Item> sl = new ArrayList<Inv.Item>(Inv.slots);
        int n = Math.max(0, sl.size() - 36);
        if (slot < 0 || slot >= sl.size() || sl.get(slot) == null) return false;
        Inv.Item it = sl.get(slot);
        boolean shulker = Inv.type == SHULKER_MENU || Inv.title.toLowerCase(Locale.ROOT).contains("shulker");
        int left = it.count;
        if (slot >= n) { // yours -> the container, from its first slot
            if (!(shulker && it.key.endsWith("shulker_box"))) left = insert(sl, it, left, 0, n, false);
        } else { // the container's -> yours, from the hotbar's end backwards (as the game does)
            left = insert(sl, it, left, n, sl.size(), true);
        }
        sl.set(slot, Inv.withCount(it, left));
        Inv.slots = sl;
        mirror(sl);
        Inv.version++;
        final Bot bot = b;
        b.modDo(new Runnable() {
            public void run() {
                try {
                    bot.sendWindowClick(wid, slot, 0, 1);
                } catch (IOException ignored) {
                }
            }
        });
        return left != it.count;
    }

    /** An open window's last 36 slots are your inventory: keep window 0 in step (as Bot does for the server's updates). */
    private static void mirror(List<Inv.Item> win) {
        List<Inv.Item> inv = Inv.inventory;
        if (win.size() < 36 || inv.size() < 45) return;
        List<Inv.Item> copy = new ArrayList<Inv.Item>(inv);
        for (int k = 0; k < 36; k++) copy.set(9 + k, win.get(win.size() - 36 + k));
        Inv.inventory = copy;
    }

    /** The game's menu id for a shulker box (1.21.x menu registry). */
    static final int SHULKER_MENU = 20;

    /** The game's insertItem: first onto stacks of the same item, then into empty slots. Returns what's left. */
    private static int insert(List<Inv.Item> sl, Inv.Item it, int count, int from, int to, boolean backwards) {
        for (int pass = 0; pass < 2 && count > 0; pass++) {
            for (int k = backwards ? to - 1 : from; backwards ? k >= from : k < to; k += backwards ? -1 : 1) {
                Inv.Item t = sl.get(k);
                if (pass == 0) {
                    if (t == null || !t.key.equals(it.key) || t.count >= t.maxCount || it.maxCount <= 1) continue;
                    int put = Math.min(count, t.maxCount - t.count);
                    sl.set(k, Inv.withCount(t, t.count + put));
                    count -= put;
                } else {
                    if (t != null) continue;
                    int put = Math.min(count, it.maxCount);
                    sl.set(k, Inv.withCount(it, put));
                    count -= put;
                }
                if (count <= 0) break;
            }
        }
        return count;
    }

    private static int minState(String id) {
        ProtoDef.VersionData vd = Hub.versionData;
        if (vd == null) return -1;
        for (int k = 0; k < vd.blockIds.length; k++) if (vd.blockIds[k].equals(id)) return vd.blockMin[k];
        return -1;
    }

    // ---------------------------------------------------------------- arguments

    private static String s(Object[] a, int i) {
        return a.length > i && a[i] != null ? String.valueOf(a[i]) : "";
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    private static double num(Object[] a, int i) {
        if (a.length <= i || a[i] == null) return 0;
        if (a[i] instanceof Number) return ((Number) a[i]).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(a[i]));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int i(Object[] a, int k) {
        return (int) Math.floor(num(a, k));
    }

    private static boolean bool(Object[] a, int i) {
        return a.length > i && Boolean.TRUE.equals(a[i]);
    }

    private static int[] ints(JSONArray a) throws JSONException {
        int[] r = new int[a.length()];
        for (int k = 0; k < r.length; k++) r[k] = (int) Math.floor(a.getDouble(k));
        return r;
    }

    private static double[] dbls(JSONArray a) throws JSONException {
        double[] r = new double[a.length()];
        for (int k = 0; k < r.length; k++) r[k] = a.getDouble(k);
        return r;
    }
}
