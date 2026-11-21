package app.afkbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Walking for mods: finds a path over the blocks that are there (stepping up one, dropping up to five, more than
 * three only if there's no other way, never into lava or fire, no cutting corners) and walks it by holding the
 * bot's movement keys, a tick at a time, through the bot's own physics. A port of Harvest Hand's Walker (without its
 * flying and pillaring, which farming never uses). Platform neutral.
 */
final class ModNav {
    /** What the bot's body is doing, and its keys. Implemented by Bot. */
    interface Body {
        double x();

        double y();

        double z();

        boolean onGround();

        boolean hCollide();

        boolean inWater();

        int food();

        void keys(boolean forward, boolean jump, boolean sprint, boolean sneak);

        void face(float yaw);
    }

    /** Where a path may end. */
    interface Goal {
        boolean test(int x, int y, int z);
    }

    private static final int MAX_NODES = 40_000;

    private List<int[]> path = new ArrayList<int[]>();
    private int at;
    /** Sprint on the longer stretches. */
    volatile boolean sprint;
    /** Sneak onto the last step so it doesn't overshoot. */
    volatile boolean careful = true;
    private boolean moving;
    /** Gave up on a step since the last look (cleared by {@link #takeStuck}). */
    private boolean stuck;
    private double[] heading;
    private double closest;
    private int stuckTicks;
    /** Cells the last plan looked at. */
    int expanded;

    synchronized boolean moving() {
        return moving;
    }

    synchronized boolean arrived() {
        return at >= path.size();
    }

    /** True once if it got stuck since the last call (and gave up on its path). */
    synchronized boolean takeStuck() {
        boolean s = stuck;
        stuck = false;
        return s;
    }

    synchronized int remaining() {
        return Math.max(0, path.size() - at);
    }

    /** Stops walking (lets go of the keys) but keeps the path. */
    synchronized void stop() {
        moving = false;
    }

    /** Stops and forgets the path. */
    synchronized void clear() {
        moving = false;
        path = new ArrayList<int[]>();
        at = 0;
    }

    // ---------------------------------------------------------------- planning (A*)

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static final class Node {
        final int x, y, z;
        final double g;

        Node(int x, int y, int z, double g) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
        }
    }

    /**
     * Finds a path from where the player is to any cell the goal accepts, inside bounds (minX, minY, minZ, maxX,
     * maxY, maxZ). False if there's none.
     */
    boolean plan(double px, double py, double pz, Goal goal, int[] bounds) {
        int[] start = ModWorld.feet(px, py, pz);
        // Swimming in deep water: plan from the surface, where it can climb out onto the bank.
        if (!ModWorld.standable(start[0], start[1], start[2])) {
            int sy = start[1];
            for (int i = 0; i < 12 && ModWorld.fluid(ModWorld.state(start[0], sy, start[2])) != 0; i++) sy++;
            if (sy != start[1] && ModWorld.passable(start[0], sy, start[2])) start = new int[]{start[0], sy, start[2]};
        }
        int expandedNow = 0;
        if (goal.test(start[0], start[1], start[2]) && ModWorld.standable(start[0], start[1], start[2])) {
            // Already there, but off to one side: step to the middle first.
            double off = Math.hypot(start[0] + 0.5 - px, start[2] + 0.5 - pz);
            List<int[]> p = new ArrayList<int[]>();
            if (off >= 0.3) p.add(start);
            synchronized (this) {
                path = p;
                at = 0;
                stuckTicks = 0;
                heading = null;
                expanded = 0;
            }
            return true;
        }
        Map<Long, Double> best = new HashMap<Long, Double>();
        Map<Long, int[]> from = new HashMap<Long, int[]>();
        PriorityQueue<Node> open = new PriorityQueue<Node>(256, new java.util.Comparator<Node>() {
            public int compare(Node a, Node b) {
                return Double.compare(a.g, b.g);
            }
        });
        open.add(new Node(start[0], start[1], start[2], 0));
        best.put(key(start[0], start[1], start[2]), 0.0);
        int[] found = null;
        while (!open.isEmpty() && expandedNow < MAX_NODES) {
            Node n = open.poll();
            Double b = best.get(key(n.x, n.y, n.z));
            if (b != null && n.g > b) continue;
            expandedNow++;
            if (goal.test(n.x, n.y, n.z) && ModWorld.standable(n.x, n.y, n.z)) {
                found = new int[]{n.x, n.y, n.z};
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = 1; dy >= -5; dy--) {
                        int nx = n.x + dx, ny = n.y + dy, nz = n.z + dz;
                        if (nx < bounds[0] || ny < bounds[1] || nz < bounds[2] || nx > bounds[3] || ny > bounds[4] || nz > bounds[5]) continue;
                        if (!canMove(n.x, n.y, n.z, nx, ny, nz, dx, dy, dz)) continue;
                        // Drops over three blocks hurt a little: only when nothing else will do.
                        double cost = (dx != 0 && dz != 0 ? 1.414 : 1) + (dy > 0 ? 0.6 : dy < -3 ? 8 : dy < 0 ? -dy * 0.25 : 0);
                        // Water slows it and currents push it off the path: around it if there's a dry way.
                        int st = ModWorld.state(nx, ny, nz);
                        if (ModWorld.fluid(st) != 0) cost += ModWorld.still(st) ? 3 : 8;
                        double g = n.g + cost;
                        long k = key(nx, ny, nz);
                        Double old = best.get(k);
                        if (old == null || g < old) {
                            best.put(k, g);
                            from.put(k, new int[]{n.x, n.y, n.z});
                            open.add(new Node(nx, ny, nz, g));
                        }
                        break;
                    }
                }
            }
        }
        if (found == null) {
            synchronized (this) {
                path = new ArrayList<int[]>();
                at = 0;
                expanded = expandedNow;
            }
            return false;
        }
        List<int[]> list = new ArrayList<int[]>();
        long sk = key(start[0], start[1], start[2]);
        for (int[] p = found; p != null && key(p[0], p[1], p[2]) != sk; p = from.get(key(p[0], p[1], p[2]))) list.add(0, p);
        synchronized (this) {
            path = list;
            at = 0;
            stuckTicks = 0;
            heading = null;
            expanded = expandedNow;
        }
        return true;
    }

    /** One step, from a cell to a neighbouring one up to one higher or five lower. */
    private static boolean canMove(int ax, int ay, int az, int bx, int by, int bz, int dx, int dy, int dz) {
        if (!ModWorld.standable(bx, by, bz)) return false;
        if (dy > 0 && !ModWorld.passable(ax, ay + 2, az)) return false;
        // A drop: room to fall into the landing cell from our height.
        if (dy < 0) {
            for (int y = by + 2; y <= ay + 1; y++) if (!ModWorld.passable(bx, y, bz)) return false;
        }
        if (dx != 0 && dz != 0) {
            // No cutting corners.
            int[][] sides = {{ax + dx, ay, az}, {ax, ay, az + dz}};
            for (int[] s : sides) {
                int sy = s[1] + Math.max(0, dy);
                if (!ModWorld.passable(s[0], sy, s[2]) || !ModWorld.passable(s[0], sy + 1, s[2])) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the farm walk (flood fill)

    /**
     * Every cell you can walk to from a start point, up to radius blocks out (and 8 up or down): walls and fences stop
     * it, water is crossed (Harvest Hand's farm scan). Each result is {x, y, z, flags}: 1 = you can stand there out of
     * the water. The start is moved down (up to 3) onto ground first.
     */
    static List<int[]> flood(int sx, int sy, int sz, int radius) {
        List<int[]> out = new ArrayList<int[]>();
        int x0 = sx, y0 = sy, z0 = sz;
        for (int i = 0; i < 3 && !ModWorld.standable(x0, y0, z0) && !ModWorld.wet(x0, y0, z0); i++) y0--;
        Set<Long> seen = new HashSet<Long>();
        ArrayDeque<int[]> open = new ArrayDeque<int[]>();
        open.add(new int[]{x0, y0, z0});
        seen.add(key(x0, y0, z0));
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // north, east, south, west (the game's horizontal order)
        while (!open.isEmpty() && seen.size() < 120_000) {
            int[] n = open.poll();
            boolean stand = ModWorld.standable(n[0], n[1], n[2]) && ModWorld.fluid(ModWorld.state(n[0], n[1], n[2])) == 0;
            out.add(new int[]{n[0], n[1], n[2], stand ? 1 : 0});
            for (int[] d : dirs) {
                for (int dy = 1; dy >= -3; dy--) {
                    int nx = n[0] + d[0], ny = n[1] + dy, nz = n[2] + d[1];
                    if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius || Math.abs(ny - sy) > 8) break;
                    if (dy > 0 && !ModWorld.passable(n[0], n[1] + 2, n[2])) continue;
                    if (!ModWorld.standable(nx, ny, nz) && !ModWorld.wet(nx, ny, nz)) continue;
                    // Dropping down: room to fall.
                    boolean clear = true;
                    for (int y = ny + 1; y <= n[1] + 1 && clear; y++) clear = ModWorld.passable(nx, y, nz);
                    if (!clear) continue;
                    if (seen.add(key(nx, ny, nz))) open.add(new int[]{nx, ny, nz});
                    break;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- walking it (every bot tick)

    /**
     * Holds the keys for one tick along the path. Called by the bot's tick before its physics, while a mod has
     * control. Lets go when there's nothing to do.
     */
    synchronized void drive(Body b) {
        if (!moving || at >= path.size()) {
            b.keys(false, false, false, false);
            if (at >= path.size()) moving = false;
            return;
        }
        step(b, 0);
    }

    private void step(Body b, int depth) {
        if (at >= path.size()) {
            moving = false;
            b.keys(false, false, false, false);
            return;
        }
        int[] next = path.get(at);
        double ax = next[0] + 0.5, ay = next[1], az = next[2] + 0.5;
        double dx = ax - b.x(), dz = az - b.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        int[] feet = ModWorld.feet(b.x(), b.y(), b.z());
        boolean last = at == path.size() - 1;
        if (flat < 0.3 && Math.abs(feet[1] - next[1]) <= 1 || !last && feet[0] == next[0] && feet[1] == next[1] && feet[2] == next[2] && flat < 0.45) {
            at++;
            if (at >= path.size() || depth > 8) {
                moving = false;
                b.keys(false, false, false, false);
                return;
            }
            step(b, depth + 1);
            return;
        }
        b.face((float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90);
        boolean wet = b.inWater();
        boolean jump = wet ? next[1] >= feet[1] : next[1] > feet[1] && (b.onGround() || b.hCollide());
        boolean sprinting = sprint && !wet && path.size() - at > 2 && b.food() > 6;
        // Slow down onto a narrow last step so it doesn't overshoot (not when the step is down).
        boolean sneak = careful && last && flat < 1.2 && next[1] >= feet[1];
        b.keys(true, jump, sprinting, sneak);
        checkStuck(b, ax, ay, az);
    }

    /** Stuck: no closer to where this step is headed for three seconds (five in water). */
    private void checkStuck(Body b, double tx, double ty, double tz) {
        double ex = b.x() - tx, ey = b.y() - ty, ez = b.z() - tz;
        double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (heading == null || sq(heading[0] - tx) + sq(heading[1] - ty) + sq(heading[2] - tz) > 0.01) {
            heading = new double[]{tx, ty, tz};
            closest = d;
            stuckTicks = 0;
        } else if (d < closest - 0.05) {
            closest = d;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
        if (stuckTicks > (b.inWater() ? 100 : 60)) {
            heading = null;
            moving = false;
            path = new ArrayList<int[]>();
            at = 0;
            stuck = true;
            b.keys(false, false, false, false);
        }
    }

    /** Starts walking the planned path (the keys go down from the next tick). */
    synchronized void go() {
        if (at < path.size()) {
            moving = true;
            stuck = false;
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
