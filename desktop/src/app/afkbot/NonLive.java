package app.afkbot;

import java.io.File;

/**
 * The Naughty or Nice add-on against a real vanilla server: the bot whispers itself from the DMs path and the
 * add-on must read both sides as one conversation. desktop/run.sh nonlive [address] (default the local test server).
 */
public class NonLive {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    public static void main(String[] a) throws Exception {
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        NonIntel.init(new File(System.getProperty("java.io.tmpdir"), "non-live-" + System.nanoTime()));
        NonIntel.installed = true;
        Bot.Config c = new Bot.Config();
        c.address = a.length > 0 ? a[0] : "127.0.0.1:25601";
        c.offline = true;
        c.offlineName = "NonTester";
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        Bot b = new Bot(c, null);
        Hub.bot = b;
        b.start();
        for (int i = 0; i < 150 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        Thread.sleep(2500);
        check("joined (" + NonIntel.server() + ")", World.inWorld && !NonIntel.server().isEmpty());
        check("the secure-chat flag was read: off on an offline test server", !b.secureChat);
        check("you're on the tab list but not counted as a player", NonIntel.get("NonTester") == null);

        String why = NonIntel.send("NonTester", "hello from the dms tab");
        check("sending is allowed here (" + why + ")", why == null);
        NonIntel.Thread t = null;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            t = NonIntel.threadCopy("NonTester");
            if (t.msgs.size() >= 2) break;
        }
        StringBuilder seen = new StringBuilder();
        for (NonIntel.Line l : t.msgs) seen.append(l.me ? "me:" : "them:").append(l.text).append(l.state == 0 ? "" : "(" + l.state + ")").append(" | ");
        System.out.println("conversation: " + seen);
        boolean mineConfirmed = false, theirs = false;
        for (NonIntel.Line l : t.msgs) {
            if (l.me && l.state == 0 && l.text.equals("hello from the dms tab")) mineConfirmed = true;
            if (!l.me && l.text.equals("hello from the dms tab")) theirs = true;
        }
        check("the server's \"you whisper to\" confirms the sent line", mineConfirmed);
        check("the incoming whisper (read from its chat kind) lands in the same conversation", theirs);
        check("still connected: an unsigned /w didn't get the bot kicked", World.inWorld && Hub.bot != null);

        NonIntel.send("Nobody_Here_At_All", "anyone?");
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            if (NonIntel.threadCopy("Nobody_Here_At_All").msgs.get(0).state == NonIntel.FAILED) break;
        }
        NonIntel.Line n = NonIntel.threadCopy("Nobody_Here_At_All").msgs.get(0);
        check("to someone who isn't there: marked not sent (" + n.state + ", " + n.error + ")", n.state == NonIntel.FAILED);
        System.out.println("recent chat: " + Hub.chatText().replace('\n', '|'));
        b.stop();
        Thread.sleep(500);
        System.out.println("DONE: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
