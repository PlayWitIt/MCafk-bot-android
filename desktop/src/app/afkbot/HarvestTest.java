package app.afkbot;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The Harvest Hand add-on (a separate repo: harvest-hand-afk) run by the bot against the local test server: builds a
 * walled farm with a water source and ripe crops, gives the bot a Fortune hoe and seeds, places a chest the way you
 * would (so it's added by itself), loads the add-on from its file, presses Start and checks it harvests, replants,
 * uses Fortune, picks up and stores. Usage: desktop/run.sh harvest [path/to/harvesthand.js] (needs
 * desktop/realserver.sh running).
 */
public class HarvestTest {
    private static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (ok) pass++;
        else fail++;
    }

    static void cmd(String c) throws Exception {
        PreviewTest.cmd(c);
    }

    // the farm: walls around x 39..49, z 39..49; farmland 40..48 at y -61 with water in the middle
    static final int X0 = 40, X1 = 48, Z0 = 40, Z1 = 48, Y = -60;

    static ModEngine.Mod mod;

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0] : "../harvest-hand-afk/harvesthand.js";
        String code = new String(java.nio.file.Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        Hub.versionLoader = new Hub.VersionLoader() {
            public ProtoDef.VersionData load(int p) throws Exception {
                return ProtoDef.load(new java.io.FileInputStream("app/src/main/assets/proto/" + p + ".json"));
            }
        };
        ModEngine.host = new TestHost();
        Bot.Config c = new Bot.Config();
        c.address = "127.0.0.1:25601";
        c.offline = true;
        c.offlineName = "HarvestBot";
        c.antiAfk = false;
        c.autoReconnect = false;
        c.autoLog = false;
        c.visualRange = false;
        Hub.config = c;
        Bot b = new Bot(c, null);
        Hub.bot = b;
        Hub.running = true;
        b.start();
        for (int i = 0; i < 150 && !(World.inWorld && World.selfY != 0); i++) Thread.sleep(100);
        check("joined", World.inWorld);

        cmd("difficulty peaceful"); // the flat test world spawns slimes: no fights in this test
        cmd("gamerule spawn_mobs false");
        cmd("time set noon"); // crops only grow in the light
        cmd("gamerule advance_time false");
        buildFarm();
        cmd("gamemode survival HarvestBot");
        cmd("clear HarvestBot");
        cmd("effect clear HarvestBot");
        cmd("give HarvestBot minecraft:diamond_hoe[minecraft:enchantments={fortune:3}]");
        cmd("give HarvestBot minecraft:wheat_seeds 8");
        cmd("give HarvestBot minecraft:bread 4");
        toFarm();

        mod = new ModEngine.Mod("harvesthand", code);
        ModEngine.start(mod, null);
        ModEngine.flush();
        check("the add-on loaded (" + (mod.lastError == null ? "no errors" : mod.lastError) + ")", mod.running);
        check("its settings form has the Fabric mod's 14 settings", mod.settingsSpec != null && mod.settingsSpec.length() == 14);
        check("defaults as the Fabric mod: radius 64, 2 a tick, boxes auto, min health 8", mod.config != null && mod.config.optInt("radius") == 64
                && mod.config.optInt("perTick") == 2 && "auto".equals(mod.config.optString("boxes")) && mod.config.optInt("minHealth") == 8
                && mod.config.optBoolean("oneCropPerBox") && mod.config.optBoolean("till") && mod.config.optBoolean("reconnect"));
        check("it offers Start here", mod.buttons != null && mod.buttons.toString().contains("\"start\""));

        // A chest placed "by you": the add-on sees the click with a chest in hand, then the chest there.
        // a gap in the west wall, with a barrel in it (it opens with the wall on top): placed as you would
        for (int t = 0; t < 3 && !Hub.chatText().contains("You placed a chest"); t++) {
            cmd("setblock 39 -60 44 air");
            for (int w = 0; w < 40 && !Shapes.id(MapData.stateAt(39, -60, 44)).isEmpty(); w++) Thread.sleep(50);
            ModEngine.emit("useBlock", new org.json.JSONObject().put("x", 39).put("y", -61).put("z", 44).put("face", 1).put("item", "barrel"));
            cmd("setblock 39 -60 44 minecraft:barrel[facing=east]");
            Thread.sleep(1500);
        }

        int ripeBefore = countRipe();
        System.out.println("ripe crops before: " + ripeBefore);
        ModEngine.press(mod, "start");
        Thread.sleep(2000);
        check("started (" + statusLine() + ")", mod.buttons != null && mod.buttons.toString().contains("\"stop\""));
        check("the chest you placed is used", logHas("1 of your chest"));

        long until = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(2500);
            System.out.println("  " + statusLine() + "  | ripe " + countRipe() + ", wheat on you " + count("wheat") + ", seeds " + count("wheat_seeds"));
        }
        int ripeAfter = countRipe(), growing = countGrowing();
        check("ripe crops harvested (" + ripeBefore + " -> " + ripeAfter + ")", ripeAfter <= ripeBefore / 10);
        check("replanted: " + growing + " young crops", growing >= ripeBefore * 9 / 10);
        check("the harvest is in the inventory (" + count("wheat") + " wheat, " + count("carrot") + " carrots)", count("wheat") > 20 && count("carrot") > 5);
        check("Fortune was used (" + statusText() + ")", statusText().contains("Fortune 3"));

        ModEngine.press(mod, "pause");
        Thread.sleep(600);
        check("pause", statusText().contains("Paused"));
        ModEngine.press(mod, "pause");
        Thread.sleep(600);
        check("carry on", !statusText().contains("Paused"));
        ModEngine.press(mod, "stop");
        Thread.sleep(600);
        check("stop: Start here again, and the bot is the bot's again", mod.buttons.toString().contains("\"start\"") && !b.modControl);

        storing();
        drainedChest();
        boxes();
        bareFarm();
        keepUp();
        byHandAndEating();
        commands();
        kicked(b, c);

        b.stop();
        System.out.println("DONE: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Fills the inventory with stone, leaving `free` slots (and the hoe and some seeds). */
    static void packInventory(int free) throws Exception {
        cmd("clear HarvestBot");
        cmd("give HarvestBot minecraft:diamond_hoe[minecraft:enchantments={fortune:3}]");
        cmd("give HarvestBot minecraft:wheat_seeds 16");
        cmd("give HarvestBot minecraft:carrot 16");
        cmd("give HarvestBot minecraft:bread 4");
        cmd("give HarvestBot minecraft:stone " + (64 * (36 - 4 - free)));
        Thread.sleep(1500);
    }

    static void runFor(int ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(3000);
            System.out.println("  " + statusLine() + "  | ripe " + countRipe() + ", wheat on you " + count("wheat") + ", empty slots " + emptySlots());
        }
    }

    static int emptySlots() {
        int n = 0;
        List<Inv.Item> w = Inv.inventory;
        for (int i = 9; i < 45 && i < w.size(); i++) if (w.get(i) == null) n++;
        return n;
    }

    /** A full inventory goes into the chest you placed; a stack of each seed stays. */
    static void storing() throws Exception {
        System.out.println("--- storing in your chest");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " wheat[age=7]");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z0 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        cmd("data merge block 39 -60 44 {Items:[]}");
        packInventory(2);
        toFarm();
        ModEngine.press(mod, "start");
        runFor(40000);
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
        String log = Hub.chatText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) stored").matcher(log);
        int stored = 0;
        while (m.find()) stored = Integer.parseInt(m.group(1));
        check("the harvest went into your chest (" + stored + " stored)", stored > 40);
        check("a stack of seeds was kept to plant (" + count("wheat_seeds") + " wheat seeds, " + count("carrot") + " carrots)", count("wheat_seeds") > 0 && count("carrot") > 0);
        check("still everything replanted (" + countGrowing() + " young crops)", countGrowing() >= 72);
    }

    /** A chest of yours drained by a hopper is your storage: straight in, nothing ever taken out. */
    static void drainedChest() throws Exception {
        System.out.println("--- a chest drained by a hopper");
        cmd("data merge block 39 -60 44 {Items:[{Slot:0b,id:\"minecraft:shulker_box\",count:1}]}"); // an empty box in it: must stay there
        cmd("setblock 39 -61 44 hopper[facing=down]");
        cmd("setblock 39 -62 44 chest");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " wheat[age=7]");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z0 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        cmd("kill @e[type=item]");
        packInventory(2);
        toFarm();
        String before = Hub.chatText();
        ModEngine.press(mod, "start");
        runFor(35000);
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
        String chat = Hub.chatText().substring(before.length());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) stored").matcher(chat);
        int stored = 0;
        while (m.find()) stored = Integer.parseInt(m.group(1));
        check("seen as drained storage (" + (chat.contains("drained") ? "said so" : "not said") + ")", chat.contains("drained"));
        check("the harvest went down the drain (" + stored + " stored)", stored > 40);
        int boxes = 0;
        for (Inv.Item it : Inv.inventory) if (it != null && it.key.endsWith("shulker_box")) boxes++;
        check("nothing taken out of it (the box in it stayed there)", boxes == 0);
        cmd("setblock 39 -61 44 stone_bricks");
        cmd("setblock 39 -62 44 dirt");
        cmd("data merge block 39 -60 44 {Items:[]}");
    }

    /** No chest: empty shulker boxes on you are filled (the loader, as auto is with no drained chest). */
    static void boxes() throws Exception {
        System.out.println("--- shulker boxes, no chest");
        cmd("setblock 39 -60 44 stone_bricks");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " wheat[age=7]");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z0 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        cmd("kill @e[type=item]");
        packInventory(3);
        cmd("clear HarvestBot minecraft:stone 128");
        cmd("give HarvestBot minecraft:shulker_box 2");
        Thread.sleep(1200);
        toFarm();
        ModEngine.press(mod, "start");
        runFor(60000);
        String st = statusText();
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
        int boxes = 0, filled = 0;
        for (Inv.Item it : Inv.inventory) {
            if (it == null || !it.key.endsWith("shulker_box")) continue;
            boxes += it.count;
            if (it.contents != null && !it.contents.isEmpty()) filled++;
        }
        check("both boxes are back on you (" + boxes + ")", boxes == 2);
        check("boxes were filled with the harvest (" + filled + " with harvest in; " + st + ")", filled >= 1);
        boolean onecrop = true;
        for (Inv.Item it : Inv.inventory) {
            if (it == null || it.contents == null || it.contents.isEmpty()) continue;
            String first = it.contents.get(0).key;
            for (Inv.Item x : it.contents) onecrop &= x.key.equals(first);
        }
        check("one crop per box", onecrop);
        check("no box left on the wall", !Hub.chatText().contains("still on the wall"));
    }

    /** Into the farm (and make sure it got there). */
    static void toFarm() throws Exception {
        for (int t = 0; t < 5; t++) {
            cmd("tp HarvestBot 44.5 -60 42.5 0 0");
            Thread.sleep(2000);
            if (Math.abs(World.selfX - 44.5) < 1 && Math.abs(World.selfZ - 42.5) < 1) return;
        }
        throw new IllegalStateException("couldn't get into the farm: at " + World.selfX + " " + World.selfY + " " + World.selfZ);
    }

    /** A bare farm: no crops, no seeds on you, carrots on the ground; grass by the water at the edge gets tilled. */
    static void bareFarm() throws Exception {
        System.out.println("--- a bare farm");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " air");
        cmd("fill " + X0 + " -61 " + Z0 + " " + X1 + " -61 " + (Z0 + 1) + " grass_block"); // two rows of grass, the water reaches them
        cmd("kill @e[type=item]");
        cmd("clear HarvestBot");
        cmd("give HarvestBot minecraft:diamond_hoe[minecraft:enchantments={fortune:3}]");
        cmd("summon item 42.5 -59.5 46.5 {Item:{id:\"minecraft:carrot\",count:64}}");
        cmd("summon item 46.5 -59.5 46.5 {Item:{id:\"minecraft:carrot\",count:32}}");
        Thread.sleep(1000);
        toFarm();
        ModEngine.press(mod, "start");
        runFor(45000);
        int planted = 0, farmland = 0;
        for (int x = X0; x <= X1; x++)
            for (int z = Z0; z <= Z1; z++) {
                if (Shapes.id(MapData.stateAt(x, Y, z)).equals("carrots")) planted++;
                if (Shapes.id(MapData.stateAt(x, Y - 1, z)).equals("farmland")) farmland++;
            }
        check("the grass by the water was tilled (" + farmland + " of 80 farmland)", farmland == 80);
        check("every plot planted with the carrots off the ground (" + planted + " of 80)", planted == 80);
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
    }

    /** As the Fabric mod's farm test: a farm at every age, ten times the growth for two minutes: it keeps up. */
    static void keepUp() throws Exception {
        System.out.println("--- keeping up at 10x growth");
        for (int z = Z0; z <= Z1; z++) cmd("fill " + X0 + " -60 " + z + " " + X1 + " -60 " + z + " wheat[age=" + (z % 8) + "]");
        cmd("setblock 44 -60 44 air");
        cmd("gamerule random_tick_speed 30");
        toFarm();
        String before = Hub.chatText();
        ModEngine.press(mod, "start");
        Thread.sleep(120000);
        String st = statusText();
        int ripeNow = countRipe(), growing = countGrowing();
        cmd("gamerule random_tick_speed 3");
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(\\d+) harvested").matcher(st);
        int got = hm.find() ? Integer.parseInt(hm.group(1)) : 0;
        java.util.regex.Matcher wm = java.util.regex.Pattern.compile("([\\d.]+) s wait").matcher(st);
        double wait = wm.find() ? Double.parseDouble(wm.group(1)) : 99;
        check(String.format("kept every plot growing at 10x growth (%d harvested, %.1f s average wait, %d growing, %d ripe now)", got, wait, growing, ripeNow),
                got >= 20 && wait <= 15 && growing + ripeNow >= 79 && ripeNow <= 4);
    }

    /** No Fortune tool: by hand, holding the seeds. Hungry: it eats. */
    static void byHandAndEating() throws Exception {
        System.out.println("--- by hand, and eating");
        cmd("clear HarvestBot");
        cmd("give HarvestBot minecraft:carrot 32");
        cmd("give HarvestBot minecraft:bread 8");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        cmd("difficulty easy"); // hunger only drops off peaceful (mob spawning stays off)
        cmd("effect give HarvestBot minecraft:hunger 8 250");
        Thread.sleep(8500);
        int foodBefore = GameState.food;
        toFarm();
        ModEngine.press(mod, "start");
        runFor(25000);
        String st = statusText();
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
        check("harvested by hand (" + st + ")", st.contains("harvested") && countRipe() <= 4);
        cmd("difficulty peaceful");
        check("ate when hungry (food " + foodBefore + " -> " + GameState.food + ", bread " + count("bread") + " of 8)", GameState.food > foodBefore && count("bread") < 8);
    }

    /** /hh typed in the app goes to the add-on, never to the server; settings change from there too. */
    static void commands() throws Exception {
        System.out.println("--- /hh commands");
        String before = Hub.chatText();
        Hub.bot.sendChat("/hh boxes spare");
        Thread.sleep(800);
        check("/hh boxes spare: the setting changed (" + mod.config.optString("boxes") + ")", "spare".equals(mod.config.optString("boxes")));
        Hub.bot.sendChat("/hh perTick 3");
        Thread.sleep(800);
        check("/hh perTick 3", mod.config.optInt("perTick") == 3);
        Hub.bot.sendChat("/hh boxes auto");
        Hub.bot.sendChat("/hh perTick 2");
        Thread.sleep(800);
        String log = new String(java.nio.file.Files.readAllBytes(new File("desktop/realsrv/logs/latest.log").toPath()), StandardCharsets.UTF_8);
        check("the server never saw /hh", !log.contains("/hh"));
        ModEngine.setConfig(mod, "minHealth", 6);
        Thread.sleep(500);
        check("a setting changed in the app reaches the add-on", mod.config.optInt("minHealth") == 6);
        ModEngine.setConfig(mod, "minHealth", 8);
        Thread.sleep(300);
    }

    /** Kicked while it works: the app reconnects, and it carries on with the same farm. */
    static void kicked(Bot b, Bot.Config c) throws Exception {
        System.out.println("--- kicked");
        c.autoReconnect = true;
        cmd("give HarvestBot minecraft:carrot 32");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        toFarm();
        ModEngine.press(mod, "start");
        Thread.sleep(3000);
        cmd("kick HarvestBot Testing the reconnect");
        long until = System.currentTimeMillis() + 60000;
        boolean back = false;
        while (System.currentTimeMillis() < until && !back) {
            Thread.sleep(1000);
            back = Hub.chatText().contains("Back after the disconnect");
        }
        check("kicked: reconnected and carrying on with the farm", back);
        Thread.sleep(8000);
        check("harvesting again after the reconnect (" + statusLine() + ")", statusText().contains("harvested") && !statusText().contains("Stand in"));
        c.autoReconnect = false;
        ModEngine.press(mod, "stop");
        Thread.sleep(800);
    }

    static void buildFarm() throws Exception {
        cmd("fill 37 -61 37 51 -55 51 air");
        cmd("fill 37 -62 37 51 -62 51 dirt");
        cmd("fill 37 -61 37 51 -61 51 grass_block");
        cmd("fill 39 -60 39 49 -59 39 stone_bricks");
        cmd("fill 39 -60 49 49 -59 49 stone_bricks");
        cmd("fill 39 -60 39 39 -59 49 stone_bricks");
        cmd("fill 49 -60 39 49 -59 49 stone_bricks");
        cmd("fill " + X0 + " -61 " + Z0 + " " + X1 + " -61 " + Z1 + " farmland[moisture=7]");
        cmd("setblock 44 -61 44 water");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z1 + " wheat[age=7]");
        cmd("fill " + X0 + " -60 " + Z0 + " " + X1 + " -60 " + Z0 + " carrots[age=7]");
        cmd("setblock 44 -60 44 air");
        cmd("kill @e[type=item]");
        Thread.sleep(1500);
    }

    static int countRipe() {
        int n = 0;
        for (int x = X0; x <= X1; x++)
            for (int z = Z0; z <= Z1; z++) {
                int st = MapData.stateAt(x, Y, z);
                String id = Shapes.id(st);
                if ((id.equals("wheat") || id.equals("carrots")) && "7".equals(Shapes.prop(st, "age"))) n++;
            }
        return n;
    }

    static int countGrowing() {
        int n = 0;
        for (int x = X0; x <= X1; x++)
            for (int z = Z0; z <= Z1; z++) {
                int st = MapData.stateAt(x, Y, z);
                String id = Shapes.id(st);
                if ((id.equals("wheat") || id.equals("carrots")) && !"7".equals(Shapes.prop(st, "age"))) n++;
            }
        return n;
    }

    static int count(String id) {
        int n = 0;
        for (Inv.Item it : Inv.inventory) if (it != null && it.key.equals(id)) n += it.count;
        return n;
    }

    static String statusText() {
        org.json.JSONArray s = mod.status;
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) sb.append(i > 0 ? " | " : "").append(s.optJSONObject(i).optString("text"));
        return sb.toString();
    }

    static String statusLine() {
        String t = statusText();
        return t.length() > 150 ? t.substring(0, 150) : t;
    }

    static boolean logHas(String s) {
        return mod.logText().contains(s) || Hub.chatText().contains(s);
    }

    static final class TestHost implements ModEngine.Host {
        public String name() {
            return "HarvestBot";
        }

        public String server() {
            return "127.0.0.1:25601";
        }

        public boolean online() {
            return World.inWorld;
        }

        public double[] pos() {
            return new double[]{World.selfX, World.selfY, World.selfZ, World.selfYaw};
        }

        public float health() {
            return Hub.statHealth;
        }

        public List<World.Dot> entities() {
            return World.snapshot(null);
        }

        public void chat(String text) {
            Bot b = Hub.bot;
            if (b != null) b.sendChat(text);
        }

        public void jump() {
        }

        public void disconnect(String reason) {
        }

        public void log(String modName, String msg) {
            System.out.println("[" + modName + "] " + msg);
        }

        public void alert(String title, String text) {
            System.out.println("[ALERT] " + title + ": " + text);
        }

        public void saveStorage(String modId, String json) {
        }
    }
}
