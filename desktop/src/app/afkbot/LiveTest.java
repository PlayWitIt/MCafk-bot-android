package app.afkbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Runs the app's real bot code on a PC against a real server (e.g. constantiam.net) with a real Microsoft account,
 * and lets you drive it from the terminal to reproduce bugs.
 *
 *   desktop/run.sh live [server]        (default constantiam.net)
 *   desktop/run.sh live 127.0.0.1:25601 --offline Tester   (local test server from desktop/realserver.sh)
 *
 * The first run prints a sign-in code: enter it at microsoft.com/link. The login is saved in desktop/state/
 * (private tokens - never commit that folder). Commands are read from stdin, one per line:
 *
 *   where                      position, blocks around you, server corrections so far
 *   walk                       the in-app "Test walking" (forward 1.5 s, back 1.5 s)
 *   go <yaw> <ms> [jump]       hold forward facing yaw (0 = south/+z, -90 = east/+x, 90 = west, 180 = north)
 *   containers                 containers within 5 blocks with their coordinates
 *   open <x> <y> <z>           right-click a block (open a chest, press a button...)
 *   take <slot>                shift-click a slot of the open container
 *   close                      close the open container
 *   trace on|off               print every packet sent
 *   say <text>                 chat (or /command)
 *   wait <ms>                  pause (useful in scripted runs: desktop/run.sh live < script.txt)
 *   quit
 */
public final class LiveTest {
    public static void main(String[] a) throws Exception {
        String server = a.length > 0 ? a[0] : "constantiam.net";
        final android.content.Context ctx = new android.content.Context();
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        String offline = a.length > 2 && a[1].equals("--offline") ? a[2] : null; // cracked/local test servers
        if (offline == null && Auth.signedInName(ctx) == null) {
            System.out.println("Signing in with Microsoft - watch for the code below...");
            Auth.signIn(ctx, null);
        }
        System.out.println(offline != null ? "Offline mode as " + offline : "Signed in as " + Auth.signedInName(ctx));
        Bot.Config c = new Bot.Config();
        c.address = server;
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        if (offline != null) {
            c.offline = true;
            c.offlineName = offline;
        }
        final Bot b = new Bot(c, new Bot.SessionSource() {
            public Auth.Session get(boolean force) throws Exception {
                return Auth.session(ctx, force);
            }
        });
        Hub.bot = b;
        b.start();
        for (int i = 0; i < 300 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        Thread.sleep(2000);
        System.out.println("[LIVE] in world=" + World.inWorld + " at " + pos() + " - type commands (see LiveTest.java)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] w = line.trim().split("\\s+");
            if (w[0].isEmpty() || w[0].startsWith("#")) continue;
            try {
                switch (w[0]) {
                    case "where":
                        say("at " + pos() + " corrections=" + Bot.corrections + " in world=" + World.inWorld);
                        int bx = (int) Math.floor(World.selfX), by = (int) Math.floor(World.selfY), bz = (int) Math.floor(World.selfZ);
                        for (int dy = -1; dy <= 2; dy++) {
                            StringBuilder sb = new StringBuilder("  y" + (by + dy) + ":");
                            for (int dz = -1; dz <= 1; dz++)
                                for (int dx = -1; dx <= 1; dx++) {
                                    int st = MapData.stateAt(bx + dx, by + dy, bz + dz);
                                    sb.append(' ').append(st < 0 ? "?" : Shapes.id(st).isEmpty() ? "air" : Shapes.id(st)).append('#').append(st);
                                }
                            say(sb.toString());
                        }
                        break;
                    case "walk":
                        say(b.testWalk());
                        Thread.sleep(3800);
                        break;
                    case "go":
                        b.manual = true;
                        b.lookYaw = Float.parseFloat(w[1]);
                        b.lookPitch = 0;
                        b.inJump = w.length > 3;
                        int c0 = Bot.corrections;
                        double x0 = World.selfX, z0 = World.selfZ;
                        b.inForward = 1;
                        Thread.sleep(Long.parseLong(w[2]));
                        b.inForward = 0;
                        b.inJump = false;
                        Thread.sleep(800);
                        say(String.format("moved %.2f blocks, corrections %d, now %s", Math.hypot(World.selfX - x0, World.selfZ - z0), Bot.corrections - c0, pos()));
                        break;
                    case "hold": { // hold <keys> <ms> [yaw]: f b l r = move, j = jump, s = sprint, n = sneak, 0 = nothing
                        b.manual = true;
                        if (w.length > 3) b.lookYaw = Float.parseFloat(w[3]);
                        b.lookPitch = 0;
                        String k = w[1];
                        int hc0 = Bot.corrections;
                        double hx0 = World.selfX, hy0 = World.selfY, hz0 = World.selfZ;
                        b.inForward = k.contains("f") ? 1 : k.contains("b") ? -1 : 0;
                        b.inStrafe = k.contains("l") ? 1 : k.contains("r") ? -1 : 0;
                        b.inJump = k.contains("j");
                        b.inSprint = k.contains("s");
                        b.inSneak = k.contains("n");
                        Thread.sleep(Long.parseLong(w[2]));
                        b.inForward = 0;
                        b.inStrafe = 0;
                        b.inJump = false;
                        b.inSprint = false;
                        b.inSneak = false;
                        Thread.sleep(800);
                        say(String.format("hold %s: moved %.2f %.2f %.2f, corrections %d, now %s", k, World.selfX - hx0, World.selfY - hy0, World.selfZ - hz0, Bot.corrections - hc0, pos()));
                        break;
                    }
                    case "containers":
                        for (Object[] o : Bot.nearbyContainers(5)) {
                            int[] k = (int[]) o[0];
                            say(String.format("%s at %d %d %d (%.1f away) state=%d", o[1], k[0], k[1], k[2], (Double) o[2], MapData.stateAt(k[0], k[1], k[2])));
                        }
                        break;
                    case "open": {
                        int x = Integer.parseInt(w[1]), y = Integer.parseInt(w[2]), z = Integer.parseInt(w[3]);
                        int face = Bot.faceToward(new int[]{x, y, z});
                        float[] hit = Bot.facePoint(x, y, z, face, World.selfX, World.selfY + 1.62, World.selfZ);
                        say("clicking " + Shapes.id(MapData.stateAt(x, y, z)) + " face " + (int) hit[0] + " at " + hit[1] + "," + hit[2] + "," + hit[3]);
                        b.useBlock(x, y, z, face);
                        Thread.sleep(1500);
                        say("open window=" + Inv.openId + " title=" + Inv.title + " size=" + Inv.containerSize() + " status=" + Inv.status);
                        for (int i = 0; i < Inv.containerSize(); i++)
                            if (Inv.slots.get(i) != null) say("  slot " + i + ": " + Inv.slots.get(i).name + " x" + Inv.slots.get(i).count);
                        break;
                    }
                    case "take":
                        b.clickSlot(Integer.parseInt(w[1]), 0, 1);
                        Thread.sleep(800);
                        break;
                    case "close":
                        b.closeContainer();
                        break;
                    case "trace":
                        Bot.traceSent = w.length > 1 && w[1].equals("on");
                        break;
                    case "say":
                        b.sendChat(line.trim().substring(4));
                        break;
                    case "wait":
                        Thread.sleep(Long.parseLong(w[1]));
                        break;
                    case "quit":
                        b.stop();
                        Thread.sleep(500);
                        System.exit(0);
                    default:
                        say("unknown command: " + w[0]);
                }
            } catch (Exception e) {
                say("error: " + e);
            }
        }
        b.stop();
        System.exit(0);
    }

    static String pos() {
        return String.format("%.2f %.3f %.2f", World.selfX, World.selfY, World.selfZ);
    }

    static void say(String s) {
        System.out.println("[LIVE] " + s);
    }
}
