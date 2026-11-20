package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns Minecraft chat components (JSON or network NBT) into plain text. */
final class Text {
    private Text() {
    }

    private static final Map<String, String> KNOWN = new HashMap<String, String>();

    static {
        KNOWN.put("multiplayer.disconnect.duplicate_login", "You logged in from another location");
        KNOWN.put("multiplayer.disconnect.idling", "You have been idle for too long!");
        KNOWN.put("multiplayer.disconnect.not_whitelisted", "You are not white-listed on this server!");
        KNOWN.put("multiplayer.disconnect.server_shutdown", "Server closed");
        KNOWN.put("multiplayer.disconnect.server_full", "The server is full!");
        KNOWN.put("multiplayer.disconnect.kicked", "Kicked by an operator");
        KNOWN.put("multiplayer.disconnect.flying", "Flying is not enabled on this server");
        KNOWN.put("multiplayer.disconnect.invalid_player_movement", "Invalid move player packet received");
        KNOWN.put("multiplayer.disconnect.outdated_client", "Incompatible client! Please use %s");
        KNOWN.put("multiplayer.disconnect.incompatible", "Incompatible client! Please use %s");
        KNOWN.put("multiplayer.disconnect.unverified_username", "Failed to verify username!");
        KNOWN.put("multiplayer.disconnect.banned", "You are banned from this server");
        KNOWN.put("multiplayer.disconnect.banned.reason", "You are banned from this server.\nReason: %s");
        KNOWN.put("multiplayer.disconnect.authservers_down", "Authentication servers are down. Please try again later");
        KNOWN.put("disconnect.timeout", "Timed out");
        KNOWN.put("multiplayer.player.joined", "%s joined the game");
        KNOWN.put("multiplayer.player.left", "%s left the game");
        KNOWN.put("chat.type.text", "<%s> %s");
        KNOWN.put("chat.type.announcement", "[%s] %s");
        KNOWN.put("chat.type.emote", "* %s %s");
        KNOWN.put("multiplayer.message_not_delivered", "Can't deliver chat message");
    }

    static String fromJson(String json) {
        try {
            Object o = new JSONTokener(json).nextValue();
            return flatten(fromJsonValue(o));
        } catch (Exception e) {
            return json;
        }
    }

    private static Object fromJsonValue(Object o) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, fromJsonValue(j.get(k)));
            }
            return m;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            List<Object> l = new ArrayList<Object>();
            for (int i = 0; i < a.length(); i++) l.add(fromJsonValue(a.get(i)));
            return l;
        }
        return o == JSONObject.NULL ? "" : o;
    }

    /** Reads one anonymous (network) NBT tag and flattens it to text. */
    static String fromNbt(Buf.In in) throws IOException {
        int type = in.u8();
        if (type == 0) return "";
        return flatten(readTag(in, type));
    }

    private static Object readTag(Buf.In in, int type) throws IOException {
        switch (type) {
            case 1:
                return (byte) in.u8();
            case 2:
                return (short) in.u16();
            case 3:
                return in.i32();
            case 4:
                return in.i64();
            case 5:
                return in.f32();
            case 6:
                return in.f64();
            case 7:
                in.bytes(in.i32());
                return "";
            case 8:
                return new String(in.bytes(in.u16()), StandardCharsets.UTF_8);
            case 9: {
                int et = in.u8();
                int n = in.i32();
                List<Object> l = new ArrayList<Object>();
                for (int i = 0; i < n; i++) l.add(readTag(in, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                while (true) {
                    int t = in.u8();
                    if (t == 0) break;
                    String name = new String(in.bytes(in.u16()), StandardCharsets.UTF_8);
                    m.put(name, readTag(in, t));
                }
                return m;
            }
            case 11:
                in.bytes(in.i32() * 4);
                return "";
            case 12:
                in.bytes(in.i32() * 8);
                return "";
            default:
                throw new IOException("Bad NBT tag " + type);
        }
    }

    @SuppressWarnings("unchecked")
    static String flatten(Object o) {
        if (o == null) return "";
        if (o instanceof String) return (String) o;
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<Object>) o) sb.append(flatten(x));
            return sb.toString();
        }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            StringBuilder sb = new StringBuilder();
            if (m.containsKey("text")) sb.append(flatten(m.get("text")));
            if (m.containsKey("")) sb.append(flatten(m.get("")));
            if (m.containsKey("translate")) {
                String key = flatten(m.get("translate"));
                String fmt = KNOWN.get(key);
                List<String> args = new ArrayList<String>();
                Object with = m.get("with");
                if (with instanceof List) for (Object a : (List<Object>) with) args.add(flatten(a));
                if (fmt == null) {
                    Object fb = m.get("fallback");
                    fmt = fb != null ? flatten(fb) : key;
                    if (fb == null && !args.isEmpty()) {
                        StringBuilder f = new StringBuilder(key);
                        for (String a : args) f.append(' ').append(a);
                        fmt = f.toString();
                        args.clear();
                    }
                }
                sb.append(format(fmt, args));
            }
            Object extra = m.get("extra");
            if (extra != null) sb.append(flatten(extra));
            return sb.toString();
        }
        return String.valueOf(o);
    }

    private static String format(String fmt, List<String> args) {
        StringBuilder sb = new StringBuilder();
        int ai = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '%' && i + 1 < fmt.length()) {
                char n = fmt.charAt(i + 1);
                if (n == 's') {
                    sb.append(ai < args.size() ? args.get(ai) : "");
                    ai++;
                    i++;
                    continue;
                }
                if (Character.isDigit(n) && i + 3 < fmt.length() && fmt.charAt(i + 2) == '$' && fmt.charAt(i + 3) == 's') {
                    int idx = n - '1';
                    sb.append(idx >= 0 && idx < args.size() ? args.get(idx) : "");
                    i += 3;
                    continue;
                }
                if (n == '%') {
                    sb.append('%');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Removes legacy section-sign colour codes. */
    static String strip(String s) {
        return s.replaceAll("§.", "");
    }
}
