/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

/**
 * A category tile shown before the user searches for anything.
 *
 * <p>Tenor labels these with a leading hash ("#shrug") while the term that must
 * be searched for is the bare word, so the two are kept separate.
 */
public final class TenorCategory {

    /** Display label, as Tenor formats it. */
    public final String name;

    /** Query to run when the tile is tapped. */
    public final String searchTerm;

    /** Animated preview shown behind the label. */
    public final String imageUrl;

    public TenorCategory(String name, String searchTerm, String imageUrl) {
        this.name = name;
        this.searchTerm = searchTerm;
        this.imageUrl = imageUrl;
    }

    @Override
    public String toString() {
        return "TenorCategory{" + searchTerm + "}";
    }
}
