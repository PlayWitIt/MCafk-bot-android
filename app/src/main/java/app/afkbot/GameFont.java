package app.afkbot;

/**
 * The game's own pixel font (textures/font/ascii.png: 16x16 cells of 8x8 pixels, laid out by character code for
 * ASCII). Character widths are measured from the glyph pixels like the game does: last opaque column + 1, plus one
 * pixel of spacing; a space is 4 wide. Platform neutral (the HUD draws the glyphs).
 */
final class GameFont {
    final int[] advance = new int[256];
    final int cell; // pixels per glyph cell in the source image (8 for the default 128x128 image)

    GameFont(int[] argb, int size) {
        cell = size / 16;
        for (int c = 0; c < 256; c++) {
            int cx = (c % 16) * cell, cy = (c / 16) * cell, last = -1;
            for (int x = cell - 1; x >= 0 && last < 0; x--)
                for (int y = 0; y < cell; y++)
                    if ((argb[(cy + y) * size + cx + x] >>> 24) != 0) {
                        last = x;
                        break;
                    }
            advance[c] = c == ' ' ? cell / 2 : last < 0 ? 0 : (last + 1) * 8 / cell + 1;
        }
    }

    /** Width of a string in font pixels (8 = one glyph height). */
    int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += advance[glyph(s.charAt(i))];
        return Math.max(0, w - 1);
    }

    /** The glyph used for a character: ASCII as is, anything else as '?'. */
    static int glyph(char ch) {
        return ch >= 32 && ch < 127 ? ch : '?';
    }

    /** Plain text: removes the game's colour codes (section sign + code). */
    static String strip(String s) {
        if (s.indexOf('§') < 0) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') i++;
            else b.append(c);
        }
        return b.toString();
    }
}
