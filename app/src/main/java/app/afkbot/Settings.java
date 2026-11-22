package app.afkbot;

import android.content.Context;
import android.content.SharedPreferences;

/** Saves/loads all module settings. One shared Config is used by both the screen and the running bot. */
final class Settings {
    private Settings() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Slot.prefs("settings"), Context.MODE_PRIVATE); // each bot slot has its own settings
    }

    /** The name this slot plays as: the offline name, or the signed-in account's ("" if neither). */
    static String accountName(Context c) {
        Bot.Config cfg = get(c);
        if (cfg.offline) return cfg.offlineName == null ? "" : cfg.offlineName;
        String n = Auth.signedInName(c);
        return n == null ? "" : n;
    }

    /** The live config (shared with the bot when it is running). */
    static synchronized Bot.Config get(Context c) {
        if (Hub.config == null) Hub.config = load(prefs(c));
        return Hub.config;
    }

    static Bot.Config load(SharedPreferences p) {
        Bot.Config c = new Bot.Config();
        c.address = p.getString("address", ""); // no server by default: Direct connect, or pick a saved one
        c.offline = p.getBoolean("offline", false);
        c.offlineName = p.getString("offlineName", "");
        c.autoReconnect = p.getBoolean("autoReconnect", true);
        c.autoRespawn = p.getBoolean("autoRespawn", true);
        c.antiAfk = p.getBoolean("antiAfk", true);
        c.afkJump = p.getBoolean("afkJump", true);
        c.afkSpin = p.getBoolean("afkSpin", true);
        c.afkSwing = p.getBoolean("afkSwing", true);
        c.afkSneak = p.getBoolean("afkSneak", false);
        c.afkRandom = p.getBoolean("afkRandom", true);
        c.afkSeconds = p.getInt("afkSeconds", p.getInt("jumpSeconds", 30)); // older versions used jumpSeconds
        c.autoLog = p.getBoolean("autoLog", true);
        c.logIgnoreFriends = p.getBoolean("logIgnoreFriends", true);
        c.logHearts = p.getInt("logHearts", p.getInt("logoutHearts", 5));
        c.logDistance = p.getInt("logDistance", p.getInt("logoutPlayerDistance", 24));
        c.rejoinMinutes = p.getInt("rejoinMinutes", p.getInt("safetyRejoinMinutes", 0));
        c.visualRange = p.getBoolean("visualRange", true);
        c.friends = p.getString("friends", "");
        c.minimap = p.getBoolean("minimap", true);
        c.viewDistance = p.getInt("viewDistance", 6);
        return c;
    }

    static void save(Context ctx, Bot.Config c) {
        prefs(ctx).edit()
                .putString("address", c.address)
                .putBoolean("offline", c.offline)
                .putString("offlineName", c.offlineName)
                .putBoolean("autoReconnect", c.autoReconnect)
                .putBoolean("autoRespawn", c.autoRespawn)
                .putBoolean("antiAfk", c.antiAfk)
                .putBoolean("afkJump", c.afkJump)
                .putBoolean("afkSpin", c.afkSpin)
                .putBoolean("afkSwing", c.afkSwing)
                .putBoolean("afkSneak", c.afkSneak)
                .putBoolean("afkRandom", c.afkRandom)
                .putInt("afkSeconds", c.afkSeconds)
                .putBoolean("autoLog", c.autoLog)
                .putBoolean("logIgnoreFriends", c.logIgnoreFriends)
                .putInt("logHearts", c.logHearts)
                .putInt("logDistance", c.logDistance)
                .putInt("rejoinMinutes", c.rejoinMinutes)
                .putBoolean("visualRange", c.visualRange)
                .putString("friends", c.friends)
                .putBoolean("minimap", c.minimap)
                .putInt("viewDistance", c.viewDistance)
                .apply();
    }
}
