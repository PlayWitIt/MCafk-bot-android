package app.afkbot;
public class RealTest {
  static void say(String s){ System.out.println("[REAL] "+s); }
  static void cmd(String c) throws Exception { java.io.FileWriter w=new java.io.FileWriter(System.getProperty("afk.cmd", "realsrv/cmd")); w.write(c+"\n"); w.close(); Thread.sleep(150); }
  static void walk(Bot b, float yaw, long ms, boolean jump) throws Exception { b.manual=true; b.lookYaw=yaw; b.lookPitch=0; b.inJump=jump; b.inForward=1; Thread.sleep(ms); b.inForward=0; b.inJump=false; Thread.sleep(600); }
  public static void main(String[] a) throws Exception {
    Hub.versionLoader = new Hub.VersionLoader(){ public ProtoDef.VersionData load(int p) throws Exception { return ProtoDef.load(new java.io.FileInputStream(System.getProperty("afk.proto", "app/src/main/assets/proto/")+p+".json")); } };
    Bot.Config c=new Bot.Config(); c.address="127.0.0.1:25601"; c.offline=true; c.offlineName="Tester"; c.antiAfk=false; c.autoReconnect=false; c.autoLog=false; c.visualRange=false;
    Bot b=new Bot(c,null); b.start();
    for (int i=0;i<100 && !(World.inWorld && World.selfY!=0);i++) Thread.sleep(100);
    Thread.sleep(2500);
    say(String.format("spawned at %.2f %.2f %.2f, chunks=%d", World.selfX, World.selfY, World.selfZ, MapData.chunkCount()));
    String script = a.length>0 ? a[0] : "walk";
    int c0 = Bot.corrections;
    if (script.equals("walk")) {
      cmd("fill 0 -60 0 60 -50 20 minecraft:air"); cmd("fill 0 -61 0 60 -61 20 minecraft:grass_block"); cmd("tp Tester 8.5 -60 10.5 -90 0"); Thread.sleep(2500); c0=Bot.corrections;
      b.manual=true; b.lookYaw=-90; b.lookPitch=0;
      double x0=World.selfX;
      b.inForward=1; Thread.sleep(3000); b.inForward=0; Thread.sleep(800);
      say(String.format("walked east: dx=%.2f in 3s (vanilla ~12.9), corrections=%d", World.selfX-x0, Bot.corrections-c0));
      c0=Bot.corrections; x0=World.selfX;
      b.inSprint=true; b.inForward=1; Thread.sleep(2000); b.inForward=0; b.inSprint=false; Thread.sleep(800);
      say(String.format("sprinted: dx=%.2f in 2s (vanilla ~11.2), corrections=%d", World.selfX-x0, Bot.corrections-c0));
      c0=Bot.corrections;
      b.inJump=true; b.inForward=1; Thread.sleep(2000); b.inJump=false; b.inForward=0; Thread.sleep(800);
      say(String.format("jump-walked, corrections=%d", Bot.corrections-c0));
      c0=Bot.corrections; double z0=World.selfZ;
      b.lookYaw=0; b.inStrafe=1; Thread.sleep(1500); b.inStrafe=0; Thread.sleep(500);
      say(String.format("strafed: dz=%.2f dx-ish, corrections=%d", World.selfZ-z0, Bot.corrections-c0));
    }
    if (script.equals("course")) {
      cmd("tp Tester 8.5 -60 10.5 -90 0"); Thread.sleep(1200);
      cmd("setblock 11 -60 10 minecraft:chest[facing=west]");
      cmd("item replace block 11 -60 10 container.0 with minecraft:diamond 5");
      cmd("item replace block 11 -60 10 container.3 with minecraft:oak_log 32");
      cmd("setblock 16 -60 10 minecraft:oak_slab[type=bottom]");
      cmd("setblock 19 -60 10 minecraft:stone_stairs[facing=east]");
      cmd("fill 23 -60 9 23 -60 11 minecraft:oak_fence");
      cmd("setblock 23 -60 10 minecraft:oak_fence_gate[facing=east,open=true]");
      cmd("fill 26 -61 9 28 -61 11 minecraft:water");
      cmd("setblock 31 -60 10 minecraft:white_carpet");
      cmd("setblock 33 -60 10 minecraft:oak_door[facing=east,half=lower,open=true]");
      cmd("setblock 33 -59 10 minecraft:oak_door[facing=east,half=upper,open=true]");
      Thread.sleep(1500);
      say(String.format("at %.2f %.2f %.2f; chest state name=%s", World.selfX, World.selfY, World.selfZ, Hub.versionData.blockName(MapData.stateAt(11,-60,10))));
      // open chest, take, close
      b.useBlock(11,-60,10,Bot.faceToward(new int[]{11,-60,10})); Thread.sleep(1200);
      say("chest open="+Inv.openId+" title="+Inv.title+" containerSize="+Inv.containerSize());
      for (int i=0;i<Inv.containerSize();i++) if (Inv.slots.get(i)!=null) say("  slot "+i+": "+Inv.slots.get(i).name+" x"+Inv.slots.get(i).count);
      b.clickSlot(0,0,1); Thread.sleep(500);
      int inv=0; for (Inv.Item it: Inv.inventory) if (it!=null) inv++;
      say("after take: inventory stacks="+inv);
      b.closeContainer(); Thread.sleep(500);
      int c1=Bot.corrections;
      // walk around the chest: north a bit then east along z=9.5? chest at x=11 blocks the line, step to z=11.5 first
      walk(b, 0, 300, false);   // south a bit
      double x0=World.selfX;
      walk(b, -90, 9000, false);
      say(String.format("after chest walked east through slab/stairs/gate/water/carpet/door: x %.2f -> %.2f, y=%.2f z=%.2f corrections=%d", x0, World.selfX, World.selfY, World.selfZ, Bot.corrections-c1));
    }
    if (script.equals("physics")) {
      cmd("tp Tester 8.5 -60 10.5 -90 0"); Thread.sleep(1200);
      cmd("fill 9 -60 8 40 -54 12 minecraft:air"); cmd("fill 9 -61 9 40 -61 12 minecraft:grass_block");
      cmd("fill 10 -61 10 13 -61 10 minecraft:ice");
      cmd("fill 15 -61 10 17 -61 10 minecraft:soul_sand");
      cmd("setblock 19 -61 10 minecraft:honey_block");
      cmd("setblock 22 -60 10 minecraft:cobweb");
      cmd("fill 30 -60 9 30 -55 11 minecraft:stone");
      cmd("fill 29 -60 10 29 -56 10 minecraft:ladder[facing=west]");
      Thread.sleep(1500);
      int c1=Bot.corrections; double x0=World.selfX;
      walk(b, -90, 9000, false);
      say(String.format("ice/soul sand/honey/cobweb: x %.2f -> %.2f y=%.2f corrections=%d", x0, World.selfX, World.selfY, Bot.corrections-c1));
      c1=Bot.corrections; double y0=World.selfY;
      walk(b, -90, 3500, false);
      say(String.format("ladder climb: y %.2f -> %.2f x=%.2f corrections=%d", y0, World.selfY, World.selfX, Bot.corrections-c1));
    }
    if (script.equals("diag")) {
      cmd("tp Tester 8.5 -60 10.5 -90 0"); cmd("fill 9 -60 8 40 -54 12 minecraft:air"); cmd("fill 0 -61 0 40 -61 20 minecraft:grass_block"); cmd("setblock 12 -60 10 minecraft:stone"); cmd("setblock 12 -59 10 minecraft:stone"); Thread.sleep(1500);
      say(b.testWalk()); Thread.sleep(3900);
      walk(b, -90, 4500, false);
    }
    if (script.equals("copper")) {
      cmd("fill 0 -61 0 40 -61 20 minecraft:grass_block"); cmd("fill 0 -60 0 40 -54 20 minecraft:air");
      cmd("fill 8 -61 10 30 -61 10 minecraft:cobblestone_slab[type=top]");
      cmd("fill 8 -60 10 20 -60 10 minecraft:exposed_copper_chest[facing=north]");
      cmd("fill 21 -60 10 23 -60 10 minecraft:waxed_oxidized_copper_chest");
      cmd("setblock 24 -60 10 minecraft:chest"); cmd("setblock 25 -60 10 minecraft:ender_chest");
      Thread.sleep(1500);
      cmd("tp Tester 8.5 -59.125 10.5 -90 0"); Thread.sleep(2500);
      int c1=Bot.corrections; double x0=World.selfX;
      say(b.testWalk()); Thread.sleep(3500);
      say(String.format("after test walk corrections=%d", Bot.corrections-c1));
      c1=Bot.corrections; x0=World.selfX;
      walk(b, -90, 5000, false);
      say(String.format("chest row: x %.2f -> %.2f y=%.3f corrections=%d", x0, World.selfX, World.selfY, Bot.corrections-c1));
      cmd("fill 30 -60 9 36 -60 11 minecraft:air");
      cmd("setblock 31 -60 10 minecraft:anvil[facing=north]"); cmd("setblock 33 -60 10 minecraft:hopper"); cmd("setblock 35 -60 10 minecraft:cauldron");
      cmd("setblock 37 -60 10 minecraft:lectern"); cmd("setblock 39 -60 10 minecraft:composter");
      Thread.sleep(1200);
      for (int xx=31; xx<=39; xx+=2) { cmd("tp Tester "+xx+".5 -58 10.5 -90 0"); Thread.sleep(3500); say(String.format("landed x=%d at %.2f y=%.4f", xx, World.selfX, World.selfY)); }
    }
    if (script.equals("shulker")) {
      cmd("fill 0 -60 0 40 -54 20 minecraft:air"); cmd("tp Tester 8.5 -60 10.5 -90 0"); Thread.sleep(1500);
      cmd("clear Tester");
      cmd("give Tester minecraft:diamond_sword[enchantments={sharpness:5,unbreaking:3},custom_name='\"Blade\"'] 1");
      cmd("give Tester minecraft:potion[potion_contents={potion:\"minecraft:strong_healing\"}] 1");
      cmd("give Tester minecraft:firework_rocket[fireworks={flight_duration:2,explosions:[{shape:\"star\",colors:[I;16711680]}]}] 16");
      cmd("give Tester minecraft:written_book[written_book_content={title:\"T\",author:\"A\",pages:[\"hello\"]}] 1");
      cmd("give Tester minecraft:red_shulker_box[container=[{slot:0,item:{id:\"minecraft:diamond\",count:3}}]] 1");
      cmd("give Tester minecraft:white_banner[banner_patterns=[{pattern:\"minecraft:stripe_top\",color:\"red\"}]] 1");
      cmd("give Tester minecraft:elytra[damage=20] 1");
      cmd("give Tester minecraft:filled_map[map_id=0] 1");
      cmd("give Tester minecraft:player_head[profile={name:\"Notch\"}] 1");
      cmd("setblock 11 -60 10 minecraft:shulker_box[facing=up]");
      cmd("item replace block 11 -60 10 container.0 with minecraft:diamond 5");
      cmd("item replace block 11 -60 10 container.1 with minecraft:netherite_sword[enchantments={sharpness:5,mending:1}] 1");
      cmd("item replace block 11 -60 10 container.2 with minecraft:enchanted_book[stored_enchantments={mending:1}] 1");
      cmd("item replace block 11 -60 10 container.3 with minecraft:totem_of_undying 1");
      cmd("item replace block 11 -60 10 container.4 with minecraft:golden_apple 64");
      cmd("item replace block 11 -60 10 container.5 with minecraft:blue_shulker_box[container=[{slot:0,item:{id:\"minecraft:diamond\",count:3}}]] 1");
      cmd("item replace block 11 -60 10 container.6 with minecraft:potion[potion_contents={potion:\"minecraft:swiftness\"}] 1");
      cmd("item replace block 11 -60 10 container.7 with minecraft:writable_book[writable_book_content={pages:[\"x\"]}] 1");
      cmd("item replace block 11 -60 10 container.8 with minecraft:diamond_chestplate[trim={material:\"minecraft:gold\",pattern:\"minecraft:coast\"},damage=5] 1");
      Thread.sleep(2000);
      int inv0=0; for (Inv.Item it: Inv.inventory) if (it!=null) inv0++;
      say("inventory stacks="+inv0+" block="+Hub.versionData.blockName(MapData.stateAt(11,-60,10)));
      b.useBlock(11,-60,10,Bot.faceToward(new int[]{11,-60,10})); Thread.sleep(1500);
      say("open="+Inv.openId+" title="+Inv.title+" size="+Inv.containerSize());
      for (int i=0;i<Inv.containerSize();i++) if (Inv.slots.get(i)!=null) say("  slot "+i+": "+Inv.slots.get(i).name+" x"+Inv.slots.get(i).count);
      b.clickSlot(0,0,1); Thread.sleep(700);
      say("after shift-click: slot0="+(Inv.slots.get(0)==null?"empty":Inv.slots.get(0).name)+" stateId="+Inv.stateId);
      b.clickSlot(1,0,0); Thread.sleep(700); b.clickSlot(20,0,0); Thread.sleep(700);
      int inv=0; for (Inv.Item it: Inv.inventory) if (it!=null) inv++;
      say("after take: inventory stacks="+inv+" connected="+World.inWorld);
      b.closeContainer(); Thread.sleep(800);
      int inv2=0; for (Inv.Item it: Inv.inventory) if (it!=null) inv2++;
      say("still connected="+World.inWorld+" inventory stacks="+inv2);
      // open from each side (west/north/south/east/below-ish) to exercise click points
      cmd("setblock 11 -58 10 minecraft:stone");
      for (String tp : new String[]{"9.5 -60 10.5","11.5 -60 8.5","11.5 -60 12.5","13.5 -60 10.5"}) {
        cmd("tp Tester "+tp); Thread.sleep(1500);
        b.useBlock(11,-60,10,Bot.faceToward(new int[]{11,-60,10})); Thread.sleep(1200);
        say("from "+tp+": open="+Inv.openId+" "+Inv.title); b.closeContainer(); Thread.sleep(500);
      }
    }
    say("total corrections="+Bot.corrections);
    b.stop(); Thread.sleep(300); System.exit(0);
  }
}
