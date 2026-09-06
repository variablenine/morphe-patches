package app.morphe.extension.reddit.patches;

import java.util.List;

import app.morphe.extension.reddit.nsfw.NsfwPostDetector;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * NSFW mode: while it is on, feed listings are reduced to their 18+ posts.
 *
 * <p>The setting is read on every call rather than cached in a static, because this is a mode
 * the user turns on and off, and it must take effect on the next feed load instead of on the
 * next app start.
 *
 * <p>Two limits are worth knowing about, and neither can be fixed on this side of the wire:
 * <ul>
 *   <li>This only removes posts. Reddit does not send 18+ posts to a feed at all unless the
 *       account has "Show NSFW content (18+)" enabled, so with that account setting off the
 *       filtered feed is simply empty.</li>
 *   <li>A listing carries no identity, so this applies to every listing built this way, not to
 *       the front page alone.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class NsfwFeedModePatch {

    /**
     * Whether the "no item was understood" warning has already been logged.
     * Only logged once, since this runs on every page of every feed.
     */
    /**
     * Whether the one-off diagnostic toast has been shown this app start. Temporary: it exists
     * to answer, on a real device, whether this listing hook is reached at all on a given Reddit
     * version - something no amount of decompiling settles. Remove once that is known.
     */
    private static final java.util.Set<String> reportedSources =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If NSFW mode is currently on.
     */
    public static boolean isNsfwModeEnabled() {
        return Settings.NSFW_FEED_MODE.get();
    }

    /**
     * Turns NSFW mode on or off. Exists so the mode can be driven from somewhere
     * other than the settings screen.
     */
    public static void setNsfwModeEnabled(boolean enabled) {
        Settings.NSFW_FEED_MODE.save(enabled);
    }

    /**
     * Injection point. Filters the posts the modern feed builds its elements from.
     *
     * <p>This is the hook that reaches the screen. {@link #filterListing} sits on the cache path -
     * on device it filtered a listing to nothing while the rendered feed carried on unchanged -
     * whereas every post that gets displayed passes through here on its way to becoming a feed
     * element, whatever fetched it.
     *
     * @param links The posts about to be turned into feed elements.
     * @return Only the NSFW posts, or the unchanged list if NSFW mode is off or nothing was
     *         readable.
     */
    public static List<?> filterFeedLinks(List<?> links) {
        return filter(links, "feed");
    }

    /**
     * Injection point. Filters the children of a feed listing.
     *
     * @param list The posts the listing was built with.
     * @return Only the NSFW posts, or the unchanged list if NSFW mode is off
     *         or the listing could not be read.
     */
    public static List<?> filterListing(List<?> list) {
        return filter(list, "listing");
    }

    private static List<?> filter(List<?> list, String source) {
        try {
            if (list == null || list.isEmpty() || !isNsfwModeEnabled()) {
                return list;
            }

            NsfwPostDetector.FilterResult result = NsfwPostDetector.filterToNsfw(list);

            if (result.classifiedCount == 0) {
                // Not one item of this listing exposed an NSFW flag, which means the model
                // changed rather than that the page happens to hold no 18+ posts. Fail open:
                // showing the feed unfiltered is a far better failure than silently emptying it.
                if (reportedSources.add("unreadable-" + source)) {
                    String model = describeFirstItem(list);
                    Logger.printInfo(() -> "NSFW mode: no NSFW flag found on "
                            + model + ", leaving listings unfiltered");
                    Utils.showToastLong("NSFW mode (" + source + "): could not read "
                            + model + ", left unfiltered");
                }
                return list;
            }

            Logger.printDebug(() -> "NSFW mode: kept " + result.nsfwItems.size()
                    + " of " + list.size() + " posts");

            if (reportedSources.add(source)) {
                Utils.showToastLong("NSFW mode (" + source + "): kept "
                        + result.nsfwItems.size() + " of " + list.size() + " posts"
                        // The drawer row has no reliable channel of its own when it fails to
                        // appear, and this toast is known to reach the screen.
                        + "\nDrawer row: " + NsfwDrawerRow.diagnostic());
            }

            return result.nsfwItems;
        } catch (Exception ex) {
            Logger.printException(() -> "filterListing failure", ex);
            return list;
        }
    }

    private static String describeFirstItem(List<?> list) {
        for (Object item : list) {
            if (item != null) {
                return item.getClass().getName();
            }
        }
        return "an empty listing";
    }
}
