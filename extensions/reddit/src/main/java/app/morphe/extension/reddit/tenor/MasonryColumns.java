/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

/**
 * Tracks column heights for the staggered grid the picker lays GIFs out in.
 *
 * <p>GIFs have wildly different aspect ratios, so a fixed grid either crops them
 * or leaves large gaps. Placing each new GIF into whichever column is currently
 * shortest keeps the columns close to level as the user scrolls.
 *
 * <p>Placement is incremental rather than computed over the whole result set:
 * pages arrive one at a time and already placed items must never move, since
 * reflowing the grid under the user's thumb is how mis-taps happen.
 *
 * <p>Pure by design - no Android imports - so the layout maths is testable with
 * a plain JDK.
 */
public final class MasonryColumns {

    private final int[] heights;

    /**
     * @param columnCount Number of columns, at least one.
     */
    public MasonryColumns(int columnCount) {
        if (columnCount < 1) {
            throw new IllegalArgumentException("columnCount: " + columnCount);
        }
        heights = new int[columnCount];
    }

    public int columnCount() {
        return heights.length;
    }

    /** Current accumulated height of a column. */
    public int height(int column) {
        return heights[column];
    }

    /**
     * Index of the shortest column, preferring the leftmost when tied.
     *
     * <p>The tie break matters for the first row: without it an empty grid would
     * place every item in whichever column the scan happened to reach last, and
     * the first row would come out ragged.
     */
    public int shortestColumn() {
        int shortest = 0;
        for (int i = 1; i < heights.length; i++) {
            if (heights[i] < heights[shortest]) {
                shortest = i;
            }
        }
        return shortest;
    }

    /**
     * Assigns an item to the shortest column and accounts for its height.
     *
     * @param itemHeight Height the item will occupy, including any spacing below it.
     * @return Index of the column the item belongs in.
     */
    public int place(int itemHeight) {
        int column = shortestColumn();
        heights[column] += Math.max(0, itemHeight);
        return column;
    }

    /** Clears all column heights, for when the grid is emptied to show new results. */
    public void reset() {
        java.util.Arrays.fill(heights, 0);
    }
}
