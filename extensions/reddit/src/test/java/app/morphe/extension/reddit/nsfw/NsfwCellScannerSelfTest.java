package app.morphe.extension.reddit.nsfw;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free self-test for {@link NsfwCellScanner}. Runs with a plain JDK:
 *
 * <pre>
 *   cd extensions/reddit/src
 *   javac main/java/app/morphe/extension/reddit/nsfw/NsfwCellScanner.java -d /tmp/nsfw
 *   javac -cp /tmp/nsfw test/java/app/morphe/extension/reddit/nsfw/NsfwCellScannerSelfTest.java -d /tmp/nsfw
 *   java  -cp /tmp/nsfw app.morphe.extension.reddit.nsfw.NsfwCellScannerSelfTest
 * </pre>
 *
 * <p>The fakes mirror the shape of Reddit's GraphQL metadata cells: obfuscated field names, a
 * {@code t3_} post id somewhere among the strings, and a status indicator list.
 */
public final class NsfwCellScannerSelfTest {
    private static int pass = 0, fail = 0;

    private static void check(boolean cond, String name) {
        if (cond) pass++; else fail++;
        System.out.printf("%s  %s%n", cond ? "PASS" : "FAIL", name);
    }

    /** Stands in for com.reddit.type.PostStatusIndicatorType. */
    public enum PostStatusIndicatorType { NSFW, SPOILER, PINNED, LOCKED }

    /** An unrelated enum that must not be mistaken for the indicator. */
    public enum OtherType { NSFW }

    /** MetadataCellFragment, with the renamed fields a real build has. */
    public static final class MetadataCell {
        public final String f1;
        public final String f2;
        public final List<?> f3;

        public MetadataCell(String typename, String id, List<?> statusIndicators) {
            this.f1 = typename;
            this.f2 = id;
            this.f3 = statusIndicators;
        }
    }

    /** A cell nested inside a group, inside an edge. */
    public static final class Group {
        public final List<?> cells;
        public Group(List<?> cells) { this.cells = cells; }
    }

    public static final class Edge {
        public final Object node;
        public Edge(Object node) { this.node = node; }
    }

    /** Self-referencing, to prove the walk terminates. */
    public static final class Cyclic {
        public Object self;
        public String id = "t3_cycle";
    }

    public static void main(String[] args) {
        // --- the flag itself ---
        check(NsfwCellScanner.isNsfwIndicator(PostStatusIndicatorType.NSFW),
                "NSFW indicator is recognised");
        check(!NsfwCellScanner.isNsfwIndicator(PostStatusIndicatorType.SPOILER),
                "SPOILER is not NSFW");
        check(!NsfwCellScanner.isNsfwIndicator(OtherType.NSFW),
                "an NSFW constant on an unrelated enum is ignored");
        check(!NsfwCellScanner.isNsfwIndicator("NSFW"),
                "the bare string NSFW is not an indicator");
        check(!NsfwCellScanner.isNsfwIndicator(null), "null is not an indicator");

        // --- a flat metadata cell ---
        NsfwCellScanner.Scan nsfw = NsfwCellScanner.scan(new MetadataCell(
                "MetadataCell", "t3_abc123",
                Arrays.asList(PostStatusIndicatorType.NSFW, PostStatusIndicatorType.PINNED)));
        check(nsfw != null && "t3_abc123".equals(nsfw.postId) && nsfw.nsfw,
                "flat cell yields its post id and NSFW state");

        NsfwCellScanner.Scan sfw = NsfwCellScanner.scan(new MetadataCell(
                "MetadataCell", "t3_def456", Collections.emptyList()));
        check(sfw != null && "t3_def456".equals(sfw.postId) && !sfw.nsfw,
                "cell with no indicators is not NSFW");

        NsfwCellScanner.Scan spoiler = NsfwCellScanner.scan(new MetadataCell(
                "MetadataCell", "t3_ghi", Arrays.asList(PostStatusIndicatorType.SPOILER)));
        check(spoiler != null && !spoiler.nsfw, "a spoiler-only post is not NSFW");

        // --- nested, as the real feed delivers it ---
        Edge edge = new Edge(new Group(Arrays.asList(
                new MetadataCell("Other", "t3_nested", Arrays.asList(PostStatusIndicatorType.NSFW)))));
        NsfwCellScanner.Scan nested = NsfwCellScanner.scan(edge);
        check(nested != null && "t3_nested".equals(nested.postId) && nested.nsfw,
                "nested cell is found through the edge and group");

        // --- id detection ---
        NsfwCellScanner.Scan noId = NsfwCellScanner.scan(new MetadataCell(
                "MetadataCell", "not-a-fullname", Collections.emptyList()));
        check(noId == null, "a fragment with no t3_ id yields null");

        NsfwCellScanner.Scan firstId = NsfwCellScanner.scan(new MetadataCell(
                "t3_typename_lookalike", "t3_real", Collections.emptyList()));
        check(firstId != null && firstId.postId.startsWith("t3_"),
                "a t3_ string is taken as the id");

        check(NsfwCellScanner.scan(null) == null, "null fragment yields null");
        check(NsfwCellScanner.scan("t3_bare") == null,
                "a bare string is not a fragment");

        // --- termination ---
        Cyclic cyclic = new Cyclic();
        cyclic.self = cyclic;
        NsfwCellScanner.Scan cyc = NsfwCellScanner.scan(cyclic);
        check(cyc != null && "t3_cycle".equals(cyc.postId), "a self-referencing model terminates");

        // --- depth limit ---
        Object deep = new MetadataCell("x", "t3_deep", Arrays.asList(PostStatusIndicatorType.NSFW));
        for (int i = 0; i < 8; i++) deep = new Edge(deep);
        NsfwCellScanner.Scan tooDeep = NsfwCellScanner.scan(deep);
        check(tooDeep == null || !tooDeep.nsfw, "the walk stops rather than recursing without bound");

        System.out.printf("%nRESULT: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
