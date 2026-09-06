package app.morphe.extension.reddit.patches;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Adds an "NSFW" row to the navigation drawer, directly under Reddit's own Popular row,
 * and toggles NSFW mode when it is tapped.
 *
 * <p>The row is built by cloning the Popular row rather than by constructing one from a known
 * class: the drawer row type is obfuscated and renamed every release, but its shape
 * ({@code (boolean, int titleRes, int iconRes, long uniqueId)}) is stable, and the clone
 * inherits whatever the real type happens to be. Everything here is reflective and wrapped, so
 * a shape change costs the row, not the app.
 *
 * <p>The Popular row is identified by its title resource id, looked up by name at runtime, which
 * also resolves which of the two int fields is the title and which is the icon: only the title
 * can equal a string resource id.
 *
 * <p>Note on why this toggles a filter instead of opening a mature feed: Reddit's
 * {@code FeedType} and {@code ListingType} both carry a MATURE entry, but the app ships no
 * mature feed screen (every other feed has one - HomeFeedScreen, PopularFeedScreen,
 * LatestFeedScreen, AllFeedScreen). MATURE survives only in exhaustive Kotlin branch tables,
 * so there is nothing to navigate to.
 */
@SuppressWarnings("unused")
public final class NsfwDrawerRow {

    /** Reddit's own string resource naming its Popular drawer row. */
    private static final String POPULAR_LABEL_KEY = "popular_feed_label";

    /** The string resource Morphe adds for this row. */
    private static final String NSFW_LABEL_KEY = "morphe_nsfw_feed_mode_row_title";

    /**
     * Stable adapter id for the added row. Deliberately far from Reddit's own ids, which count
     * down from zero, so it cannot collide with one.
     */
    private static final long ROW_UNIQUE_ID = Long.MIN_VALUE + 0x4E53_5730L;

    private static final int UNRESOLVED = -1;

    private static volatile int popularTitleId = UNRESOLVED;
    private static volatile int nsfwTitleId = UNRESOLVED;

    /** Whether the diagnostic toast explaining the fallback has been shown this app start. */
    private static volatile boolean explainedFallback;

    private NsfwDrawerRow() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Appends the NSFW row to the drawer section that holds the Popular row.
     *
     * @param items One drawer section's items.
     * @return The same items, with the NSFW row appended if this section was the Popular one.
     */
    public static Collection<?> addNsfwRow(Collection<?> items) {
        try {
            if (items == null || items.isEmpty()) {
                return items;
            }

            int popularId = popularTitleId();
            int nsfwId = nsfwTitleId();
            if (popularId == 0 || nsfwId == 0) {
                return items;
            }

            for (Object item : items) {
                Object row = cloneAsNsfwRow(item, popularId, nsfwId);
                if (row == null) {
                    continue;
                }

                List<Object> augmented = new ArrayList<>(items.size() + 1);
                augmented.addAll(items);
                augmented.add(row);

                Logger.printDebug(() -> "NSFW mode: added drawer row after Popular");
                return augmented;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addNsfwRow failure", ex);
        }

        return items;
    }

    /**
     * Injection point. Handles a tap on a drawer row.
     *
     * @param row The row that was tapped.
     * @return Whether this was the NSFW row, and the tap has been handled.
     */
    public static boolean onDrawerRowClicked(Object row) {
        try {
            int nsfwId = nsfwTitleId();
            if (row == null || nsfwId == 0 || !hasIntField(row, nsfwId)) {
                return false;
            }

            boolean enabled = !Settings.NSFW_FEED_MODE.get();
            Settings.NSFW_FEED_MODE.save(enabled);

            if (enabled) {
                Utils.showToastShort("NSFW mode ON - refresh the feed");
                if (!explainedFallback) {
                    explainedFallback = true;
                    Utils.showToastLong("Filtering the feed to 18+ posts. "
                            + "This app build has no mature feed to open");
                }
            } else {
                Utils.showToastShort("NSFW mode OFF - refresh the feed");
            }

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onDrawerRowClicked failure", ex);
            return false;
        }
    }

    /**
     * Builds an NSFW row from Reddit's Popular row, or null if this item is not that row.
     */
    private static Object cloneAsNsfwRow(Object item, int popularId, int nsfwId) {
        if (item == null) {
            return null;
        }

        Class<?> type = item.getClass();

        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(
                    boolean.class, int.class, int.class, long.class);
        } catch (NoSuchMethodException ex) {
            // Not a simple drawer row.
            return null;
        }

        int titleId = UNRESOLVED;
        int iconId = UNRESOLVED;
        boolean flag = false;

        try {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);

                Class<?> fieldType = field.getType();
                if (fieldType == int.class) {
                    int value = field.getInt(item);
                    // Only the title can be a string resource id, so this also tells the
                    // two int fields apart without relying on their declaration order.
                    if (value == popularId) {
                        titleId = value;
                    } else {
                        iconId = value;
                    }
                } else if (fieldType == boolean.class) {
                    flag = field.getBoolean(item);
                }
            }

            if (titleId != popularId || iconId == UNRESOLVED) {
                return null;
            }

            constructor.setAccessible(true);
            return constructor.newInstance(flag, nsfwId, iconId, ROW_UNIQUE_ID);
        } catch (Exception ex) {
            Logger.printException(() -> "cloneAsNsfwRow failure", ex);
            return null;
        }
    }

    private static boolean hasIntField(Object row, int value) {
        try {
            for (Field field : row.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                    continue;
                }
                field.setAccessible(true);
                if (field.getInt(row) == value) {
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hasIntField failure", ex);
        }
        return false;
    }

    private static int popularTitleId() {
        int id = popularTitleId;
        if (id == UNRESOLVED) {
            id = ResourceUtils.getStringIdentifier(POPULAR_LABEL_KEY);
            popularTitleId = id;
            if (id == 0) {
                Logger.printInfo(() -> "NSFW mode: no '" + POPULAR_LABEL_KEY
                        + "' string, cannot place the drawer row");
            }
        }
        return id;
    }

    private static int nsfwTitleId() {
        int id = nsfwTitleId;
        if (id == UNRESOLVED) {
            id = ResourceUtils.getStringIdentifier(NSFW_LABEL_KEY);
            nsfwTitleId = id;
        }
        return id;
    }
}
