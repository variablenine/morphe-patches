package app.morphe.extension.reddit.nsfw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.reddit.nsfw.NsfwPostDetector.FilterResult;
import app.morphe.extension.reddit.nsfw.NsfwPostDetector.Rating;

/**
 * Dependency-free self-test for {@link NsfwPostDetector}. Runs with a plain JDK:
 *
 * <pre>
 *   cd extensions/reddit/src
 *   javac main/java/app/morphe/extension/reddit/nsfw/NsfwPostDetector.java -d /tmp/nsfw
 *   javac -cp /tmp/nsfw test/java/app/morphe/extension/reddit/nsfw/NsfwPostDetectorSelfTest.java -d /tmp/nsfw
 *   java  -cp /tmp/nsfw app.morphe.extension.reddit.nsfw.NsfwPostDetectorSelfTest
 * </pre>
 *
 * <p>The fakes below stand in for Reddit's feed models: the detector only ever sees them
 * through reflection, so exercising it against local classes exercises the real code path.
 */
public final class NsfwPostDetectorSelfTest {
    private static int pass = 0, fail = 0;

    private static void check(boolean cond, String name) {
        if (cond) pass++; else fail++;
        System.out.printf("%s  %s%n", cond ? "PASS" : "FAIL", name);
    }

    // --- fakes -------------------------------------------------------------------------

    /** Reddit's own shape: a primitive over18 flag named after the over_18 JSON field. */
    public static final class PrimitiveLink {
        private final boolean over18;

        public PrimitiveLink(boolean over18) {
            this.over18 = over18;
        }

        public boolean getOver18() {
            return over18;
        }
    }

    /** Boxed flag, as a nullable Kotlin Boolean? compiles to. */
    public static final class BoxedLink {
        private final Boolean over18;

        public BoxedLink(Boolean over18) {
            this.over18 = over18;
        }

        public Boolean getOver18() {
            return over18;
        }
    }

    /** A renamed flag, to prove the fallback names are used. */
    public static final class RenamedLink {
        public boolean isNsfw() {
            return true;
        }
    }

    /** Both names present: the higher priority one must win. */
    public static final class AmbiguousLink {
        public boolean getOver18() {
            return true;
        }

        public boolean isNsfw() {
            return false;
        }
    }

    /** Nothing usable at all. */
    public static final class OpaqueElement {
        public String getTitle() {
            return "announcement";
        }
    }

    /** Right name, wrong type. */
    public static final class WrongTypeLink {
        public String getOver18() {
            return "true";
        }
    }

    /** Right name and type, but takes an argument. */
    public static final class ArgumentLink {
        public boolean getOver18(int unused) {
            return true;
        }
    }

    /** A feed element that wraps the real post. */
    public static final class WrapperElement {
        private final Object link;

        public WrapperElement(Object link) {
            this.link = link;
        }

        public Object getLink() {
            return link;
        }
    }

    /** A second wrapper layer, using a different accessor name. */
    public static final class OuterElement {
        private final Object item;

        public OuterElement(Object item) {
            this.item = item;
        }

        public Object getItem() {
            return item;
        }
    }

    /** Its accessor blows up, but the post is reachable by unwrapping. */
    public static final class ThrowingLink {
        private final Object link;

        public ThrowingLink(Object link) {
            this.link = link;
        }

        public boolean getOver18() {
            throw new IllegalStateException("model not initialised");
        }

        public Object getLink() {
            return link;
        }
    }

    /** Returns itself, which must not recurse forever. */
    public static final class SelfReferencingElement {
        public Object getLink() {
            return this;
        }
    }

    /** A wrapper accessor returning a String must not be followed. */
    public static final class StringWrapperElement {
        public String getData() {
            return "getOver18";
        }
    }

    // --- tests -------------------------------------------------------------------------

    public static void main(String[] args) {
        NsfwPostDetector.clearCaches();

        // --- direct flags ---
        check(NsfwPostDetector.classify(new PrimitiveLink(true)) == Rating.NSFW,
                "primitive getOver18() == true is NSFW");
        check(NsfwPostDetector.classify(new PrimitiveLink(false)) == Rating.SFW,
                "primitive getOver18() == false is SFW");
        check(NsfwPostDetector.classify(new BoxedLink(Boolean.TRUE)) == Rating.NSFW,
                "boxed getOver18() == TRUE is NSFW");
        check(NsfwPostDetector.classify(new BoxedLink(Boolean.FALSE)) == Rating.SFW,
                "boxed getOver18() == FALSE is SFW");
        check(NsfwPostDetector.classify(new BoxedLink(null)) == Rating.UNKNOWN,
                "boxed getOver18() == null is UNKNOWN, not SFW");
        check(NsfwPostDetector.classify(new RenamedLink()) == Rating.NSFW,
                "fallback accessor name isNsfw() is used");
        check(NsfwPostDetector.classify(new AmbiguousLink()) == Rating.NSFW,
                "getOver18() takes priority over isNsfw()");

        // --- nothing to read ---
        check(NsfwPostDetector.classify(null) == Rating.UNKNOWN,
                "null item is UNKNOWN");
        check(NsfwPostDetector.classify(new OpaqueElement()) == Rating.UNKNOWN,
                "model without any NSFW flag is UNKNOWN");
        check(NsfwPostDetector.classify(new WrongTypeLink()) == Rating.UNKNOWN,
                "non boolean getOver18() is ignored");
        check(NsfwPostDetector.classify(new ArgumentLink()) == Rating.UNKNOWN,
                "getOver18(int) is ignored");
        check(NsfwPostDetector.classify("just a string") == Rating.UNKNOWN,
                "unrelated object is UNKNOWN");

        // --- unwrapping ---
        check(NsfwPostDetector.classify(new WrapperElement(new PrimitiveLink(true))) == Rating.NSFW,
                "wrapped NSFW post is found one level down");
        check(NsfwPostDetector.classify(new WrapperElement(new PrimitiveLink(false))) == Rating.SFW,
                "wrapped SFW post is found one level down");
        check(NsfwPostDetector.classify(
                        new OuterElement(new WrapperElement(new PrimitiveLink(true)))) == Rating.NSFW,
                "wrapped NSFW post is found two levels down");
        check(NsfwPostDetector.classify(new OuterElement(
                        new OuterElement(new WrapperElement(new PrimitiveLink(true))))) == Rating.UNKNOWN,
                "unwrapping stops after the depth limit");
        check(NsfwPostDetector.classify(new WrapperElement(new OpaqueElement())) == Rating.UNKNOWN,
                "wrapper around an unreadable post is UNKNOWN");
        check(NsfwPostDetector.classify(new WrapperElement(null)) == Rating.UNKNOWN,
                "wrapper around null is UNKNOWN");
        check(NsfwPostDetector.classify(new StringWrapperElement()) == Rating.UNKNOWN,
                "a String returning wrapper accessor is not followed");

        // --- hostile models ---
        check(NsfwPostDetector.classify(new ThrowingLink(new PrimitiveLink(true))) == Rating.NSFW,
                "a throwing accessor falls back to unwrapping");
        check(NsfwPostDetector.classify(new ThrowingLink(null)) == Rating.UNKNOWN,
                "a throwing accessor with nothing to unwrap is UNKNOWN");
        check(NsfwPostDetector.classify(new SelfReferencingElement()) == Rating.UNKNOWN,
                "a self referencing wrapper terminates");

        // --- accessor reporting ---
        check("getOver18".equals(NsfwPostDetector.nsfwAccessorNameFor(PrimitiveLink.class)),
                "accessor name is reported for a readable model");
        check(NsfwPostDetector.nsfwAccessorNameFor(OpaqueElement.class) == null,
                "no accessor name is reported for an unreadable model");

        // --- listing filter ---
        List<Object> mixed = Arrays.asList(
                new PrimitiveLink(false),
                new PrimitiveLink(true),
                new OpaqueElement(),
                new BoxedLink(Boolean.TRUE),
                new PrimitiveLink(false));
        FilterResult mixedResult = NsfwPostDetector.filterToNsfw(mixed);
        check(mixedResult.nsfwItems.size() == 2,
                "only NSFW items are kept");
        check(mixedResult.nsfwItems.get(0) == mixed.get(1) && mixedResult.nsfwItems.get(1) == mixed.get(3),
                "kept items stay in their original order");
        check(mixedResult.classifiedCount == 4,
                "classified count counts NSFW and SFW but not UNKNOWN");
        check(mixedResult.removedCount == 3,
                "removed count is the number of dropped items");

        FilterResult unreadable = NsfwPostDetector.filterToNsfw(
                Arrays.asList(new OpaqueElement(), new OpaqueElement()));
        check(unreadable.classifiedCount == 0 && unreadable.nsfwItems.isEmpty(),
                "a wholly unreadable listing reports zero classified, so callers can fail open");

        FilterResult allSfw = NsfwPostDetector.filterToNsfw(
                Arrays.asList(new PrimitiveLink(false), new PrimitiveLink(false)));
        check(allSfw.classifiedCount == 2 && allSfw.nsfwItems.isEmpty(),
                "an all SFW listing is distinguishable from an unreadable one");

        check(NsfwPostDetector.filterToNsfw(null).nsfwItems.isEmpty(),
                "null listing yields an empty result");
        check(NsfwPostDetector.filterToNsfw(new ArrayList<>()).nsfwItems.isEmpty(),
                "empty listing yields an empty result");

        List<Object> withNulls = Arrays.asList(null, new PrimitiveLink(true), null);
        FilterResult nullResult = NsfwPostDetector.filterToNsfw(withNulls);
        check(nullResult.nsfwItems.size() == 1 && nullResult.classifiedCount == 1,
                "null entries are dropped and not counted as classified");

        List<?> source = Arrays.asList(new PrimitiveLink(true));
        List<Object> filtered = NsfwPostDetector.filterToNsfw(source).nsfwItems;
        filtered.add(new PrimitiveLink(true));
        check(filtered.size() == 2 && source.size() == 1,
                "the filtered listing is a fresh mutable list");

        // --- caching ---
        check(NsfwPostDetector.classify(new PrimitiveLink(true)) == Rating.NSFW
                        && NsfwPostDetector.classify(new PrimitiveLink(false)) == Rating.SFW,
                "cached accessors still classify correctly on repeat calls");

        System.out.printf("%nRESULT: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
