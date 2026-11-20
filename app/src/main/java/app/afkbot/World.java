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
        if (ENTS.size() >= MAX_ENTITIES && !ENTS.containsKey(e.id)) return;
        ENTS.put(e.id, e);
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
        }
    }

    static synchronized void moveTo(int id, double x, double y, double z) {
        Ent e = ENTS.get(id);
        if (e != null) {
            e.x = x;
            e.y = y;
            e.z = z;
        }
    }

    static synchronized void putName(UUID u, String name) {
        NAMES.put(u, name);
        tabCount = NAMES.size();
    }

    static synchronized void removeName(UUID u) {
        NAMES.remove(u);
        tabCount = NAMES.size();
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
                out.add(new Dot(e.kind, label, e.x - sx, e.y - sy, e.z - sz, friend));
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
