/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The credentials the picker talks to Tenor with.
 *
 * <p>Tenor's public v1 API was discontinued and v2 rejects any request without a
 * valid key, so a key has to come from somewhere. Rather than ship one (a key in
 * a public repository is scraped and revoked in short order), the picker reads
 * the one tenor.com itself is serving: the site embeds its configuration as a
 * base64 blob in a {@code <script id="data">} tag on every page.
 *
 * <p>This is the only undocumented part of the integration. Everything built on
 * top of it is the ordinary, documented v2 API, so if the blob ever moves the
 * damage is contained to {@link #extractEncodedPayload} and the picker can be
 * pointed at a user supplied key by constructing this class directly.
 *
 * <p>Pure by design - no Android imports - so the parsing is testable with a
 * plain JDK.
 */
public final class TenorWebConfig {

    /** Page scraped for the configuration blob. Any tenor.com page carries it; the root is smallest. */
    public static final String CONFIG_SOURCE_URL = "https://tenor.com/";

    /**
     * Marks the start of the configuration script. The id is what the site keys the
     * blob by, and has outlived several redesigns of the surrounding markup.
     */
    private static final String SCRIPT_ID_MARKER = "<script id=\"data\"";

    /** Field names inside the decoded blob. */
    public static final String FIELD_API_URL = "API_V2_URL";
    public static final String FIELD_API_KEY = "API_V2_KEY";
    public static final String FIELD_CLIENT_KEY = "API_V2_CLIENT_KEY";

    /** Used if the blob omits a field, which would otherwise leave an unusable config. */
    private static final String DEFAULT_API_URL = "https://tenor.googleapis.com/v2";
    private static final String DEFAULT_CLIENT_KEY = "tenor_web";

    public final String apiUrl;
    public final String apiKey;
    public final String clientKey;

    public TenorWebConfig(String apiUrl, String apiKey, String clientKey) {
        this.apiUrl = isBlank(apiUrl) ? DEFAULT_API_URL : stripTrailingSlash(apiUrl);
        this.apiKey = apiKey;
        this.clientKey = isBlank(clientKey) ? DEFAULT_CLIENT_KEY : clientKey;
    }

    /** A config with no key cannot be used; callers must re-fetch instead of issuing a doomed request. */
    public boolean isUsable() {
        return !isBlank(apiKey);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    /**
     * Pulls the base64 configuration payload out of a tenor.com page.
     *
     * <p>Matched by script id rather than by position or by a fixed attribute order,
     * since the nonce attribute changes on every request and the tag order does not
     * survive redesigns.
     *
     * @return The still encoded payload, or null if the page does not carry one.
     */
    public static String extractEncodedPayload(String html) {
        if (html == null) return null;

        int idIndex = html.indexOf(SCRIPT_ID_MARKER);
        if (idIndex < 0) return null;

        // Skip the remaining attributes to the end of the opening tag.
        int contentStart = html.indexOf('>', idIndex);
        if (contentStart < 0) return null;
        contentStart++;

        int contentEnd = html.indexOf("</script>", contentStart);
        if (contentEnd < 0) return null;

        String payload = html.substring(contentStart, contentEnd).trim();
        return payload.isEmpty() ? null : payload;
    }

    /**
     * Decodes the payload extracted by {@link #extractEncodedPayload} into JSON text.
     *
     * <p>Tolerates missing padding and embedded whitespace, both of which have shown
     * up in the served blob at different times.
     *
     * @return The decoded JSON, or null if the payload is not valid base64.
     */
    public static String decodePayload(String encoded) {
        if (encoded == null) return null;

        try {
            // The MIME decoder skips whitespace and other characters outside the
            // base64 alphabet, which the plain decoder rejects outright.
            byte[] decoded = Base64.getMimeDecoder().decode(padToQuad(encoded));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Restores '=' padding, which the served payload has been observed to omit. */
    private static String padToQuad(String encoded) {
        String trimmed = encoded.trim();
        int remainder = trimmed.length() % 4;
        if (remainder == 0) return trimmed;

        StringBuilder padded = new StringBuilder(trimmed);
        for (int i = remainder; i < 4; i++) {
            padded.append('=');
        }
        return padded.toString();
    }

    @Override
    public String toString() {
        // The key is deliberately not logged.
        return "TenorWebConfig{" + apiUrl + " clientKey=" + clientKey + " hasKey=" + isUsable() + "}";
    }
}
