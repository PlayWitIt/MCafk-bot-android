package app.afkbot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Naughty or Nice add-on: player intel while the bot is AFK. Who's online and when, who came near and where,
 * your lists and groups (Friend, Nice, Look Into, Naughty), what people said, coordinates posted in chat, your name
 * mentioned, and whispers as DMs you can answer. Phone alerts for Naughty players joining or coming near and for
 * whispers. Kept per server in the app's files. Lists can be imported from the Naughty or Nice mod on PC with a share
 * link (NN2:…) or code (NN1:…).
 *
 * Pure Java and org.json, so it runs in desktop/ too. Everything is guarded by the class lock: the bot calls in from
 * its network thread, the screen reads from the UI thread.
 */
final class NonIntel {
    private NonIntel() {
    }

    enum Verdict {
        NONE("Not listed"), FRIEND("Friend"), NICE("Nice"), LOOK("Look Into"), NAUGHTY("Naughty");
        final String label;

        Verdict(String label) {
            this.label = label;
        }
    }

    static final class Person {
        String name = "";
        String uuid = "";
        Verdict verdict = Verdict.NONE;
        List<String> groups = new ArrayList<String>();
        String note = "";
        long firstSeen, lastSeen, lastOnline, lastJoin, listedAt;
        /** Times they came into view, and time spent in view. */
        int sightings;
        long nearMs;
        /** Where they were last seen (only when they came into view). */
        double x, y, z;
        String dim = "";
        long posAt;
        int messages;
        /** Their last lines in public chat. */
        List<Line> chat = new ArrayList<Line>();
        transient boolean online;
        transient long nearSince;
    }

    static final class Line {
        long t;
        boolean me;
        String text = "";
        /** 0 sent or received, SENDING, FAILED. */
        int state;
        String error;
    }

    static final int SENDING = 1, FAILED = 2;

    static final class Thread {
        String name = "";
        List<Line> msgs = new ArrayList<Line>();
        long readUpTo;

        long last() {
            return msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).t;
        }

        int unread() {
            int n = 0;
            for (int i = msgs.size() - 1; i >= 0 && msgs.get(i).t > readUpTo; i--) if (!msgs.get(i).me) n++;
            return n;
        }
    }

    /** Something that happened: join, leave, seen, coords, mention, alert. */
    static final class Event {
        long t;
        String kind = "";
        String name = "";
        String text = "";
        boolean hasPos;
        double x, y, z;
    }

    // ---- Settings (kept across servers) ----

    static volatile boolean installed;
    static boolean alertNaughtyJoin = true, alertNaughtyNear = true, alertLookJoin = false, alertWhisper = true, alertMention = true;
    static String whisperCommand = "w";
    static String lastServer = "";

    // ---- The server being watched ----

    private static File dir;
    private static String server = "";
    private static final Map<String, Person> PEOPLE = new LinkedHashMap<String, Person>();
    private static final Map<String, Thread> THREADS = new LinkedHashMap<String, Thread>();
    private static final List<Event> EVENTS = new ArrayList<Event>();
    private static final Map<UUID, String> ONLINE = new HashMap<UUID, String>();
    private static boolean dirty;
    private static long joinedAt, savedAt;
    private static volatile int version;
    /** The DM conversation on screen (its new lines count as read). */
    private static String viewing;

    static final int MAX_EVENTS = 3000, MAX_CHAT = 40, MAX_DM = 3000;

    /** The bot's own name (for mentions, and to leave yourself out), or null when it isn't running. */
    private static String myName() {
        Bot b = Hub.bot;
        return b == null ? null : b.playerName();
    }

    /** Bot events as sent to script mods: join, leave, kicked, playerLeave. Never throws (it runs in the bot's network code). */
    static void botEvent(String event, String value) {
        if (!installed) return;
        try {
            if (event.equals("join")) joined(value);
            else if (event.equals("leave") || event.equals("kicked")) left(value);
            else if (event.equals("playerLeave")) gone(value);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: " + event + " failed (" + e + ")");
        }
    }

    // The bot calls these from its network thread: an error must never reach it (it would drop the connection).

    static void tabAdd(UUID u, String name) {
        if (!installed) return;
        try {
            tabAdd0(u, name);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: tabAdd failed (" + e + ")");
        }
    }

    static void tabRemove(UUID u) {
        if (!installed) return;
        try {
            tabRemove0(u);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: tabRemove failed (" + e + ")");
        }
    }

    static void seen(String name, double x, double y, double z, double dist, String dimension) {
        if (!installed) return;
        try {
            seen0(name, x, y, z, dist, dimension);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: seen failed (" + e + ")");
        }
    }

    static void second(List<World.Dot> players) {
        if (!installed) return;
        try {
            second0(players);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: second failed (" + e + ")");
        }
    }

    static void chat(String sender, String message, String raw, boolean system) {
        if (!installed) return;
        try {
            chat0(sender, message, raw, system);
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: chat failed (" + e + ")");
        }
    }

    // ================================================================ files

    /** Called once with the app's folder for the add-on (files/non). */
    static synchronized void init(File folder) {
        if (dir != null) return;
        dir = folder;
        dir.mkdirs();
        try {
            JSONObject s = readJson(new File(dir, "settings.json"));
            if (s != null) {
                installed = s.optBoolean("installed", false);
                alertNaughtyJoin = s.optBoolean("alertNaughtyJoin", true);
                alertNaughtyNear = s.optBoolean("alertNaughtyNear", true);
                alertLookJoin = s.optBoolean("alertLookJoin", false);
                alertWhisper = s.optBoolean("alertWhisper", true);
                alertMention = s.optBoolean("alertMention", true);
                whisperCommand = s.optString("whisperCommand", "w");
                lastServer = s.optString("lastServer", "");
            }
        } catch (Exception e) {
            Hub.log("Naughty or Nice: couldn't read its settings (" + e.getMessage() + ")");
        }
        if (!lastServer.isEmpty()) load(lastServer);
    }

    static synchronized void saveSettings() {
        if (dir == null) return;
        try {
            JSONObject s = new JSONObject().put("installed", installed).put("alertNaughtyJoin", alertNaughtyJoin).put("alertNaughtyNear", alertNaughtyNear)
                    .put("alertLookJoin", alertLookJoin).put("alertWhisper", alertWhisper).put("alertMention", alertMention)
                    .put("whisperCommand", whisperCommand).put("lastServer", lastServer);
            writeJson(new File(dir, "settings.json"), s);
        } catch (Exception e) {
            Hub.log("Naughty or Nice: couldn't save its settings (" + e.getMessage() + ")");
        }
        changed();
    }

    private static File serverFile(String s) {
        return new File(dir, s.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    private static void load(String s) {
        PEOPLE.clear();
        THREADS.clear();
        EVENTS.clear();
        ONLINE.clear();
        server = s;
        dirty = false;
        if (dir == null || s.isEmpty()) return;
        try {
            JSONObject o = readJson(serverFile(s));
            if (o == null) return;
            JSONArray ps = o.optJSONArray("people");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                Person p = personFrom(ps.getJSONObject(i));
                if (!p.name.isEmpty()) PEOPLE.put(key(p.name), p);
            }
            JSONArray ts = o.optJSONArray("threads");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.getJSONObject(i);
                Thread th = new Thread();
                th.name = t.optString("name");
                th.readUpTo = t.optLong("readUpTo");
                th.msgs = lines(t.optJSONArray("msgs"));
                for (Line l : th.msgs) if (l.state == SENDING) l.state = 0;
                if (!th.name.isEmpty()) THREADS.put(key(th.name), th);
            }
            JSONArray es = o.optJSONArray("events");
            for (int i = 0; es != null && i < es.length(); i++) {
                JSONObject e = es.getJSONObject(i);
                Event ev = new Event();
                ev.t = e.optLong("t");
                ev.kind = e.optString("kind");
                ev.name = e.optString("name");
                ev.text = e.optString("text");
                ev.hasPos = e.has("x");
                ev.x = e.optDouble("x", 0);
                ev.y = e.optDouble("y", 0);
                ev.z = e.optDouble("z", 0);
                EVENTS.add(ev);
            }
        } catch (Exception e) {
            Hub.log("Naughty or Nice: couldn't read " + s + "'s data (" + e.getMessage() + ")");
        }
    }

    /** Writes this server's data if anything changed (or always, with force). */
    static synchronized void save(boolean force) {
        if (dir == null || server.isEmpty() || (!dirty && !force)) return;
        try {
            JSONObject o = new JSONObject().put("server", server).put("saved", System.currentTimeMillis());
            JSONArray ps = new JSONArray();
            for (Person p : PEOPLE.values()) ps.put(personJson(p));
            o.put("people", ps);
            JSONArray ts = new JSONArray();
            for (Thread t : THREADS.values()) {
                if (t.msgs.isEmpty()) continue;
                ts.put(new JSONObject().put("name", t.name).put("readUpTo", t.readUpTo).put("msgs", linesJson(t.msgs)));
            }
            o.put("threads", ts);
            JSONArray es = new JSONArray();
            for (Event e : EVENTS) {
                JSONObject j = new JSONObject().put("t", e.t).put("kind", e.kind).put("name", e.name).put("text", e.text);
                if (e.hasPos) j.put("x", Math.round(e.x)).put("y", Math.round(e.y)).put("z", Math.round(e.z));
                es.put(j);
            }
            o.put("events", es);
            writeJson(serverFile(server), o);
            dirty = false;
            savedAt = System.currentTimeMillis();
        } catch (Exception e) {
            Hub.log("Naughty or Nice: couldn't save (" + e.getMessage() + ")");
        }
    }

    private static Person personFrom(JSONObject j) {
        Person p = new Person();
        p.name = j.optString("name");
        p.uuid = j.optString("uuid");
        try {
            p.verdict = Verdict.valueOf(j.optString("verdict", "NONE"));
        } catch (IllegalArgumentException e) {
            p.verdict = Verdict.NONE;
        }
        JSONArray g = j.optJSONArray("groups");
        for (int i = 0; g != null && i < g.length(); i++) p.groups.add(g.optString(i));
        p.note = j.optString("note");
        p.firstSeen = j.optLong("firstSeen");
        p.lastSeen = j.optLong("lastSeen");
        p.lastOnline = j.optLong("lastOnline");
        p.lastJoin = j.optLong("lastJoin");
        p.listedAt = j.optLong("listedAt");
        p.sightings = j.optInt("sightings");
        p.nearMs = j.optLong("nearMs");
        p.x = j.optDouble("x", 0);
        p.y = j.optDouble("y", 0);
        p.z = j.optDouble("z", 0);
        p.dim = j.optString("dim");
        p.posAt = j.optLong("posAt");
        p.messages = j.optInt("messages");
        p.chat = lines(j.optJSONArray("chat"));
        return p;
    }

    private static JSONObject personJson(Person p) throws JSONException {
        JSONObject j = new JSONObject().put("name", p.name).put("uuid", p.uuid).put("verdict", p.verdict.name()).put("groups", new JSONArray(p.groups))
                .put("note", p.note).put("firstSeen", p.firstSeen).put("lastSeen", p.lastSeen).put("lastOnline", p.lastOnline).put("lastJoin", p.lastJoin)
                .put("listedAt", p.listedAt).put("sightings", p.sightings).put("nearMs", p.nearMs).put("messages", p.messages).put("chat", linesJson(p.chat));
        if (p.posAt > 0) j.put("x", Math.round(p.x)).put("y", Math.round(p.y)).put("z", Math.round(p.z)).put("dim", p.dim).put("posAt", p.posAt);
        return j;
    }

    private static List<Line> lines(JSONArray a) {
        List<Line> out = new ArrayList<Line>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Line l = new Line();
            l.t = o.optLong("t");
            l.me = o.optBoolean("me");
            l.text = o.optString("text");
            l.state = o.optInt("state");
            l.error = o.has("error") ? o.optString("error") : null;
            out.add(l);
        }
        return out;
    }

    private static JSONArray linesJson(List<Line> ls) throws JSONException {
        JSONArray a = new JSONArray();
        for (Line l : ls) {
            JSONObject o = new JSONObject().put("t", l.t).put("me", l.me).put("text", l.text);
            if (l.state != 0) o.put("state", l.state);
            if (l.error != null) o.put("error", l.error);
            a.put(o);
        }
        return a;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static JSONObject readJson(File f) throws IOException, JSONException {
        if (!f.exists()) return null;
        InputStream in = new FileInputStream(f);
        try {
            return new JSONObject(new String(readAll(in), UTF8));
        } finally {
            in.close();
        }
    }

    private static void writeJson(File f, JSONObject o) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
        try {
            w.write(o.toString());
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("couldn't replace " + f.getName());
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ================================================================ from the bot

    static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void changed() {
        version++;
    }

    static int version() {
        return version;
    }

    private static Person person(String name) {
        Person p = PEOPLE.get(key(name));
        if (p == null) {
            p = new Person();
            p.name = name;
            p.firstSeen = System.currentTimeMillis();
            PEOPLE.put(key(name), p);
        } else if (!p.name.equals(name)) {
            p.name = name;
        }
        return p;
    }

    private static boolean isMe(String name) {
        String mine = myName();
        return mine != null && mine.equalsIgnoreCase(name);
    }

    private static void event(String kind, String name, String text, boolean hasPos, double x, double y, double z) {
        Event e = new Event();
        e.t = System.currentTimeMillis();
        e.kind = kind;
        e.name = name == null ? "" : name;
        e.text = text == null ? "" : text;
        e.hasPos = hasPos;
        e.x = x;
        e.y = y;
        e.z = z;
        EVENTS.add(e);
        while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);
    }

    /** The bot joined a server (its address as typed). */
    static synchronized void joined(String serverLabel) {
        if (!installed) return;
        String s = serverLabel == null ? "" : serverLabel.trim().toLowerCase(Locale.ROOT);
        if (!s.equals(server)) {
            save(true);
            load(s);
        }
        if (!s.equals(lastServer)) {
            lastServer = s;
            saveSettings();
        }
        joinedAt = System.currentTimeMillis();
        ONLINE.clear();
        event("you", "", "You joined " + s, false, 0, 0, 0);
        dirty = true;
        changed();
    }

    /** Switched on while the bot is already online: starts watching this server, with everyone online now. */
    static void startNow(String serverLabel, Map<UUID, String> online) {
        try {
            joined(serverLabel);
            for (Map.Entry<UUID, String> e : online.entrySet()) tabAdd0(e.getKey(), e.getValue());
        } catch (RuntimeException e) {
            Hub.log("Naughty or Nice: couldn't start (" + e + ")");
        }
    }

    /** The bot left (disconnected, kicked or stopped). */
    static synchronized void left(String reason) {
        if (!installed || server.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String n : ONLINE.values()) {
            Person p = PEOPLE.get(key(n));
            if (p != null) {
                p.lastOnline = now;
                p.online = false;
                endNear(p, now);
            }
        }
        ONLINE.clear();
        event("you", "", "You left" + (reason == null || reason.isEmpty() ? "" : ": " + reason), false, 0, 0, 0);
        dirty = true;
        save(true);
        changed();
    }

    /** Someone is on the tab list. Right after you join, that's everyone already online; after that, a join. */
    static synchronized void tabAdd0(UUID u, String name) {
        if (!installed || server.isEmpty() || name == null || name.isEmpty() || ONLINE.containsKey(u)) return;
        ONLINE.put(u, name);
        if (isMe(name)) return;
        long now = System.currentTimeMillis();
        Person p = person(name);
        if (u != null) p.uuid = u.toString();
        p.online = true;
        p.lastOnline = now;
        boolean join = now - joinedAt > 8000;
        if (join) {
            p.lastJoin = now;
            event("join", name, "joined", false, 0, 0, 0);
            if (p.verdict == Verdict.NAUGHTY && alertNaughtyJoin) alert("Naughty player joined", name + " joined " + server + note(p));
            else if (p.verdict == Verdict.LOOK && alertLookJoin) alert("Look Into: " + name + " joined", name + " joined " + server + note(p));
        }
        dirty = true;
        changed();
    }

    static synchronized void tabRemove0(UUID u) {
        if (!installed) return;
        String name = ONLINE.remove(u);
        if (name == null || isMe(name)) return;
        long now = System.currentTimeMillis();
        Person p = PEOPLE.get(key(name));
        if (p != null) {
            p.online = false;
            p.lastOnline = now;
            endNear(p, now);
        }
        event("leave", name, "left", false, 0, 0, 0);
        dirty = true;
        changed();
    }

    /** A player came into view, at x y z (dist blocks away). */
    static synchronized void seen0(String name, double x, double y, double z, double dist, String dimension) {
        if (!installed || server.isEmpty() || name == null || name.equals("Player") || isMe(name)) return;
        long now = System.currentTimeMillis();
        Person p = person(name);
        p.sightings++;
        p.lastSeen = now;
        p.x = x;
        p.y = y;
        p.z = z;
        p.dim = dimension == null ? "" : dimension;
        p.posAt = now;
        if (p.nearSince == 0) p.nearSince = now;
        event("seen", name, String.format(Locale.US, "came into view, %.0f blocks away", dist), true, x, y, z);
        if (p.verdict == Verdict.NAUGHTY && alertNaughtyNear) {
            alert("Naughty player near you", String.format(Locale.US, "%s is %.0f blocks away (%.0f, %.0f, %.0f)%s", name, dist, x, y, z, note(p)));
        }
        dirty = true;
        changed();
    }

    /** A player went out of view. */
    static synchronized void gone(String name) {
        if (!installed || name == null) return;
        Person p = PEOPLE.get(key(name));
        if (p == null) return;
        endNear(p, System.currentTimeMillis());
        dirty = true;
        changed();
    }

    private static void endNear(Person p, long now) {
        if (p.nearSince > 0) {
            p.nearMs += now - p.nearSince;
            p.lastSeen = now;
            p.nearSince = 0;
        }
    }

    /** Every second while online: keeps positions of players in view fresh, and saves now and then. */
    static synchronized void second0(List<World.Dot> players) {
        if (!installed || server.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (players != null) {
            for (World.Dot d : players) {
                if (d.kind != 'p') continue;
                Person p = PEOPLE.get(key(d.label));
                if (p == null) continue;
                p.x = World.selfX + d.dx;
                p.y = World.selfY + d.dy;
                p.z = World.selfZ + d.dz;
                p.posAt = now;
                p.lastSeen = now;
            }
        }
        tickDms(now);
        if (dirty && now - savedAt > 30000) save(false);
    }

    private static String note(Person p) {
        return p.groups.isEmpty() ? "" : " [" + join(p.groups, ", ") + "]";
    }

    private static void alert(String title, String text) {
        Hub.log("Naughty or Nice: " + text);
        Hub.alert(title, text);
    }

    // ---- Chat ----

    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final Pattern[] WHISPER_IN = {
            Pattern.compile("^" + NAME + " whispers(?: to you)?: (.+)$"),
            Pattern.compile("^(?i:from) " + NAME + ":? (.+)$"),
            Pattern.compile("^[\\[(]" + NAME + " ?(?:->|→|») ?(?:me|you)[\\])]:? (.+)$"),
            Pattern.compile("^" + NAME + " whispered to you: (.+)$"),
    };
    private static final Pattern[] WHISPER_OUT = {
            Pattern.compile("^(?i:you whisper(?:ed)? to) " + NAME + ": (.+)$"),
            Pattern.compile("^(?i:to) " + NAME + ":? (.+)$"),
            Pattern.compile("^[\\[(](?:me|you) ?(?:->|→|») ?" + NAME + "[\\])]:? (.+)$"),
    };
    private static final Pattern LEADING_TIME = Pattern.compile("^[\\[(]\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[AaPp][Mm])?[\\])]\\s*");
    /** "name: text", "[Rank] name » text": only trusted when the name is someone online. */
    private static final Pattern SEPARATED = Pattern.compile("^(?:\\[[^\\]]{1,24}\\]\\s*)*" + NAME + "\\s?(?::|»|>>)\\s(.+)$");
    /** Coordinates: "1200 64 -3400", "x 1200 z -3400", "1200, -3400". */
    private static final Pattern COORDS = Pattern.compile("(?<![\\w.])(-?\\d{2,8})(?:[ ,/]+(-?\\d{1,3}))?[ ,/]+(-?\\d{2,8})(?![\\w.])");

    /** A chat line as the bot read it. {@code sender} is set for "&lt;name&gt; text" chat. */
    static synchronized void chat0(String sender, String message, String raw, boolean system) {
        if (!installed || server.isEmpty() || message == null) return;
        long now = System.currentTimeMillis();
        String line = LEADING_TIME.matcher(raw == null ? message : raw).replaceFirst("").trim();
        if (sender == null) {
            for (Pattern pt : WHISPER_OUT) {
                Matcher m = pt.matcher(line);
                if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
                    dm(m.group(1), true, m.group(2), now);
                    return;
                }
            }
            for (Pattern pt : WHISPER_IN) {
                Matcher m = pt.matcher(line);
                if (m.matches()) {
                    dm(m.group(1), false, m.group(2), now);
                    return;
                }
            }
            if (system && notSent(line)) return;
            Matcher m = SEPARATED.matcher(line);
            if (m.matches() && ONLINE.containsValue(m.group(1))) {
                sender = m.group(1);
                message = m.group(2);
            }
        }
        if (sender == null || isMe(sender)) return;
        Person p = person(sender);
        p.messages++;
        Line l = new Line();
        l.t = now;
        l.text = message;
        p.chat.add(l);
        while (p.chat.size() > MAX_CHAT) p.chat.remove(0);
        Matcher c = COORDS.matcher(message);
        while (c.find()) {
            long a = Math.abs(Long.parseLong(c.group(1))), b = Math.abs(Long.parseLong(c.group(3)));
            if (Math.max(a, b) < 300 && c.group(2) == null) continue;
            if (Math.max(a, b) < 100) continue;
            event("coords", sender, message, false, 0, 0, 0);
            break;
        }
        String mine = myName();
        if (mine != null && mine.length() >= 3 && message.toLowerCase(Locale.ROOT).contains(mine.toLowerCase(Locale.ROOT))) {
            event("mention", sender, message, false, 0, 0, 0);
            if (alertMention) alert(sender + " said your name", message);
        }
        dirty = true;
        changed();
    }

    /** A server reply that a whisper didn't go through ("Player not found"): marks the line just sent. */
    private static boolean notSent(String line) {
        if (!Pattern.compile("(?i)(no player (was )?found|player not found|not online|isn'?t online|is offline|could not find|couldn'?t find|unknown player|"
                + "can'?t (message|whisper)|cannot (message|whisper)|is ignoring you|doesn'?t accept|not accepting)").matcher(line).find()) return false;
        long now = System.currentTimeMillis();
        Line newest = null;
        for (Thread t : THREADS.values()) {
            for (int i = t.msgs.size() - 1; i >= 0 && i >= t.msgs.size() - 6; i--) {
                Line l = t.msgs.get(i);
                if (l.me && l.state == SENDING && now - l.t < 6000 && (newest == null || l.t < newest.t)) newest = l;
            }
        }
        if (newest == null) return false;
        newest.state = FAILED;
        newest.error = line.length() > 120 ? line.substring(0, 120) + "…" : line;
        dirty = true;
        changed();
        return true;
    }

    // ---- DMs ----

    private static Thread thread(String name) {
        Thread t = THREADS.get(key(name));
        if (t == null) {
            t = new Thread();
            t.name = name;
            THREADS.put(key(name), t);
        }
        return t;
    }

    /** A whisper, from them or from you. Your own confirms one sent from the DMs box rather than adding it twice. */
    private static void dm(String partner, boolean mine, String text, long now) {
        Thread t = thread(partner);
        t.name = partner;
        if (mine) {
            for (Line l : t.msgs) {
                if (l.me && l.state == SENDING) {
                    l.state = 0;
                    l.text = text;
                    dirty = true;
                    changed();
                    return;
                }
            }
        }
        Line l = new Line();
        l.t = mine ? now : Math.max(now, t.readUpTo + 1);
        l.me = mine;
        l.text = text;
        t.msgs.add(l);
        while (t.msgs.size() > MAX_DM) t.msgs.remove(0);
        if (mine || key(partner).equals(viewing)) t.readUpTo = Math.max(t.readUpTo, l.t);
        else if (alertWhisper) Hub.alert(partner + " whispered you", text);
        person(partner);
        dirty = true;
        changed();
    }

    private static void tickDms(long now) {
        for (Thread t : THREADS.values()) {
            for (int i = t.msgs.size() - 1; i >= 0 && i >= t.msgs.size() - 10; i--) {
                Line l = t.msgs.get(i);
                if (l.state == SENDING && now - l.t > 6000) {
                    l.state = 0;
                    dirty = true;
                    changed();
                }
            }
        }
    }

    /**
     * Whispers {@code text} to {@code to} (split to fit). Returns null when sent, or why it couldn't be.
     */
    static String send(String to, String text) {
        Bot s = Hub.bot;
        if (s == null || !World.inWorld) return "The bot has to be online to send: start AFK first.";
        if (s.secureChat) return "This server enforces secure chat, and a whisper sent from the app would get the bot kicked. Answer from the game for now.";
        String cmd = whisperCommand.trim().replaceFirst("^/", "");
        if (cmd.isEmpty()) cmd = "w";
        String prefix = "/" + cmd + " " + to + " ";
        for (String part : split(text, 256 - prefix.length() + 1)) {
            testSending(to, part);
            s.sendChat(prefix + part);
        }
        return null;
    }

    /** Puts a line in as "sending" (until the server's echo, or a few seconds). */
    static synchronized void testSending(String to, String text) {
        Thread t = thread(to);
        Line l = new Line();
        l.t = System.currentTimeMillis();
        l.me = true;
        l.text = text;
        l.state = SENDING;
        t.msgs.add(l);
        t.readUpTo = Math.max(t.readUpTo, l.t);
        dirty = true;
        changed();
    }

    static List<String> split(String text, int room) {
        List<String> parts = new ArrayList<String>();
        String rest = text.trim();
        room = Math.max(20, room);
        while (rest.length() > room) {
            int cut = rest.lastIndexOf(' ', room);
            if (cut < room / 2) cut = room;
            parts.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) parts.add(rest);
        return parts;
    }

    static synchronized void viewing(String name) {
        viewing = name == null ? null : key(name);
        if (viewing != null) {
            Thread t = THREADS.get(viewing);
            if (t != null && t.last() > t.readUpTo) {
                t.readUpTo = t.last();
                dirty = true;
                changed();
            }
        }
    }

    static synchronized int unread() {
        int n = 0;
        for (Thread t : THREADS.values()) n += t.unread();
        return n;
    }

    static synchronized List<Thread> threads() {
        List<Thread> list = new ArrayList<Thread>();
        for (Thread t : THREADS.values()) {
            if (t.msgs.isEmpty() && !key(t.name).equals(viewing)) continue;
            Thread c = new Thread();
            c.name = t.name;
            c.readUpTo = t.readUpTo;
            c.msgs = new ArrayList<Line>(t.msgs);
            list.add(c);
        }
        Collections.sort(list, new Comparator<Thread>() {
            public int compare(Thread a, Thread b) {
                return Long.compare(b.last(), a.last());
            }
        });
        return list;
    }

    static synchronized Thread threadCopy(String name) {
        Thread t = thread(name);
        Thread c = new Thread();
        c.name = t.name;
        c.readUpTo = t.readUpTo;
        for (Line l : t.msgs) {
            Line x = new Line();
            x.t = l.t;
            x.me = l.me;
            x.text = l.text;
            x.state = l.state;
            x.error = l.error;
            c.msgs.add(x);
        }
        return c;
    }

    static synchronized void deleteThread(String name) {
        if (THREADS.remove(key(name)) != null) {
            dirty = true;
            changed();
        }
    }

    // ---- For the screen ----

    static synchronized String server() {
        return server;
    }

    /** Copies of everyone known, for the screen. */
    static synchronized List<Person> people() {
        List<Person> out = new ArrayList<Person>();
        long now = System.currentTimeMillis();
        for (Person p : PEOPLE.values()) {
            Person c = copy(p);
            if (p.nearSince > 0) c.nearMs += now - p.nearSince;
            out.add(c);
        }
        return out;
    }

    static synchronized Person get(String name) {
        Person p = PEOPLE.get(key(name));
        return p == null ? null : copy(p);
    }

    private static Person copy(Person p) {
        Person c = new Person();
        c.name = p.name;
        c.uuid = p.uuid;
        c.verdict = p.verdict;
        c.groups = new ArrayList<String>(p.groups);
        c.note = p.note;
        c.firstSeen = p.firstSeen;
        c.lastSeen = p.lastSeen;
        c.lastOnline = p.lastOnline;
        c.lastJoin = p.lastJoin;
        c.listedAt = p.listedAt;
        c.sightings = p.sightings;
        c.nearMs = p.nearMs;
        c.x = p.x;
        c.y = p.y;
        c.z = p.z;
        c.dim = p.dim;
        c.posAt = p.posAt;
        c.messages = p.messages;
        c.chat = new ArrayList<Line>(p.chat);
        c.online = p.online;
        c.nearSince = p.nearSince;
        return c;
    }

    static synchronized List<Event> events() {
        return new ArrayList<Event>(EVENTS);
    }

    static synchronized void setVerdict(String name, Verdict v) {
        Person p = person(name);
        p.verdict = v;
        p.listedAt = System.currentTimeMillis();
        dirty = true;
        changed();
    }

    static synchronized void setGroups(String name, List<String> groups) {
        Person p = person(name);
        p.groups = new ArrayList<String>();
        for (String g : groups) if (!g.trim().isEmpty() && !containsIgnoreCase(p.groups, g.trim())) p.groups.add(g.trim());
        dirty = true;
        changed();
    }

    static synchronized void setNote(String name, String note) {
        person(name).note = note == null ? "" : note;
        dirty = true;
        changed();
    }

    static synchronized void forget(String name) {
        if (PEOPLE.remove(key(name)) != null) {
            dirty = true;
            changed();
        }
    }

    /** Adds someone by name (to put them on a list before they've been seen). */
    static synchronized void add(String name) {
        person(name);
        dirty = true;
        changed();
    }

    static synchronized boolean online(String name) {
        return ONLINE.containsValue(name) || containsIgnoreCase(new ArrayList<String>(ONLINE.values()), name);
    }

    /** Everything for this server goes (lists too). */
    /** For the desktop test: pretend you joined {@code ms} ago. */
    static synchronized void testJoinedAgo(long ms) {
        joinedAt = System.currentTimeMillis() - ms;
    }

    static synchronized void clearServer() {
        PEOPLE.clear();
        THREADS.clear();
        EVENTS.clear();
        dirty = true;
        save(true);
        changed();
    }

    // ================================================================ import from the PC mod

    /** What an import did, in words. */
    static final class ImportResult {
        int added, updated, groups;
        String server = "", from = "";

        public String toString() {
            return "Imported " + (added + updated) + " players" + (from.isEmpty() ? "" : " from " + from)
                    + (server.isEmpty() ? "" : " (" + server + ")") + ": " + added + " new, " + updated + " updated, " + groups + " group memberships.";
        }
    }

    private static final Pattern NN2 = Pattern.compile("NN2:([bp])([A-Za-z0-9]{3,40})\\.([A-Za-z0-9_-]{22})");

    /**
     * Imports lists from the Naughty or Nice mod: a share link (NN2:…, downloaded), a share code (NN1:…) or a share
     * file's text. Players' lists, groups and notes merge in (a list you set here isn't overwritten with "not listed").
     * Network: call off the UI thread.
     */
    static ImportResult importShare(String text, Fetcher fetch) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Paste a share link or code from Naughty or Nice first.");
        String json;
        Matcher m2 = NN2.matcher(text);
        int at1 = text.indexOf("NN1:");
        if (m2.find()) {
            char host = m2.group(1).charAt(0);
            String url = host == 'b' ? "https://bytebin.lucko.me/" + m2.group(2) : "https://paste.rs/" + m2.group(2);
            String body = fetch.get(url).trim();
            byte[] key = b64(m2.group(3));
            byte[] packed = unlock(b64(body), key);
            json = new String(gunzip(packed), UTF8);
        } else if (at1 >= 0) {
            StringBuilder data = new StringBuilder();
            for (int i = at1 + 4; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') data.append(c);
                else if (!Character.isWhitespace(c)) break;
            }
            json = new String(gunzip(b64(data.toString())), UTF8);
        } else if (text.trim().startsWith("{")) {
            json = text.trim();
        } else {
            throw new IllegalArgumentException("That isn't a Naughty or Nice share. Links start with NN2: and codes with NN1:");
        }
        JSONObject pack = new JSONObject(json);
        if (!"naughty-or-nice-share".equals(pack.optString("format")) && !pack.has("players")) throw new IllegalArgumentException("That isn't a Naughty or Nice share.");
        return merge(pack);
    }

    interface Fetcher {
        String get(String url) throws IOException;
    }

    static synchronized ImportResult merge(JSONObject pack) throws JSONException {
        ImportResult r = new ImportResult();
        r.from = pack.optString("from");
        r.server = pack.optString("server").replaceFirst("^(server|local):", "");
        JSONArray players = pack.optJSONArray("players");
        long now = System.currentTimeMillis();
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject pl = players.getJSONObject(i);
            String name = pl.optString("name");
            if (name.isEmpty()) continue;
            boolean known = PEOPLE.containsKey(key(name));
            Person p = person(name);
            if (pl.has("id")) p.uuid = pl.optString("id");
            Verdict v = Verdict.NONE;
            try {
                v = Verdict.valueOf(pl.optString("verdict", "NONE"));
            } catch (IllegalArgumentException ignored) {
            }
            if (pl.optBoolean("friend")) v = Verdict.FRIEND;
            if (v != Verdict.NONE) {
                p.verdict = v;
                p.listedAt = pl.optLong("listedAt", now);
            }
            JSONArray gs = pl.optJSONArray("groups");
            for (int g = 0; gs != null && g < gs.length(); g++) {
                String group = gs.optString(g).trim();
                if (!group.isEmpty() && !containsIgnoreCase(p.groups, group)) {
                    p.groups.add(group);
                    r.groups++;
                }
            }
            JSONArray details = pl.optJSONArray("details");
            StringBuilder note = new StringBuilder(p.note);
            for (int d = 0; details != null && d < details.length(); d++) {
                JSONObject det = details.optJSONObject(d);
                String t = det == null ? details.optString(d) : det.optString("text");
                if (t != null && !t.isEmpty() && note.indexOf(t) < 0) note.append(note.length() == 0 ? "" : "\n").append(t);
            }
            p.note = note.toString();
            if (known) r.updated++;
            else r.added++;
        }
        dirty = true;
        save(true);
        changed();
        return r;
    }

    private static byte[] b64(String s) {
        return java.util.Base64.getUrlDecoder().decode(s.trim().replace("=", ""));
    }

    private static byte[] unlock(byte[] data, byte[] key) throws Exception {
        if (data.length < 28) throw new IllegalArgumentException("That share link is damaged, or it's been cut short.");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, data, 0, 12));
        return cipher.doFinal(data, 12, data.length - 12);
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data));
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    // ================================================================ helpers

    static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    static String ago(long t) {
        if (t <= 0) return "never";
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        return s / 86400 + "d ago";
    }

    static String span(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h " + s % 3600 / 60 + "m";
        return s / 86400 + "d " + s % 86400 / 3600 + "h";
    }
}
