package app.morphe.extension.reddit.nsfw;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, Android-free core for reading a post's NSFW state out of Reddit's GraphQL cell models.
 *
 * <p>The modern home feed is built from GraphQL "cells", not from the {@code Link} domain model,
 * and its feed elements carry only a link id, a unique id, an "is promoted" flag and an
 * identifier - no NSFW anywhere. The flag the app itself uses to draw the 18+ tag lives on the
 * metadata cell as a {@code statusIndicators} list of
 * {@code com.reddit.type.PostStatusIndicatorType}, whose {@code NSFW} constant is not obfuscated.
 *
 * <p>Everything here is found by shape rather than by name, because every field on those
 * fragments is renamed each release:
 * <ul>
 *   <li>the post id is the {@code String} field holding a {@code t3_} fullname, which is
 *       self-validating - no other string on the fragment looks like one;</li>
 *   <li>the NSFW flag is an enum constant named {@code NSFW} on a type whose class name ends in
 *       {@code PostStatusIndicatorType}.</li>
 * </ul>
 */
public final class NsfwCellScanner {

    /** Reddit's fullname prefix for a post. */
    private static final String POST_ID_PREFIX = "t3_";

    /** The unobfuscated GraphQL enum carrying a post's status tags. */
    private static final String STATUS_INDICATOR_TYPE = "PostStatusIndicatorType";

    private static final String NSFW_CONSTANT = "NSFW";

    /** How deep to walk a fragment's object graph. Cells nest a few levels at most. */
    private static final int MAX_DEPTH = 4;

    private NsfwCellScanner() {
    }

    /**
     * Reads a post id and its NSFW state out of a GraphQL cell fragment.
     *
     * @param fragment The fragment to scan.
     * @return The result, or null if this object carries no post id.
     */
    public static Scan scan(Object fragment) {
        // Only a model object is a fragment. Refusing strings, numbers and collections at the
        // root keeps stray values out of the map, even though the walk would happily find a
        // t3_ id inside one.
        if (fragment == null || !isModel(fragment)) {
            return null;
        }

        Scan scan = new Scan();
        walk(fragment, scan, 0, new IdentityHashMap<>());
        return scan.postId == null ? null : scan;
    }

    /**
     * @return Whether an object is, or contains, the NSFW status indicator.
     */
    public static boolean isNsfwIndicator(Object value) {
        if (value == null) {
            return false;
        }
        Class<?> type = value.getClass();
        // Enum constants compile to a subclass when they carry a body, so walk up.
        while (type != null) {
            if (type.getName().endsWith(STATUS_INDICATOR_TYPE)) {
                return NSFW_CONSTANT.equals(nameOf(value));
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static String nameOf(Object value) {
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    /**
     * @return Whether an object is one of Reddit's own model classes rather than a framework
     *         value or a container.
     */
    private static boolean isModel(Object value) {
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Collection
                || value instanceof Enum) {
            return false;
        }
        String className = value.getClass().getName();
        return !className.startsWith("java.") && !className.startsWith("kotlin.")
                && !className.startsWith("android.");
    }

    private static void walk(Object node, Scan scan, int depth, Map<Object, Boolean> seen) {
        if (node == null || depth > MAX_DEPTH || seen.put(node, Boolean.TRUE) != null) {
            return;
        }

        if (node instanceof Collection) {
            for (Object item : (Collection<?>) node) {
                if (isNsfwIndicator(item)) {
                    scan.nsfw = true;
                } else {
                    walk(item, scan, depth + 1, seen);
                }
            }
            return;
        }

        if (isNsfwIndicator(node)) {
            scan.nsfw = true;
            return;
        }

        if (node instanceof CharSequence) {
            String text = node.toString();
            if (scan.postId == null && text.startsWith(POST_ID_PREFIX) && text.length() > POST_ID_PREFIX.length()) {
                scan.postId = text;
            }
            return;
        }

        // Only walk Reddit's own models; anything else is framework noise.
        String className = node.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("kotlin.")
                || className.startsWith("android.") || node instanceof Number
                || node instanceof Boolean) {
            return;
        }

        for (Field field : declaredFields(node.getClass())) {
            Object value;
            try {
                value = field.get(node);
            } catch (Throwable ex) {
                continue;
            }
            walk(value, scan, depth + 1, seen);
        }
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        try {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                fields.add(field);
            }
        } catch (Throwable ignored) {
            // Nothing readable.
        }
        return fields;
    }

    /**
     * What a scan found on one fragment.
     */
    public static final class Scan {
        /** The post's {@code t3_} fullname, or null if none was present. */
        public String postId;

        /** Whether the fragment carried the NSFW status indicator. */
        public boolean nsfw;
    }
}
