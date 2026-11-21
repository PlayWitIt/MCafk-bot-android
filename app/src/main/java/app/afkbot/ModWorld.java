package app.afkbot;

/**
 * The world as mods see it: block names and properties, and the walking rules mods and {@link ModNav} share (what a
 * player can stand in, walk through, or must keep out of). Reads the bot's chunk data; an unloaded block counts as
 * solid, like the real client before the chunk arrives. Platform neutral.
 */
final class ModWorld {
    private ModWorld() {
    }

    /** Block state at a position: -1 if that chunk isn't loaded. */
    static int state(int x, int y, int z) {
        return MapData.stateAt(x, y, z);
    }

    /** Block name ("carrots"), "air", or null when that chunk isn't loaded. */
    static String id(int x, int y, int z) {
        int s = state(x, y, z);
        if (s < 0) return null;
        if (s == 0) return "air";
        String id = Shapes.id(s);
        return id.isEmpty() ? "air" : id;
    }

    static String prop(int x, int y, int z, String name) {
        int s = state(x, y, z);
        return s <= 0 ? null : Shapes.prop(s, name);
    }

    // ---------------------------------------------------------------- fluids

    /** The fluid in a cell: 'w' water, 'l' lava, 0 none. Waterlogged blocks, kelp, seagrass and bubble columns hold water. */
    static char fluid(int state) {
        if (state <= 0) return 0;
        String id = Shapes.id(state);
        switch (id) {
            case "water":
            case "bubble_column":
            case "kelp":
            case "kelp_plant":
            case "seagrass":
            case "tall_seagrass":
                return 'w';
            case "lava":
                return 'l';
            default:
                return "true".equals(Shapes.prop(state, "waterlogged")) ? 'w' : 0;
        }
    }

    /** A still fluid (a source, or a waterlogged block): moving water pushes you off course. */
    static boolean still(int state) {
        String id = Shapes.id(state);
        if (id.equals("water") || id.equals("lava")) return "0".equals(Shapes.prop(state, "level"));
        return true;
    }

    static boolean water(int x, int y, int z) {
        return fluid(state(x, y, z)) == 'w';
    }

    // ---------------------------------------------------------------- walking rules (as Harvest Hand's Walker)

    /** Magma only stings: allowed when a mod says so (picking things up off it). */
    static volatile boolean magmaOk;

    static boolean dangerous(int state) {
        if (state <= 0) return false;
        switch (Shapes.id(state)) {
            case "lava":
            case "fire":
            case "soul_fire":
            case "cactus":
            case "sweet_berry_bush":
            case "powder_snow":
            case "cobweb":
            case "campfire":
            case "soul_campfire":
            case "wither_rose":
            case "pointed_dripstone":
                return true;
            case "magma_block":
                return !magmaOk;
            default:
                return false;
        }
    }

    /** Highest point of a block's collision boxes (0 when there are none). */
    static float collisionTop(int state) {
        float top = 0;
        for (float[] b : Shapes.collision(state)) top = Math.max(top, b[4]);
        return top;
    }

    private static boolean noCollision(int state) {
        return state == 0 || Shapes.collision(state).length == 0;
    }

    /** A cell the player's body can be in. */
    static boolean passable(int x, int y, int z) {
        int s = state(x, y, z);
        if (s < 0 || dangerous(s)) return false;
        return noCollision(s);
    }

    /** A cell you stand in rather than on: carpet, snow, slabs... anything up to half a block high. */
    static boolean lowFloor(int x, int y, int z) {
        int s = state(x, y, z);
        if (s <= 0 || dangerous(s) || noCollision(s)) return false;
        return collisionTop(s) <= 0.5f;
    }

    /** Whether the player can stand with their feet in this cell. */
    static boolean standable(int x, int y, int z) {
        boolean low = lowFloor(x, y, z);
        if (!(passable(x, y, z) || low) || !passable(x, y + 1, z)) return false;
        if (low) return true;
        int s = state(x, y - 1, z);
        if (s <= 0 || dangerous(s) || noCollision(s)) return false;
        return collisionTop(s) > 0.5f;
    }

    /** Water you can swim in with air above (a farm's water channel, crossed when finding the farm). */
    static boolean wet(int x, int y, int z) {
        return fluid(state(x, y, z)) != 0 && passable(x, y + 1, z);
    }

    // ---------------------------------------------------------------- what blocks are

    /**
     * Blocks the game lets you place over (grass, ferns, vines...): with no hardness these go in one hit. From the
     * game's replaceable blocks (1.21.11).
     */
    static boolean replaceable(int state) {
        if (state == 0) return true;
        switch (Shapes.id(state)) {
            case "air":
            case "cave_air":
            case "void_air":
            case "water":
            case "lava":
            case "short_grass":
            case "fern":
            case "dead_bush":
            case "bush":
            case "short_dry_grass":
            case "tall_dry_grass":
            case "seagrass":
            case "tall_seagrass":
            case "fire":
            case "soul_fire":
            case "snow":
            case "vine":
            case "glow_lichen":
            case "resin_clump":
            case "light":
            case "tall_grass":
            case "large_fern":
            case "structure_void":
            case "bubble_column":
            case "warped_roots":
            case "nether_sprouts":
            case "crimson_roots":
            case "hanging_roots":
            case "leaf_litter":
                return true;
            default:
                return false;
        }
    }

    /** Hardness (time to break, before tools), -1 for unbreakable. */
    static double hardness(int state) {
        ProtoDef.VersionData vd = Hub.versionData;
        if (vd == null || state <= 0) return 0;
        int bi = vd.blockIndex(state);
        return bi >= 0 && bi < vd.hardness.length ? vd.hardness[bi] : 0;
    }

    /** Blocks with stored contents or data (chests, signs, furnaces...): never used as a wall to put things against. */
    static boolean hasBlockEntity(int state) {
        if (state <= 0) return false;
        String id = Shapes.id(state);
        return id.endsWith("chest") || id.equals("barrel") || id.endsWith("furnace") || id.equals("smoker") || id.equals("hopper")
                || id.equals("dispenser") || id.equals("dropper") || id.endsWith("shulker_box") || id.endsWith("_sign") || id.endsWith("_banner")
                || id.endsWith("_bed") || id.equals("beacon") || id.equals("spawner") || id.equals("trial_spawner") || id.equals("vault")
                || id.equals("lectern") || id.endsWith("campfire") || id.equals("bell") || id.equals("beehive") || id.equals("bee_nest")
                || id.equals("jukebox") || id.equals("brewing_stand") || id.equals("enchanting_table") || id.endsWith("_head") || id.endsWith("_skull")
                || id.equals("conduit") || id.equals("comparator") || id.equals("daylight_detector") || id.endsWith("command_block")
                || id.equals("structure_block") || id.equals("jigsaw") || id.equals("decorated_pot") || id.equals("crafter")
                || id.equals("chiseled_bookshelf") || id.startsWith("sculk_") || id.equals("calibrated_sculk_sensor") || id.equals("creaking_heart")
                || id.equals("end_portal") || id.equals("end_gateway") || id.equals("moving_piston") || id.equals("test_block");
    }

    /**
     * Whether a block's side is a full solid square (something can be put against it). dir: the side, 2 north (-z),
     * 3 south (+z), 4 west (-x), 5 east (+x), 0 down, 1 up.
     */
    static boolean solidSide(int x, int y, int z, int dir) {
        int s = state(x, y, z);
        if (s <= 0) return false;
        if (Shapes.isFull(s)) return true;
        for (float[] b : Shapes.collision(s)) {
            boolean full;
            switch (dir) {
                case 0: full = b[1] <= 0 && b[0] <= 0 && b[3] >= 1 && b[2] <= 0 && b[5] >= 1; break;
                case 1: full = b[4] >= 1 && b[0] <= 0 && b[3] >= 1 && b[2] <= 0 && b[5] >= 1; break;
                case 2: full = b[2] <= 0 && b[0] <= 0 && b[3] >= 1 && b[1] <= 0 && b[4] >= 1; break;
                case 3: full = b[5] >= 1 && b[0] <= 0 && b[3] >= 1 && b[1] <= 0 && b[4] >= 1; break;
                case 4: full = b[0] <= 0 && b[2] <= 0 && b[5] >= 1 && b[1] <= 0 && b[4] >= 1; break;
                default: full = b[3] >= 1 && b[2] <= 0 && b[5] >= 1 && b[1] <= 0 && b[4] >= 1; break;
            }
            if (full) return true;
        }
        return false;
    }

    /**
     * The cell where the player's feet are: the carpet they stand in, or the air above a block (as Harvest Hand's
     * Walker.feet: y + 0.45).
     */
    static int[] feet(double x, double y, double z) {
        return new int[]{(int) Math.floor(x), (int) Math.floor(y + 0.45), (int) Math.floor(z)};
    }
}
