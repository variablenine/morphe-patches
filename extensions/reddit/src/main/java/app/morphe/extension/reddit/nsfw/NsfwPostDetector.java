package app.morphe.extension.reddit.nsfw;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure, Android-free core of NSFW mode: decides whether a single feed item is an 18+ post.
 *
 * <p>Reddit's feed models are not part of any stable API, and the exact model class differs
 * between feed types and app versions (a listing may hold {@code Link}, some {@code ILink}
 * implementation, or a wrapper element that holds one of those). Rather than compiling against
 * one guessed signature - which would throw {@link NoSuchMethodError} at runtime the moment
 * Reddit renames or reshapes the model - this looks the accessor up reflectively and caches it
 * per class:
 *
 * <ol>
 *   <li>a zero-argument {@code boolean}/{@code Boolean} accessor named after Reddit's own
 *       {@code over_18} JSON field ({@code getOver18()} and friends), else</li>
 *   <li>a zero-argument accessor that unwraps a nested post model ({@code getLink()},
 *       {@code getPost()}, ...), recursing at most {@link #MAX_WRAPPER_DEPTH} levels.</li>
 * </ol>
 *
 * <p>Anything that cannot be classified is reported as {@link Rating#UNKNOWN} rather than
 * guessed at, so callers can decide whether to fail open. That matters: silently classifying an
 * unrecognised model as SFW would empty the whole feed instead of merely doing nothing.
 */
public final class NsfwPostDetector {

    public enum Rating {
        /** The item exposed an NSFW flag and it was set. */
        NSFW,
        /** The item exposed an NSFW flag and it was not set. */
        SFW,
        /** No usable NSFW flag was found on the item. */
        UNKNOWN
    }

    /**
     * Zero-argument boolean accessors that carry the 18+ flag, most likely first.
     * {@code over18} mirrors the {@code over_18} field of Reddit's own JSON API, and is the
     * name Reddit's Android models use elsewhere (such as {@code Subreddit.getOver18()}).
     */
    private static final String[] NSFW_ACCESSOR_NAMES = {
            "getOver18",
            "isOver18",
            "getOver_18",
            "isOver_18",
            "isNsfw",
            "getNsfw",
            "getIsNsfw",
            "isAdultContent",
            "getAdultContent",
    };

    /**
     * Zero-argument accessors that unwrap a feed element to the post model inside it.
     */
    private static final String[] WRAPPER_ACCESSOR_NAMES = {
            "getLink",
            "getPost",
            "getPostModel",
            "getLinkPresentationModel",
            "getModel",
            "getItem",
            "getData",
    };

    /**
     * How many times a feed item may be unwrapped while looking for the post inside it.
     * Also bounds recursion when models reference each other in a cycle.
     */
    private static final int MAX_WRAPPER_DEPTH = 2;

    /** Cache entry meaning "this class has no usable NSFW accessor". */
    private static final Method NO_ACCESSOR = sentinelMethod();

    private static final Method[] NO_WRAPPERS = new Method[0];

    private static final Map<Class<?>, Method> NSFW_ACCESSORS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Method[]> WRAPPER_ACCESSORS = new ConcurrentHashMap<>();

    private NsfwPostDetector() {
    }

    /**
     * @return The rating of a single feed item.
     */
    public static Rating classify(Object item) {
        return classify(item, 0);
    }

    /**
     * Keeps only the NSFW items of a feed listing.
     *
     * <p>The returned list is a fresh mutable list, because Reddit's own code may go on to
     * mutate the listing it was handed.
     */
    public static FilterResult filterToNsfw(List<?> items) {
        if (items == null || items.isEmpty()) {
            return new FilterResult(new ArrayList<>(), 0, 0);
        }

        List<Object> nsfwItems = new ArrayList<>(items.size());
        int classifiedCount = 0;

        for (Object item : items) {
            Rating rating = classify(item);
            if (rating != Rating.UNKNOWN) {
                classifiedCount++;
            }
            if (rating == Rating.NSFW) {
                nsfwItems.add(item);
            }
        }

        return new FilterResult(nsfwItems, classifiedCount, items.size() - nsfwItems.size());
    }

    /**
     * @return The name of the accessor used for a class, or null if it has none.
     *         Only used for logging and tests.
     */
    public static String nsfwAccessorNameFor(Class<?> type) {
        Method accessor = nsfwAccessorFor(type);
        return accessor == null ? null : accessor.getName();
    }

    /**
     * Drops every cached lookup. Only used by tests.
     */
    public static void clearCaches() {
        NSFW_ACCESSORS.clear();
        WRAPPER_ACCESSORS.clear();
    }

    private static Rating classify(Object item, int depth) {
        if (item == null) {
            return Rating.UNKNOWN;
        }

        Class<?> type = item.getClass();

        Method accessor = nsfwAccessorFor(type);
        if (accessor != null) {
            try {
                Object value = accessor.invoke(item);
                if (value instanceof Boolean) {
                    return ((Boolean) value) ? Rating.NSFW : Rating.SFW;
                }
            } catch (Throwable ex) {
                // Inaccessible or throwing model. Fall through and try to unwrap instead.
            }
        }

        if (depth >= MAX_WRAPPER_DEPTH) {
            return Rating.UNKNOWN;
        }

        for (Method wrapper : wrapperAccessorsFor(type)) {
            Object inner;
            try {
                inner = wrapper.invoke(item);
            } catch (Throwable ex) {
                continue;
            }

            if (inner == null || inner == item) {
                continue;
            }

            Rating rating = classify(inner, depth + 1);
            if (rating != Rating.UNKNOWN) {
                return rating;
            }
        }

        return Rating.UNKNOWN;
    }

    private static Method nsfwAccessorFor(Class<?> type) {
        Method cached = NSFW_ACCESSORS.get(type);
        if (cached != null) {
            return cached == NO_ACCESSOR ? null : cached;
        }

        Method found = null;
        Method[] methods = publicMethodsOf(type);

        // Iterate the candidate names in the outer loop, so the most likely name wins
        // no matter what order the class happens to declare its methods in.
        outer:
        for (String name : NSFW_ACCESSOR_NAMES) {
            for (Method method : methods) {
                if (method.getParameterCount() != 0 || !name.equals(method.getName())) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType != boolean.class && returnType != Boolean.class) {
                    continue;
                }
                found = makeAccessible(method);
                break outer;
            }
        }

        NSFW_ACCESSORS.put(type, found == null ? NO_ACCESSOR : found);
        return found;
    }

    private static Method[] wrapperAccessorsFor(Class<?> type) {
        Method[] cached = WRAPPER_ACCESSORS.get(type);
        if (cached != null) {
            return cached;
        }

        List<Method> found = new ArrayList<>(2);
        Method[] methods = publicMethodsOf(type);

        for (String name : WRAPPER_ACCESSOR_NAMES) {
            for (Method method : methods) {
                if (method.getParameterCount() != 0 || !name.equals(method.getName())) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType.isPrimitive() || returnType == void.class || returnType == String.class) {
                    continue;
                }
                found.add(makeAccessible(method));
            }
        }

        Method[] accessors = found.isEmpty() ? NO_WRAPPERS : found.toArray(new Method[0]);
        WRAPPER_ACCESSORS.put(type, accessors);
        return accessors;
    }

    private static Method[] publicMethodsOf(Class<?> type) {
        try {
            // Includes inherited and interface methods, which is where these accessors
            // usually live once Reddit's models are split across interfaces.
            return type.getMethods();
        } catch (Throwable ex) {
            return NO_WRAPPERS;
        }
    }

    private static Method makeAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable ex) {
            // Package private or otherwise restricted declaring class.
            // The call may still succeed, so keep the method either way.
        }
        return method;
    }

    private static Method sentinelMethod() {
        try {
            return NsfwPostDetector.class.getDeclaredMethod("noAccessorSentinel");
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
    }

    @SuppressWarnings("unused")
    private static void noAccessorSentinel() {
        // Never invoked. Exists only as a "no accessor found" marker,
        // because ConcurrentHashMap cannot store null values.
    }

    /**
     * Outcome of filtering one listing.
     */
    public static final class FilterResult {
        /** The NSFW items, in their original order. Mutable. */
        public final List<Object> nsfwItems;

        /**
         * How many items exposed a usable NSFW flag. Zero means the detector did not
         * understand a single item of the listing, which callers should treat as a
         * detector failure rather than as "this page has no NSFW posts".
         */
        public final int classifiedCount;

        /** How many items were dropped. */
        public final int removedCount;

        FilterResult(List<Object> nsfwItems, int classifiedCount, int removedCount) {
            this.nsfwItems = nsfwItems;
            this.classifiedCount = classifiedCount;
            this.removedCount = removedCount;
        }

        /** @return The NSFW items as an unmodifiable list. Only used by tests. */
        public List<Object> unmodifiableItems() {
            return Collections.unmodifiableList(nsfwItems);
        }
    }
}
