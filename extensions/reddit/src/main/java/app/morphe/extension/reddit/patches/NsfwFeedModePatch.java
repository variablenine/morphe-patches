package app.morphe.extension.reddit.patches;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.nsfw.NsfwCellScanner;
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
     * Which hooks have already reported themselves on screen this app start. Temporary: the
     * toasts exist to answer, on a real device, which hooks a given Reddit version actually
     * reaches - something no amount of decompiling settles. Remove once that is known.
     */
    private static final java.util.Set<String> reportedSources =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** How many home feed pages report their result on screen before the toasts stop. */
    private static final int DIAGNOSTIC_PAGES = 3;

    /** How many distinct tags the diagnostic toast names before it summarises the rest. */
    private static final int DIAGNOSTIC_TAGS = 8;

    /** How many home feed pages have been filtered this app start. */
    private static final java.util.concurrent.atomic.AtomicInteger pagesSeen =
            new java.util.concurrent.atomic.AtomicInteger();

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
     * Injection point. Filters the home feed's GraphQL response before its posts become feed
     * elements.
     *
     * <p>This is the hook that reaches the home feed. The other two do not: the listing model is
     * the cache path, and the listing element mapper turned out to serve only the History feed.
     * The home feed is built from GraphQL cells whose feed elements carry no NSFW flag at all,
     * so the decision has to be made here, on the response, where the post's indicators cell
     * still carries its {@code CellIndicatorType.NSFW}.
     *
     * <p>Finds the edge list by type and filters it in place, so the cursor and the dist the
     * mapper reads off the same response are left exactly as they arrived. Edges that are not
     * posts are kept rather than dropped, and a response where no post was recognised at all is
     * left alone, so a model change costs the filter rather than the feed.
     *
     * @param response The feed response about to be mapped.
     */
    public static void filterHomeFeedResponse(Object response) {
        try {
            if (response == null || !isNsfwModeEnabled()) {
                return;
            }

            for (java.lang.reflect.Field field : response.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);

                Object value = field.get(response);
                if (!(value instanceof List)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                List<Object> edges = (List<Object>) value;
                if (edges.isEmpty()) {
                    continue;
                }

                filterEdges(edges);
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "filterHomeFeedResponse failure", ex);
        }
    }

    private static void filterEdges(List<Object> edges) {
        int page = pagesSeen.incrementAndGet();
        // The first page pays for a full walk of every edge so the tags it carried can be named
        // in the diagnostic toast. Later pages stop as soon as they have their answer.
        java.util.Set<String> tags = page == 1
                ? java.util.Collections.synchronizedSet(new java.util.TreeSet<String>())
                : null;

        List<Object> keep = new ArrayList<>(edges.size());
        Object lastPost = null;
        int posts = 0;
        int kept = 0;

        for (Object edge : edges) {
            NsfwCellScanner.Scan scan = NsfwCellScanner.scan(edge, tags);
            if (scan == null) {
                // Not a post - a carousel, an ad unit, an announcement. Leave it.
                keep.add(edge);
                continue;
            }
            posts++;
            lastPost = edge;
            if (scan.nsfw) {
                keep.add(edge);
                kept++;
            }
        }

        if (posts == 0) {
            // Not one edge was recognised as a post, which means the response shape changed
            // rather than that this page happens to hold none. Fail open: an unfiltered feed is
            // a far better failure than an empty one.
            if (reportedSources.add("unreadable-home")) {
                Utils.showToastLong("NSFW mode: no post was recognised in the home response, "
                        + "feed left unfiltered");
                Logger.printInfo(() -> "NSFW mode: no readable posts in the home response");
            }
            return;
        }

        // A page filtered down to no posts at all leaves the feed with nothing to draw and
        // nothing to scroll, which reads on screen as a spinner that never resolves. Keeping one
        // post back costs a single SFW post and lets the feed page on to where the 18+ ones are.
        boolean padded = false;
        if (kept == 0) {
            keep.add(lastPost);
            padded = true;
        }

        edges.clear();
        edges.addAll(keep);

        final int keptPosts = kept;
        final int totalPosts = posts;
        Logger.printDebug(() -> "NSFW mode: home page " + page + " kept "
                + keptPosts + " of " + totalPosts + " posts");

        // Temporary, and deliberately noisy for the first few pages: on-device is the only place
        // the answer to "did it read the tags" actually exists.
        if (page <= DIAGNOSTIC_PAGES) {
            StringBuilder message = new StringBuilder("NSFW mode: page ").append(page)
                    .append(" kept ").append(kept).append(" of ").append(posts).append(" posts");
            if (padded) {
                message.append(" (+1 held back so the feed can page on)");
            }
            if (tags != null) {
                message.append("\ntags seen: ").append(describe(tags));
            }
            Utils.showToastLong(message.toString());
        }
    }

    /**
     * @return A short, readable list of the tags a page carried, for the diagnostic toast.
     */
    private static String describe(java.util.Set<String> tags) {
        if (tags.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (String tag : tags) {
            if (shown == DIAGNOSTIC_TAGS) {
                text.append(", +").append(tags.size() - shown).append(" more");
                break;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(tag);
            shown++;
        }
        return text.toString();
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
