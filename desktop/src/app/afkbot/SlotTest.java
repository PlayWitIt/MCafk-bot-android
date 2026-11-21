package app.afkbot;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Bot slots on a PC, the way the phone runs them: this process is slot 0 and starts a second Java process as slot 1
 * (the phone uses an Android process named "app.afkbot:bot1"). Both join the local test server at the same time with
 * their own names, publish their status to each other through files/slots/, and hammer one shared cache file to show
 * that safe writes never leave half a file. Usage: desktop/run.sh slot (needs desktop/realserver.sh running).
 */
public class SlotTest {
    private static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (ok) pass++;
        else fail++;
    }

    static final android.content.Context CTX = new android.content.Context();
    static final File SHARED = new File(CTX.getFilesDir(), "slot-test-shared.bin");

    static Bot join(String name) throws Exception {
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        Bot.Config c = new Bot.Config();
        c.address = "127.0.0.1:25601";
        c.offline = true;
        c.offlineName = name;
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        Bot b = new Bot(c, null);
        Hub.bot = b;
        Hub.running = true;
        b.start();
        for (int i = 0; i < 150 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        return b;
    }

    /** Big file contents that are easy to tell apart: all one byte. */
    static byte[] fill(byte v) {
        byte[] b = new byte[256 * 1024];
        java.util.Arrays.fill(b, v);
        return b;
    }

    static void hammer(byte v, int times) throws Exception {
        for (int i = 0; i < times; i++) Slot.write(SHARED, fill(v));
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("child")) {
            child();
            return;
        }
        check("this process is slot 0 (process \"java\")", Slot.current() == 0);
        check("slot 0 keeps the original preference names", Slot.prefs("settings").equals("settings") && Slot.prefs("auth").equals("auth"));
        File dir = new File(CTX.getFilesDir(), "slots");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        check("only slot 0 exists at first", Slot.exists(CTX, 0) && !Slot.exists(CTX, 1));
        int s1 = Slot.add(CTX), s2 = Slot.add(CTX);
        check("adding bots gives slots 1 and 2", s1 == 1 && s2 == 2 && Slot.exists(CTX, 1) && Slot.exists(CTX, 2));
        new File(dir, "2.on").delete();
        Slot.publish(CTX, "Ghost", "Connected", Hub.GREEN, true, true);
        File ghost = new File(dir, "0.status");
        String[] g = new String(java.nio.file.Files.readAllBytes(ghost.toPath()), StandardCharsets.UTF_8).split("\n");
        g[4] = Long.toString(System.currentTimeMillis() - 120000);
        java.nio.file.Files.write(ghost.toPath(), String.join("\n", g).getBytes(StandardCharsets.UTF_8));
        check("a running bot with no heartbeat for 2 minutes counts as gone", Slot.read(CTX, 0).stale());
        Slot.write(SHARED, fill((byte) 1));

        // slot 1 in its own process, like on the phone
        String cp = System.getProperty("java.class.path");
        Process child = new ProcessBuilder("java", "-Dafk.process=app.afkbot:bot1", "-Dafk.cmd=" + System.getProperty("afk.cmd"), "-cp", cp,
                "app.afkbot.SlotTest", "child").inheritIO().start();

        Bot me = join("SlotBotOne");
        check("slot 0's bot joined", World.inWorld);
        Slot.publish(CTX, "SlotBotOne", "Connected", Hub.GREEN, true, true);

        Slot.Status other = null;
        for (int i = 0; i < 200; i++) {
            other = Slot.read(CTX, 1);
            if (other.running && other.name.equals("SlotBotTwo")) break;
            Thread.sleep(100);
        }
        check("slot 0 sees slot 1 running as SlotBotTwo (" + other.status + ")", other.running && other.name.equals("SlotBotTwo") && other.color == Hub.GREEN);
        boolean seen = false;
        for (int i = 0; i < 50 && !seen; i++) {
            seen = World.names().containsValue("SlotBotTwo");
            Thread.sleep(100);
        }
        check("both bots are on the server at once (slot 1 is on slot 0's tab list)", seen && World.names().containsValue("SlotBotOne"));

        // both processes write the shared file at the same time; reads must always see one whole version
        int torn = 0, reads = 0;
        Thread w = new Thread(new Runnable() {
            public void run() {
                try {
                    hammer((byte) 1, 150);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        w.start();
        while (w.isAlive()) {
            byte[] b = java.nio.file.Files.readAllBytes(SHARED.toPath());
            reads++;
            if (b.length != 256 * 1024) {
                torn++;
                continue;
            }
            byte v = b[0];
            for (byte x : b) if (x != v) {
                torn++;
                break;
            }
        }
        check("cache writes from two processes never leave half a file (" + reads + " reads)", torn == 0 && reads > 20);

        child.waitFor();
        Slot.Status after = Slot.read(CTX, 1);
        check("after slot 1 stops, its status says so", !after.running && after.status.equals("Stopped"));
        check("slot 1's process exited cleanly", child.exitValue() == 0);
        me.stop();
        new File(dir, "1.on").delete();
        new File(dir, "1.status").delete();
        new File(dir, "0.status").delete();
        SHARED.delete();
        System.out.println("DONE: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Slot 1: its own process with its own statics, its own bot, its own status. */
    static void child() throws Exception {
        boolean ok = Slot.current() == 1 && Slot.prefs("settings").equals("settings_bot1") && Slot.prefs("auth").equals("auth_bot1");
        System.out.println("[slot 1] process slot " + Slot.current() + ", prefs " + Slot.prefs("settings") + (ok ? "" : "  <- WRONG"));
        Bot b = join("SlotBotTwo");
        Slot.publish(CTX, "SlotBotTwo", World.inWorld ? "Connected" : "Failed", World.inWorld ? Hub.GREEN : Hub.RED, World.inWorld, true);
        hammer((byte) 2, 150); // at the same time as slot 0
        Thread.sleep(3000);
        b.stop();
        Slot.publish(CTX, "SlotBotTwo", "Stopped", Hub.GREY, false, true);
        System.exit(ok ? 0 : 3);
    }
}
