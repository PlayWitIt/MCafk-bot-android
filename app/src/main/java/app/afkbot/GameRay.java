package app.afkbot;

/**
 * What the Play screen's crosshair points at: walks the blocks along the view ray (voxel traversal) and tests each
 * block's real outline boxes, so torches, rails, carpets, flowers and slabs can be targeted exactly like in the game.
 * Faces use the protocol's order (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), which Bot.useBlock expects.
 * Pure Java (no Android).
 */
final class GameRay {
    private GameRay() {
    }

    /** {x, y, z, face, distance*1000} of the first targetable block within max, or null. */
    static int[] cast(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tx = (dx > 0 ? (x + 1 - ox) : (ox - x)) * tdx, ty = (dy > 0 ? (y + 1 - oy) : (oy - y)) * tdy, tz = (dz > 0 ? (z + 1 - oz) : (oz - z)) * tdz;
        double t = 0;
        for (int steps = 0; t <= max && steps < 256; steps++) {
            int st = MapData.stateAt(x, y, z);
            if (st > 0 && targetable(st)) {
                double best = Double.MAX_VALUE;
                int bestFace = -1;
                for (float[] b : Shapes.outline(st)) {
                    double[] hit = box(ox, oy, oz, dx, dy, dz, (double) x + b[0], (double) y + b[1], (double) z + b[2],
                            (double) x + b[3], (double) y + b[4], (double) z + b[5]);
                    if (hit != null && hit[0] < best) {
                        best = hit[0];
                        bestFace = (int) hit[1];
                    }
                }
                if (bestFace >= 0 && best <= max) return new int[]{x, y, z, bestFace, (int) (best * 1000)};
            }
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += tdx;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += tdy;
            } else {
                z += sz;
                t = tz;
                tz += tdz;
            }
        }
        return null;
    }

    private static boolean targetable(int st) {
        String id = Shapes.id(st);
        return !(id.isEmpty() || id.equals("air") || id.equals("cave_air") || id.equals("void_air") || id.equals("water")
                || id.equals("lava") || id.equals("bubble_column") || id.equals("light") || id.equals("structure_void"));
    }

    /** Ray vs box: {distance, face} of the entry point, or null. Starting inside the box hits it at distance 0. */
    static double[] box(double ox, double oy, double oz, double dx, double dy, double dz,
                        double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = -1e9, tmax = 1e9;
        int face = 1;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        int[][] faces = {{4, 5}, {0, 1}, {2, 3}}; // entering while moving +x hits the west face, etc.
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            int f = d[a] > 0 ? faces[a][0] : faces[a][1];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tmin) {
                tmin = t1;
                face = f;
            }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        if (tmax < 0) return null;
        return new double[]{Math.max(0, tmin), face};
    }
}
