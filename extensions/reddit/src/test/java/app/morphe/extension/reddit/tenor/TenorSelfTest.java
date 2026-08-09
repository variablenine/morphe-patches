package app.morphe.extension.reddit.tenor;

/**
 * Dependency-free self-test for the pure parts of the Tenor GIF picker:
 * {@link TenorRequestBuilder}, {@link TenorWebConfig}, {@link TenorGif} and
 * {@link MasonryColumns}. Runs with a plain JDK:
 *
 * <pre>
 *   cd extensions/reddit/src
 *   javac main/java/app/morphe/extension/reddit/tenor/TenorGif.java \
 *         main/java/app/morphe/extension/reddit/tenor/TenorWebConfig.java \
 *         main/java/app/morphe/extension/reddit/tenor/TenorRequestBuilder.java \
 *         main/java/app/morphe/extension/reddit/tenor/MasonryColumns.java -d /tmp/tenor
 *   javac -cp /tmp/tenor test/java/app/morphe/extension/reddit/tenor/TenorSelfTest.java -d /tmp/tenor
 *   java  -cp /tmp/tenor app.morphe.extension.reddit.tenor.TenorSelfTest
 * </pre>
 *
 * <p>The networked parts (the API client, the image loader, the picker UI) need
 * an Android runtime and are not covered here.
 */
public final class TenorSelfTest {
    private static int pass = 0, fail = 0;

    private static void check(boolean condition, String name) {
        if (condition) pass++; else fail++;
        System.out.printf("%s  %s%n", condition ? "PASS" : "FAIL", name);
    }

    private static TenorWebConfig config() {
        return new TenorWebConfig("https://tenor.googleapis.com/v2", "TESTKEY", "tenor_web");
    }

    private static void requestBuilderTests() {
        System.out.println("\n-- TenorRequestBuilder --");

        check("hello+world".equals(TenorRequestBuilder.encode("hello world")),
                "encode turns spaces into +");
        check("a%26b%3Dc".equals(TenorRequestBuilder.encode("a&b=c")),
                "encode escapes query delimiters");

        java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("first", "1");
        parameters.put("empty", "");
        parameters.put("missing", null);
        parameters.put("last", "2");
        check("https://x/y?first=1&last=2".equals(TenorRequestBuilder.build("https://x", "/y", parameters)),
                "build drops null and empty parameters");
        check("https://x/y".equals(TenorRequestBuilder.build("https://x", "/y", new java.util.LinkedHashMap<>())),
                "build omits the ? when nothing survives");

        TenorWebConfig config = config();

        String firstPage = TenorRequestBuilder.search(config, "happy cat", "off", null);
        check(firstPage.startsWith("https://tenor.googleapis.com/v2/search?"), "search hits the search endpoint");
        check(firstPage.contains("key=TESTKEY"), "search sends the api key");
        check(firstPage.contains("client_key=tenor_web"), "search sends the client key");
        check(firstPage.contains("q=happy+cat"), "search encodes the query");
        check(firstPage.contains("media_filter=tinygif%2Cgif"), "search restricts the renditions");
        check(firstPage.contains("limit=" + TenorRequestBuilder.PAGE_SIZE), "search sends the page size");
        check(!firstPage.contains("pos="), "first page sends no cursor");

        String secondPage = TenorRequestBuilder.search(config, "cat", "off", "CDIQ_cursor");
        check(secondPage.contains("pos=CDIQ_cursor"), "later pages send the cursor");

        String featured = TenorRequestBuilder.featured(config, "medium", null);
        check(featured.contains("/featured?"), "featured hits the featured endpoint");
        check(featured.contains("contentfilter=medium"), "featured passes the content filter through");

        String categories = TenorRequestBuilder.categories(config, "high");
        check(categories.contains("/categories?"), "categories hits the categories endpoint");
        check(categories.contains("type=featured"), "categories asks for the featured set");

        String autocomplete = TenorRequestBuilder.autocomplete(config, "ca", "off");
        check(autocomplete.contains("/autocomplete?"), "autocomplete hits the autocomplete endpoint");
        check(autocomplete.contains("q=ca"), "autocomplete sends the partial query");
        check(autocomplete.contains("limit=8"), "autocomplete asks for a short list");
    }

    private static void webConfigTests() {
        System.out.println("\n-- TenorWebConfig --");

        // Nonce and attribute order match what tenor.com actually serves.
        String html = "<html><head><script id=\"data\" type=\"text/x-cache\" nonce=\"abc123\">"
                + "eyJBIjoiQiJ9</script><script id=\"store-cache\">{}</script></head></html>";
        check("eyJBIjoiQiJ9".equals(TenorWebConfig.extractEncodedPayload(html)),
                "payload is found past the nonce attribute");
        check(TenorWebConfig.extractEncodedPayload("<html>no config here</html>") == null,
                "missing script yields null");
        check(TenorWebConfig.extractEncodedPayload("<script id=\"data\">unterminated") == null,
                "unterminated script yields null");
        check(TenorWebConfig.extractEncodedPayload("<script id=\"data\"></script>") == null,
                "empty payload yields null");
        check(TenorWebConfig.extractEncodedPayload(null) == null, "null html yields null");

        check("{\"A\":\"B\"}".equals(TenorWebConfig.decodePayload("eyJBIjoiQiJ9")),
                "payload decodes to json");
        // "{}" encodes to "e30=", which the server has been seen to serve unpadded.
        check("{}".equals(TenorWebConfig.decodePayload("e30")), "missing padding is restored");
        check("{}".equals(TenorWebConfig.decodePayload("  e30=  ")), "surrounding whitespace is tolerated");
        check(TenorWebConfig.decodePayload(null) == null, "null payload yields null");

        // A distinctive key: a short one would match by chance inside "clientKey=".
        TenorWebConfig full = new TenorWebConfig("https://tenor.googleapis.com/v2/", "SUPERSECRET", "web");
        check("https://tenor.googleapis.com/v2".equals(full.apiUrl), "trailing slash is stripped");
        check(full.isUsable(), "config with a key is usable");

        TenorWebConfig sparse = new TenorWebConfig(null, "K", null);
        check("https://tenor.googleapis.com/v2".equals(sparse.apiUrl), "missing url falls back to the v2 default");
        check("tenor_web".equals(sparse.clientKey), "missing client key falls back to the web default");

        check(!new TenorWebConfig("u", null, "c").isUsable(), "config without a key is unusable");
        check(!new TenorWebConfig("u", "   ", "c").isUsable(), "blank key is unusable");
        check(!full.toString().contains("SUPERSECRET"), "toString does not leak the key");
    }

    private static void gifTests() {
        System.out.println("\n-- TenorGif --");

        check(gif(200, 200).scaledHeight(100) == 100, "square scales to a square");
        check(gif(400, 200).scaledHeight(100) == 50, "wide gif halves in height");
        check(gif(200, 400).scaledHeight(100) == 200, "tall gif doubles in height");
        check(gif(0, 0).scaledHeight(120) == 120, "missing dimensions fall back to square");
        check(gif(-5, 10).scaledHeight(120) == 120, "negative dimensions fall back to square");
        check(gif(10000, 1).scaledHeight(100) >= 1, "extreme ratio never scales below one pixel");
    }

    private static TenorGif gif(int width, int height) {
        return new TenorGif("id", "description", "preview", width, height,
                "full", width, height, 0, "https://tenor.com/view/id");
    }

    private static void masonryTests() {
        System.out.println("\n-- MasonryColumns --");

        MasonryColumns columns = new MasonryColumns(2);
        check(columns.columnCount() == 2, "column count is reported");
        check(columns.shortestColumn() == 0, "empty grid starts at the leftmost column");

        check(columns.place(100) == 0, "first item goes left");
        check(columns.place(50) == 1, "second item goes to the empty right column");
        check(columns.place(20) == 1, "third item goes to the shorter right column");
        check(columns.height(0) == 100 && columns.height(1) == 70, "heights accumulate per column");
        check(columns.place(10) == 1, "placement keeps following the shorter column");

        MasonryColumns tied = new MasonryColumns(3);
        tied.place(10);
        tied.place(10);
        tied.place(10);
        check(tied.shortestColumn() == 0, "an even grid ties back to the leftmost column");

        MasonryColumns negative = new MasonryColumns(1);
        negative.place(-40);
        check(negative.height(0) == 0, "negative heights do not shrink a column");

        columns.reset();
        check(columns.height(0) == 0 && columns.height(1) == 0, "reset clears every column");

        boolean threw = false;
        try {
            new MasonryColumns(0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "a grid needs at least one column");
    }

    public static void main(String[] args) {
        requestBuilderTests();
        webConfigTests();
        gifTests();
        masonryTests();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
