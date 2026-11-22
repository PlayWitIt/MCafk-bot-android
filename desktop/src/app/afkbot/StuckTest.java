package app.afkbot;

/**
 * Walks the bot over a course of everyday terrain the way the Play screen drives it (keys held, any direction,
 * crouching or not) and counts every moment it gets stuck (keys held, not moving, nothing in the way) and every
 * time the server pulls it back. Usage: desktop/run.sh stuck (needs desktop/realserver.sh running).
 */
public class StuckTest {
    static void cmd(String c) throws Exception {
        PreviewTest.cmd(c);
    }

    static final int X0 = 60, X1 = 100, Z0 = 0, Z1 = 24, Y = -60;
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (ok) pass++;
        else fail++;
    }

    public static void main(String[] a) throws Exception {
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        Bot.Config c = new Bot.Config();
        c.address = "127.0.0.1:25601";
        c.offline = true;
        c.offlineName = "Walker";
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        Bot b = new Bot(c, null);
        b.start();
        for (int i = 0; i < 150 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        cmd("difficulty peaceful");
        cmd("gamemode survival Walker");
        build();
        boolean only = a.length > 0;
        String[] modes = only ? a : new String[]{"walk", "sneak", "sprint"};
        for (String mode : modes) run(b, mode);
        if (!only) {
            edges(b);
            lowGap(b);
        }
        b.stop();
        System.out.println("DONE: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void build() throws Exception {
        cmd("fill " + (X0 - 2) + " -61 " + (Z0 - 2) + " " + (X1 + 2) + " -50 " + (Z1 + 2) + " air");
        cmd("fill " + (X0 - 2) + " -62 " + (Z0 - 2) + " " + (X1 + 2) + " -62 " + (Z1 + 2) + " dirt");
        cmd("fill " + (X0 - 2) + " -61 " + (Z0 - 2) + " " + (X1 + 2) + " -61 " + (Z1 + 2) + " grass_block");
        // outer wall (walking into it and along it)
        cmd("fill " + (X0 - 1) + " -60 " + (Z0 - 1) + " " + (X1 + 1) + " -59 " + (Z0 - 1) + " stone_bricks");
        cmd("fill " + (X0 - 1) + " -60 " + (Z1 + 1) + " " + (X1 + 1) + " -59 " + (Z1 + 1) + " stone_bricks");
        cmd("fill " + (X0 - 1) + " -60 " + (Z0 - 1) + " " + (X0 - 1) + " -59 " + (Z1 + 1) + " stone_bricks");
        cmd("fill " + (X1 + 1) + " -60 " + (Z0 - 1) + " " + (X1 + 1) + " -59 " + (Z1 + 1) + " stone_bricks");
        // strips of ground you step on and off
        cmd("fill 64 -61 0 66 -61 24 dirt_path");
        cmd("fill 68 -61 0 70 -61 24 farmland[moisture=7]");
        cmd("fill 72 -60 0 73 -60 24 oak_slab[type=bottom]");
        cmd("fill 75 -60 0 75 -60 24 white_carpet");
        cmd("fill 77 -60 0 78 -60 24 snow[layers=2]");
        cmd("fill 80 -60 0 80 -60 24 snow[layers=4]");
        cmd("fill 82 -60 0 82 -60 24 stone_stairs[facing=east]");
        cmd("fill 83 -60 0 84 -60 24 stone_bricks");
        cmd("fill 85 -60 0 85 -60 24 stone_stairs[facing=west]");
        // a dirt-path step against a full block (the 1/16 lip)
        cmd("fill 87 -61 0 87 -61 24 dirt_path");
        // posts, a fence line with gaps, a lantern
        for (int z = 2; z <= 22; z += 4) cmd("setblock 62 -60 " + z + " oak_fence");
        for (int z = 1; z <= 23; z += 3) cmd("setblock 90 -60 " + z + " cobblestone_wall");
        // a 1.5-high gap you can only crouch under: slab ceiling at y -58.5
        cmd("fill 93 -59 4 95 -59 8 oak_slab[type=top]");
        cmd("fill 93 -60 4 95 -60 4 air");
        // a raised platform with a drop edge (crouch must stop at the edge)
        cmd("fill 93 -60 12 99 -60 20 stone");
        // flowers and grass (no collision)
        cmd("fill 60 -60 10 99 -60 10 short_grass replace air");
        cmd("time set noon");
        Thread.sleep(2500);
    }

    /** Walks in 16 directions from the middle, back and forth, counting stuck ticks and pull-backs. */
    static void run(Bot b, String mode) throws Exception {
        System.out.println("--- " + mode);
        boolean sneak = mode.equals("sneak"), sprint = mode.equals("sprint");
        int stuckTotal = 0, corrTotal = 0, legs = 0;
        StringBuilder where = new StringBuilder();
        for (int d = 0; d < 16; d++) {
            float yaw = d * 22.5f + 7; // not exactly on the axes, as a thumb never is
            cmd("tp Walker 80.5 -59 12.5 " + yaw + " 0");
            Thread.sleep(1200);
            int c0 = Bot.corrections;
            b.manual = true;
            b.lookYaw = yaw;
            b.lookPitch = 0;
            b.inSneak = sneak;
            b.inSprint = sprint;
            b.inForward = 1;
            double px = World.selfX, pz = World.selfZ;
            int stuck = 0, run = 0;
            for (int t = 0; t < (sneak ? 140 : 60); t++) { // 7 s crouching, 3 s otherwise
                Thread.sleep(50);
                double x = World.selfX, z = World.selfZ, moved = Math.hypot(x - px, z - pz);
                px = x;
                pz = z;
                // stuck: barely moving although nothing should stop it here (walls of the arena aside)
                boolean nearWall = x < X0 + 0.5 || x > X1 + 0.5 || z < Z0 + 0.5 || z > Z1 + 0.5;
                if (moved < 0.004 && !nearWall && !atDropEdge(x, z, sneak)) {
                    if (++run == 6) {
                        stuck++;
                        if (where.length() < 1500) where.append(String.format(java.util.Locale.US, "  yaw %.0f stuck at %.2f %.2f %.2f  under: %s  feet: %s%n",
                                yaw, x, World.selfY, z, Shapes.id(MapData.stateAt((int) Math.floor(x), (int) Math.floor(World.selfY - 0.2), (int) Math.floor(z))),
                                Shapes.id(MapData.stateAt((int) Math.floor(x), (int) Math.floor(World.selfY), (int) Math.floor(z)))));
                    }
                } else run = 0;
            }
            b.inForward = 0;
            b.inSneak = false;
            b.inSprint = false;
            Thread.sleep(400);
            stuckTotal += stuck;
            corrTotal += Bot.corrections - c0;
            legs++;
        }
        System.out.print(where);
        check(mode + ": never stuck (" + stuckTotal + " stuck moments in " + legs + " walks)", stuckTotal == 0);
        check(mode + ": never pulled back by the server (" + corrTotal + ")", corrTotal == 0);
    }

    /** Crouching on the platform (93..99 x 12..20, top at -59): walk at every edge; never fall off. */
    static void edges(Bot b) throws Exception {
        System.out.println("--- crouching at edges");
        int fell = 0, pulled = 0;
        double worstOver = 0;
        for (int d = 0; d < 8; d++) {
            float yaw = d * 45 + 10;
            cmd("tp Walker 96.5 -59 16.5 " + yaw + " 0");
            Thread.sleep(1200);
            int c0 = Bot.corrections; // (the teleport itself counts as one: start after it)
            b.manual = true;
            b.lookYaw = yaw;
            b.inSneak = true;
            Thread.sleep(150);
            b.inForward = 1;
            Thread.sleep(4000);
            b.inForward = 0;
            Thread.sleep(300);
            if (World.selfY < -59.5) fell++;
            pulled += Bot.corrections - c0;
            double over = Math.max(Math.max(93 - World.selfX, World.selfX - 100), Math.max(12 - World.selfZ, World.selfZ - 21));
            worstOver = Math.max(worstOver, over);
            b.inSneak = false;
            Thread.sleep(300);
        }
        check("crouching: never fell off an edge (" + fell + " of 8), at most " + String.format(java.util.Locale.US, "%.2f", worstOver) + " over it (the game lets you hang 0.3)", fell == 0 && worstOver <= 0.31);
        check("crouching at edges: never pulled back (" + pulled + ")", pulled == 0);
    }

    /** A gap 1.5 high (slab ceiling): you only get through crouching. */
    static void lowGap(Bot b) throws Exception {
        System.out.println("--- crouching under a 1.5-high gap");
        cmd("tp Walker 91.5 -60 6.5 -90 0");
        Thread.sleep(1200);
        int c0 = Bot.corrections;
        b.manual = true;
        b.lookYaw = -90;
        b.inSneak = true;
        Thread.sleep(150);
        b.inForward = 1;
        Thread.sleep(5000);
        b.inForward = 0;
        b.inSneak = false;
        Thread.sleep(500);
        check(String.format(java.util.Locale.US, "crouched through (x %.2f, past 96)", World.selfX), World.selfX > 96);
        check("no pull-back under the gap (" + (Bot.corrections - c0) + ")", Bot.corrections == c0);
    }

    /** Crouching at the platform's edge is supposed to stop you. */
    static boolean atDropEdge(double x, double z, boolean sneak) {
        return sneak && x >= 92.2 && x <= 99.8 && z >= 11.2 && z <= 20.8;
    }
}
