package fr.traducteur.xgimi;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Cheap geometry measurements on the screenshot, used to place labels. */
final class LabelGeometry {
    private LabelGeometry() {}

    /**
     * Same-background free space around a text box, in bitmap coords.
     * Scans 3 rows (top/middle/bottom) outward, starting just outside the box so
     * glyphs aren't sampled, stopping at the first pixel differing from {@code bg}
     * by more than {@code tol} per channel, the edge, or {@code maxReach}.
     *
     * @return {left, right} with left ≤ box.left, right ≥ box.right
     */
    static int[] freeSpan(Bitmap bmp, Rect box, int bg, int tol, int maxReach) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int top = clamp(box.top, 0, h - 1), bottom = clamp(box.bottom - 1, 0, h - 1);
        int[] ys = {top + (bottom - top) / 6, (top + bottom) / 2, bottom - (bottom - top) / 6};
        int x0 = clamp(box.left, 0, w), x1 = clamp(box.right, 0, w);
        int lo = Math.max(0, x0 - maxReach), hi = Math.min(w, x1 + maxReach);
        int[] row = new int[Math.max(1, hi - lo)];
        int left = lo, right = hi;
        int br = (bg >> 16) & 0xff, bgG = (bg >> 8) & 0xff, bb = bg & 0xff;
        for (int y : ys) {
            bmp.getPixels(row, 0, row.length, lo, y, hi - lo, 1);
            int x = x1;
            while (x < right && same(row[x - lo], br, bgG, bb, tol)) x++;
            right = Math.min(right, x);
            x = x0 - 1;
            while (x >= left && same(row[x - lo], br, bgG, bb, tol)) x--;
            left = Math.max(left, x + 1);
        }
        // Safety margin so labels don't touch neighboring content.
        int margin = Math.max(2, box.height() / 4);
        if (right < hi) right = Math.max(x1, right - margin);
        if (left > lo) left = Math.min(x0, left + margin);
        return new int[]{Math.min(left, x0), Math.max(right, x1)};
    }

    private static boolean same(int c, int r, int g, int b, int tol) {
        return Math.abs(((c >> 16) & 0xff) - r) <= tol
                && Math.abs(((c >> 8) & 0xff) - g) <= tol
                && Math.abs((c & 0xff) - b) <= tol;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
