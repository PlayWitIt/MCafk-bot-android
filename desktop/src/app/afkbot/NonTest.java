package app.afkbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Checks the Naughty or Nice add-on's logic with no server and no phone: desktop/run.sh non
 */
public class NonTest {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "non-test-" + System.nanoTime());
        NonIntel.init(dir);
        NonIntel.installed = true;
        NonIntel.joined("Test.Server");
        check("joining picks the server's data", NonIntel.server().equals("test.server"));

        UUID rick = UUID.randomUUID(), hana = UUID.randomUUID();
        NonIntel.tabAdd(rick, "RandomRick");
        check("players already online when you join aren't counted as joins", count("join") == 0 && NonIntel.online("RandomRick"));
        NonIntel.setVerdict("Helpful_Hana", NonIntel.Verdict.NAUGHTY);
        NonIntel.testJoinedAgo(60000);
        NonIntel.tabAdd(hana, "Helpful_Hana");
        check("a join later is (" + count("join") + ")", count("join") == 1 && NonIntel.get("Helpful_Hana").lastJoin > 0);
        NonIntel.tabRemove(hana);
        check("leaving is noted", count("leave") == 1 && !NonIntel.get("Helpful_Hana").online);

        NonIntel.seen("RandomRick", 1200.4, 64, -3400.6, 42, "minecraft:overworld");
        NonIntel.Person r = NonIntel.get("RandomRick");
        check("coming into view keeps where and when", r.sightings == 1 && Math.round(r.x) == 1200 && Math.round(r.z) == -3401 && r.posAt > 0 && count("seen") == 1);
        Thread.sleep(30);
        NonIntel.gone("RandomRick");
        check("time in view adds up", NonIntel.get("RandomRick").nearMs >= 20);

        // Chat and whispers, in the formats servers use.
        NonIntel.chat("RandomRick", "anyone selling obby? at 1200 64 -3400", "<RandomRick> anyone selling obby? at 1200 64 -3400", false);
        check("public chat is kept on their page", NonIntel.get("RandomRick").messages == 1 && NonIntel.get("RandomRick").chat.size() == 1);
        check("coordinates in chat are intel", count("coords") == 1);
        NonIntel.chat("RandomRick", "i have 64 apples", "<RandomRick> i have 64 apples", false);
        check("...small numbers aren't", count("coords") == 1);
        NonIntel.chat(null, "Helpful_Hana whispers: hey you there?", "Helpful_Hana whispers: hey you there?", true);
        NonIntel.chat(null, "[12:34] From RandomRick: psst", "[12:34] From RandomRick: psst", true);
        NonIntel.chat(null, "[RandomRick -> me] second", "[RandomRick -> me] second", true);
        NonIntel.chat(null, "to Helpful_Hana: yeah", "to Helpful_Hana: yeah", true);
        NonIntel.chat(null, "You whisper to Helpful_Hana: one more", "You whisper to Helpful_Hana: one more", true);
        NonIntel.Thread h = NonIntel.threadCopy("Helpful_Hana");
        NonIntel.Thread k = NonIntel.threadCopy("RandomRick");
        check("whispers in each format land in the right conversation (" + h.msgs.size() + ", " + k.msgs.size() + ")",
                h.msgs.size() == 3 && !h.msgs.get(0).me && h.msgs.get(1).me && h.msgs.get(2).text.equals("one more") && k.msgs.size() == 2);
        check("theirs are unread until you look; yours mark them read", k.unread() == 2 && h.unread() == 0);
        NonIntel.viewing("RandomRick");
        check("looking at a conversation reads it", NonIntel.threadCopy("RandomRick").unread() == 0 && NonIntel.unread() == 0);
        NonIntel.viewing(null);
        check("whispers aren't public chat", NonIntel.get("Helpful_Hana").messages == 0);

        // A sent line waits for the echo, and a "not online" reply marks it.
        sending("Nobody_Here");
        NonIntel.chat(null, "Player not found.", "Player not found.", true);
        NonIntel.Thread n = NonIntel.threadCopy("Nobody_Here");
        check("\"Player not found\" marks the line just sent as not sent", n.msgs.get(0).state == NonIntel.FAILED && n.msgs.get(0).error.contains("not found"));
        sending("Helpful_Hana");
        NonIntel.chat(null, "To Helpful_Hana: echo me", "To Helpful_Hana: echo me", true);
        h = NonIntel.threadCopy("Helpful_Hana");
        check("the server's echo confirms a sent line instead of adding it twice", h.msgs.size() == 4 && h.msgs.get(3).state == 0 && h.msgs.get(3).text.equals("echo me"));
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 90; i++) words.append("word ");
        List<String> parts = NonIntel.split(words.toString().trim(), 240);
        check("long messages are split to fit", parts.size() == 2 && parts.get(0).length() <= 240);

        // Kept between sessions.
        NonIntel.setGroups("RandomRick", java.util.Arrays.asList("SCG", " scg ", "Alts"));
        check("groups are tidied", NonIntel.get("RandomRick").groups.size() == 2);
        NonIntel.left("test over");
        NonIntel.joined("other.server");
        check("another server has its own data", NonIntel.people().isEmpty());
        NonIntel.joined("test.server");
        NonIntel.Person back = NonIntel.get("Helpful_Hana");
        check("saved and read back", back != null && back.verdict == NonIntel.Verdict.NAUGHTY && NonIntel.get("RandomRick").groups.contains("SCG")
                && NonIntel.threadCopy("Helpful_Hana").msgs.size() == 4 && count("coords") == 1);

        // Importing lists from the PC mod.
        JSONObject pack = new JSONObject().put("format", "naughty-or-nice-share").put("from", "Me").put("server", "server:test.server")
                .put("players", new JSONArray()
                        .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Grp_A1").put("verdict", "NICE").put("groups", new JSONArray().put("Alpha"))
                                .put("details", new JSONArray().put(new JSONObject().put("text", "base at spawn"))))
                        .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Pal").put("verdict", "NICE").put("friend", true))
                        .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Helpful_Hana").put("verdict", "NONE").put("groups", new JSONArray().put("Bravo"))));
        byte[] gz = gzip(pack.toString().getBytes("UTF-8"));
        NonIntel.ImportResult r1 = NonIntel.importShare("look: NN1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(gz), null);
        check("a share code imports (" + r1 + ")", r1.added == 2 && r1.updated == 1 && NonIntel.get("Grp_A1").verdict == NonIntel.Verdict.NICE
                && NonIntel.get("Grp_A1").groups.contains("Alpha") && NonIntel.get("Grp_A1").note.equals("base at spawn")
                && NonIntel.get("Pal").verdict == NonIntel.Verdict.FRIEND);
        check("...without wiping a list you set here", NonIntel.get("Helpful_Hana").verdict == NonIntel.Verdict.NAUGHTY && NonIntel.get("Helpful_Hana").groups.contains("Bravo"));

        // A share link: locked like the mod locks it, fetched from the "paste site".
        byte[] key = new byte[16];
        new java.security.SecureRandom().nextBytes(key);
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] locked = c.doFinal(gzip(pack.put("players", new JSONArray().put(new JSONObject().put("name", "Linked_Lou").put("verdict", "LOOK"))).toString().getBytes("UTF-8")));
        byte[] body = new byte[12 + locked.length];
        System.arraycopy(nonce, 0, body, 0, 12);
        System.arraycopy(locked, 0, body, 12, locked.length);
        final String paste = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body);
        final String[] asked = new String[1];
        String link = "NN2:bAbc123." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        NonIntel.ImportResult r2 = NonIntel.importShare("my lists " + link, new NonIntel.Fetcher() {
            public String get(String url) {
                asked[0] = url;
                return paste + "\n";
            }
        });
        check("a share link is fetched and unlocked (" + asked[0] + ", " + r2 + ")", "https://bytebin.lucko.me/Abc123".equals(asked[0])
                && NonIntel.get("Linked_Lou") != null && NonIntel.get("Linked_Lou").verdict == NonIntel.Verdict.LOOK);
        boolean refused = false;
        try {
            NonIntel.importShare("hello", null);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("random text is refused with a message", refused);

        NonIntel.installed = false;
        NonIntel.chat("RandomRick", "not watching now", "<RandomRick> not watching now", false);
        check("switched off, it records nothing", NonIntel.get("RandomRick").messages == 2);
        System.out.println("DONE: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static int count(String kind) {
        int n = 0;
        for (NonIntel.Event e : NonIntel.events()) if (e.kind.equals(kind)) n++;
        return n;
    }

    /** A line as the DMs box sends it, without a bot (it's put in as "sending"). */
    static void sending(String to) {
        NonIntel.testSending(to, "echo me");
    }

    static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(b);
        g.write(data);
        g.close();
        return b.toByteArray();
    }
}
