package app.variablenine.extension.youtube.patches.components;

/**
 * Dependency-free self-test for {@link BrainrotDetector}. Runs with a plain JDK, no Gradle or
 * JUnit required:
 *
 * <pre>
 *   cd extensions/extension/src/main/java &amp;&amp; javac app/variablenine/extension/youtube/patches/components/BrainrotDetector.java -d /tmp/bd
 *   javac -cp /tmp/bd ../../test/java/app/variablenine/extension/youtube/patches/components/BrainrotDetectorSelfTest.java -d /tmp/bd
 *   java  -cp /tmp/bd app.variablenine.extension.youtube.patches.components.BrainrotDetectorSelfTest
 * </pre>
 *
 * Exits non-zero if any case fails, so it can gate CI.
 */
public final class BrainrotDetectorSelfTest {
    private static int pass = 0, fail = 0;

    private static void check(BrainrotDetector d, boolean expectHide, String comment) {
        BrainrotDetector.Verdict v = d.evaluate(comment);
        boolean ok = v.hide == expectHide;
        if (ok) pass++; else fail++;
        System.out.printf("%s  expect=%-4s got=%-4s | %-42s | %s%n",
                ok ? "PASS" : "FAIL",
                expectHide ? "HIDE" : "keep",
                v.hide ? "HIDE" : "keep",
                comment.length() > 42 ? comment.substring(0, 39) + "..." : comment,
                v.reason);
    }

    public static void main(String[] args) {
        BrainrotDetector d = new BrainrotDetector();

        System.out.println("=== SHOULD HIDE (brainrot spam) ===");
        check(d, true, "anti spiral");
        check(d, true, "fix mojang bedrock");
        check(d, true, "anti spiral viral");
        check(d, true, "anti viral");
        check(d, true, "anti anti spiral");
        check(d, true, "anti spiral anti viral spiral");
        check(d, true, "FIX MOJANG BEDROCK");
        check(d, true, "f1x m0jang b3dr0ck");
        check(d, true, "🅰nti sp1ral");
        check(d, true, "s p i r a l");
        check(d, true, "a n t i  s p i r a l");
        check(d, true, "antiiii spiraaaal");
        check(d, true, "anti-spiral!!!");
        check(d, true, "fix bedrock mojang fix bedrock");

        System.out.println();
        System.out.println("=== SHOULD KEEP (legit comments) ===");
        check(d, false, "the anti-spiral arc was peak animation honestly");
        check(d, false, "fix bedrock please mojang the redstone is broken");
        check(d, false, "i love how the spiral power scales in this fight");
        check(d, false, "this antiviral medication actually worked for me");
        check(d, false, "mojang really needs to fix the bedrock parity issues with java redstone");
        check(d, false, "great video, the editing on this is insane");
        check(d, false, "anti");
        check(d, false, "the bedrock of this community is the modders who fix everything");

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
