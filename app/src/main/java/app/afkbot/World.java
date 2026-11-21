package app.afkbot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** What the bot can "see": nearby entities, the player list and our own position. Read by the radar. */
final class World {
    private World() {
    }

    static final class Ent {
        final int id;
        final char kind; // p player, h hostile, a animal, i item, o other
        final String type;
        final UUID uuid;
        double x, y, z;
        float yaw; // facing, from the direction it last moved
        volatile String item; // dropped items: which item (from its metadata, Play screen only)

        Ent(int id, char kind, String type, UUID uuid, double x, double y, double z) {
            this.id = id;
            this.kind = kind;
            this.type = type;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** Immutable copy for drawing. */
    static final class Dot {
        int id = -1;
        String type = "";
        String item; // dropped items: the item id, if known
        String skin; // players: their skin texture URL, if known
        float yaw;
        final char kind;
        final String label;
        final double dx, dy, dz, dist;
        final boolean friend;

        Dot(char kind, String label, double dx, double dy, double dz, boolean friend) {
            this.kind = kind;
            this.label = label;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            this.friend = friend;
        }
    }

    private static final int MAX_ENTITIES = 3000;
    private static final Map<Integer, Ent> ENTS = new HashMap<Integer, Ent>();
    private static final Map<UUID, String> NAMES = new HashMap<UUID, String>();

    static volatile boolean inWorld;
    static volatile double selfX, selfY, selfZ;
    static volatile float selfYaw;
    static volatile String dimension = "";
    static volatile int tabCount;

    static synchronized void clearEntities() {
        ENTS.clear();
    }

    static synchronized void reset() {
        ENTS.clear();
        NAMES.clear();
        inWorld = false;
        tabCount = 0;
    }

    static synchronized void add(Ent e) {
        if (ENTS.size() >= MAX_ENTITIES && !ENTS.containsKey(e.id)) {
            // full: make room by dropping the farthest less important entity (items first, players never), so a
            // pile of dropped items can't hide an approaching player or mob
            Ent worst = null;
            double worstScore = rank(e) * 1e9 + dist2(e);
            for (Ent o : ENTS.values()) {
                double sc = rank(o) * 1e9 + dist2(o);
                if (sc > worstScore) {
                    worst = o;
                    worstScore = sc;
                }
            }
            if (worst == null) return; // everything kept is more important than this one
            ENTS.remove(worst.id);
        }
        ENTS.put(e.id, e);
    }

    /** Lower is more important: players, then hostile mobs, animals, other, dropped items. */
    private static int rank(Ent e) {
        switch (e.kind) {
            case 'p': return 0;
            case 'h': return 1;
            case 'a': return 2;
            case 'i': return 4;
            default: return 3;
        }
    }

    private static double dist2(Ent e) {
        double dx = e.x - selfX, dy = e.y - selfY, dz = e.z - selfZ;
        return Math.min(dx * dx + dy * dy + dz * dz, 9e8);
    }

    static synchronized Ent remove(int id) {
        return ENTS.remove(id);
    }

    static synchronized Ent get(int id) {
        return ENTS.get(id);
    }

    static synchronized void moveBy(int id, double dx, double dy, double dz) {
        Ent e = ENTS.get(id);
        if (e != null) {
            e.x += dx;
            e.y += dy;
            e.z += dz;
            if (dx * dx + dz * dz > 1e-4) e.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
    }

    static synchronized void moveTo(int id, double x, double y, double z) {
        Ent e = ENTS.get(id);
        if (e != null) {
            double dx = x - e.x, dz = z - e.z;
            if (dx * dx + dz * dz > 1e-4 && dx * dx + dz * dz < 64) e.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            e.x = x;
            e.y = y;
            e.z = z;
        }
    }

    /** Players' skin texture URLs (only Mojang's skin server is accepted) and whether they use the slim model. */
    private static final Map<UUID, String> SKINS = new HashMap<UUID, String>();

    static synchronized void putSkin(UUID u, String url) {
        if (url != null && (url.startsWith("http://textures.minecraft.net/texture/") || url.startsWith("https://textures.minecraft.net/texture/")))
            SKINS.put(u, url);
    }

    static synchronized String skinOf(UUID u) {
        return u == null ? null : SKINS.get(u);
    }

    static synchronized void putName(UUID u, String name) {
        NAMES.put(u, name);
        tabCount = NAMES.size();
        NonIntel.tabAdd(u, name);
    }

    static synchronized void removeName(UUID u) {
        NAMES.remove(u);
        tabCount = NAMES.size();
        NonIntel.tabRemove(u);
    }

    /** Everyone on the tab list, for an add-on switched on while connected. */
    static synchronized Map<UUID, String> names() {
        return new HashMap<UUID, String>(NAMES);
    }

    static synchronized String nameOf(UUID u) {
        return u == null ? null : NAMES.get(u);
    }

    static String label(Ent e) {
        if (e.kind == 'p') {
            String n = nameOf(e.uuid);
            return n != null ? n : "Player";
        }
        return pretty(e.type);
    }

    static String pretty(String type) {
        if (type == null || type.isEmpty()) return "Entity";
        String s = type.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Snapshot of entities relative to us, nearest first. */
    static List<Dot> snapshot(Friends friends) {
        List<Dot> out = new ArrayList<Dot>();
        double sx = selfX, sy = selfY, sz = selfZ;
        synchronized (World.class) {
            for (Ent e : ENTS.values()) {
                String label = label(e);
                boolean friend = e.kind == 'p' && friends != null && friends.contains(label);
                Dot d = new Dot(e.kind, label, e.x - sx, e.y - sy, e.z - sz, friend);
                d.id = e.id;
                d.type = e.kind == 'p' ? "player" : e.type;
                d.yaw = e.yaw;
                d.item = e.item;
                if (e.kind == 'p') d.skin = SKINS.get(e.uuid);
                out.add(d);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<Dot>() {
            @Override
            public int compare(Dot a, Dot b) {
                return Double.compare(a.dist, b.dist);
            }
        });
        return out;
    }

    /** Distance from the player's eyes to the centre of block {x, y, z}. */
    static double reach(int[] b) {
        double dx = b[0] + 0.5 - selfX, dy = b[1] + 0.5 - (selfY + 1.62), dz = b[2] + 0.5 - selfZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Nearest non-friend player, or null. */
    static Dot nearestThreat(Friends friends) {
        for (Dot d : snapshot(friends)) if (d.kind == 'p' && !d.friend) return d;
        return null;
    }

    /** Case-insensitive set of friend names. */
    static final class Friends {
        private final java.util.HashSet<String> set = new java.util.HashSet<String>();

        Friends(String csv) {
            if (csv == null) return;
            for (String s : csv.split("[,\\s]+")) {
                s = s.trim();
                if (!s.isEmpty()) set.add(s.toLowerCase(Locale.US));
            }
        }

        boolean contains(String name) {
            return name != null && set.contains(name.toLowerCase(Locale.US));
        }
    }
}
