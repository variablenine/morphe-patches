/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

/**
 * A single Tenor result.
 *
 * <p>Deliberately free of Android imports so the pure logic around it can be
 * exercised with a plain JDK (see {@code TenorSelfTest}).
 *
 * <p>Two renditions are carried: a small one for the picker grid, and the full
 * size one that is actually sent to Reddit once picked. Sending the preview
 * would upload a visibly degraded GIF, so the two must not be conflated.
 */
public final class TenorGif {

    /** Tenor post id. Stable, and what {@code itemUrl} is built from. */
    public final String id;

    /** Human readable description, used as the content description of the grid cell. */
    public final String description;

    /** Small rendition shown in the picker grid. */
    public final String previewUrl;
    public final int previewWidth;
    public final int previewHeight;

    /** Full size rendition uploaded to Reddit when this GIF is picked. */
    public final String fullUrl;
    public final int fullWidth;
    public final int fullHeight;

    /**
     * Size of {@link #fullUrl} in bytes as reported by Tenor, or zero if unknown.
     * Checked before uploading so an oversized GIF fails fast with a clear message
     * rather than part way through the upload.
     */
    public final int fullByteSize;

    /** Canonical tenor.com page for this GIF, used as the link fallback. */
    public final String itemUrl;

    public TenorGif(String id, String description,
                    String previewUrl, int previewWidth, int previewHeight,
                    String fullUrl, int fullWidth, int fullHeight, int fullByteSize,
                    String itemUrl) {
        this.id = id;
        this.description = description;
        this.previewUrl = previewUrl;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.fullUrl = fullUrl;
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.fullByteSize = fullByteSize;
        this.itemUrl = itemUrl;
    }

    /**
     * Height this GIF should occupy when drawn at {@code columnWidth}, preserving aspect ratio.
     *
     * @return Scaled height in the same units as {@code columnWidth}, never less than 1.
     */
    public int scaledHeight(int columnWidth) {
        if (previewWidth <= 0 || previewHeight <= 0) {
            // Tenor omitted the dimensions. Fall back to a square so the cell is
            // still tappable and the column heights stay roughly balanced.
            return columnWidth;
        }

        long scaled = (long) columnWidth * previewHeight / previewWidth;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }

    @Override
    public String toString() {
        return "TenorGif{" + id + " " + previewWidth + "x" + previewHeight + "}";
    }
}
