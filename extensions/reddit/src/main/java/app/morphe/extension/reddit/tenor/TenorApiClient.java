/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Talks to the Tenor v2 API.
 *
 * <p>Every method here blocks and must be called off the main thread.
 *
 * <p>The key is bootstrapped from tenor.com (see {@link TenorWebConfig}) unless the
 * user supplied their own in settings. Because a scraped key can be rotated out from
 * under us at any time, an authentication failure discards the cached config and
 * retries once with a freshly fetched one; only if that also fails does the call
 * report an error.
 */
public final class TenorApiClient {

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 15_000;

    /**
     * Responses are small (a page of metadata), but a hostile or broken response
     * should not be allowed to exhaust memory.
     */
    private static final int MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024;

    /**
     * tenor.com serves its configuration only to what looks like a browser.
     * The API calls themselves do not care.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * Cached for the life of the process. Not persisted: a stale key stored across
     * launches would fail the first search of every session, and re-fetching costs
     * one request the first time the picker is opened.
     */
    private static volatile TenorWebConfig cachedConfig;

    private TenorApiClient() {
    }

    /** A page of results together with the cursor that fetches the next one. */
    public static final class TenorPage {
        public final List<TenorGif> results;

        /** Cursor for the following page, or empty when the results are exhausted. */
        public final String next;

        TenorPage(List<TenorGif> results, String next) {
            this.results = results;
            this.next = next;
        }

        public boolean hasMore() {
            return next != null && !next.isEmpty();
        }

        static TenorPage empty() {
            return new TenorPage(Collections.emptyList(), "");
        }
    }

    /** Raised for any failure that should be surfaced to the user as "could not load". */
    public static final class TenorException extends Exception {
        TenorException(String message) {
            super(message);
        }

        TenorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // region Configuration

    /**
     * Discards the cached credentials so the next call re-fetches them.
     * Called when Tenor rejects the key we hold.
     */
    public static void invalidateConfig() {
        cachedConfig = null;
    }

    /**
     * The credentials to use, fetching them if they are not already cached.
     *
     * <p>A key entered in settings wins outright and is never re-fetched, so a user
     * with their own Google Cloud key never touches tenor.com at all.
     */
    private static TenorWebConfig config() throws TenorException {
        String userKey = Settings.TENOR_API_KEY.get().trim();
        if (!userKey.isEmpty()) {
            return new TenorWebConfig(null, userKey, null);
        }

        TenorWebConfig config = cachedConfig;
        if (config != null && config.isUsable()) {
            return config;
        }

        config = fetchWebConfig();
        cachedConfig = config;
        return config;
    }

    /** Scrapes tenor.com for the key its own web client uses. */
    private static TenorWebConfig fetchWebConfig() throws TenorException {
        String html = get(TenorWebConfig.CONFIG_SOURCE_URL, BROWSER_USER_AGENT);

        String payload = TenorWebConfig.extractEncodedPayload(html);
        if (payload == null) {
            throw new TenorException("tenor.com served no configuration block");
        }

        String json = TenorWebConfig.decodePayload(payload);
        if (json == null) {
            throw new TenorException("tenor.com configuration block is not valid base64");
        }

        try {
            JSONObject object = new JSONObject(json);
            TenorWebConfig config = new TenorWebConfig(
                    object.optString(TenorWebConfig.FIELD_API_URL, null),
                    object.optString(TenorWebConfig.FIELD_API_KEY, null),
                    object.optString(TenorWebConfig.FIELD_CLIENT_KEY, null));

            if (!config.isUsable()) {
                throw new TenorException("tenor.com configuration carries no api key");
            }

            Logger.printDebug(() -> "Fetched Tenor configuration: " + config);
            return config;
        } catch (org.json.JSONException ex) {
            throw new TenorException("tenor.com configuration is not valid json", ex);
        }
    }

    private static String contentFilter() {
        return Settings.TENOR_CONTENT_FILTER.get();
    }

    // endregion

    // region Endpoints

    /** The default feed, shown before the user has typed a query. */
    public static TenorPage featured(String position) throws TenorException {
        return gifPage(config -> TenorRequestBuilder.featured(config, contentFilter(), position));
    }

    /** GIFs matching a query. */
    public static TenorPage search(String query, String position) throws TenorException {
        if (query == null || query.trim().isEmpty()) {
            return featured(position);
        }
        return gifPage(config -> TenorRequestBuilder.search(config, query, contentFilter(), position));
    }

    /** Category tiles for the landing state of the picker. */
    public static List<TenorCategory> categories() throws TenorException {
        String body = getWithRetry(config -> TenorRequestBuilder.categories(config, contentFilter()));

        try {
            JSONArray tags = new JSONObject(body).optJSONArray("tags");
            if (tags == null) return Collections.emptyList();

            List<TenorCategory> categories = new ArrayList<>(tags.length());
            for (int i = 0; i < tags.length(); i++) {
                JSONObject tag = tags.optJSONObject(i);
                if (tag == null) continue;

                String searchTerm = tag.optString("searchterm", "");
                if (searchTerm.isEmpty()) continue;

                categories.add(new TenorCategory(
                        tag.optString("name", searchTerm),
                        searchTerm,
                        tag.optString("image", "")));
            }
            return categories;
        } catch (org.json.JSONException ex) {
            throw new TenorException("Malformed categories response", ex);
        }
    }

    /** Type ahead completions for a partially typed query. */
    public static List<String> autocomplete(String query) throws TenorException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String body = getWithRetry(config -> TenorRequestBuilder.autocomplete(config, query, contentFilter()));

        try {
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null) return Collections.emptyList();

            List<String> suggestions = new ArrayList<>(results.length());
            for (int i = 0; i < results.length(); i++) {
                String suggestion = results.optString(i, "");
                if (!suggestion.isEmpty()) suggestions.add(suggestion);
            }
            return suggestions;
        } catch (org.json.JSONException ex) {
            throw new TenorException("Malformed autocomplete response", ex);
        }
    }

    // endregion

    // region Plumbing

    /** Builds a request url from the credentials in force at call time. */
    private interface UrlFactory {
        String create(TenorWebConfig config);
    }

    private static TenorPage gifPage(UrlFactory factory) throws TenorException {
        String body = getWithRetry(factory);

        try {
            JSONObject object = new JSONObject(body);
            JSONArray results = object.optJSONArray("results");
            if (results == null) return TenorPage.empty();

            List<TenorGif> gifs = new ArrayList<>(results.length());
            for (int i = 0; i < results.length(); i++) {
                TenorGif gif = parseGif(results.optJSONObject(i));
                if (gif != null) gifs.add(gif);
            }

            return new TenorPage(gifs, object.optString("next", ""));
        } catch (org.json.JSONException ex) {
            throw new TenorException("Malformed search response", ex);
        }
    }

    /**
     * Converts one result object.
     *
     * @return The GIF, or null if it carries neither rendition the picker needs,
     * in which case it is skipped rather than failing the whole page.
     */
    private static TenorGif parseGif(JSONObject result) {
        if (result == null) return null;

        JSONObject formats = result.optJSONObject("media_formats");
        if (formats == null) return null;

        JSONObject preview = formats.optJSONObject("tinygif");
        JSONObject full = formats.optJSONObject("gif");

        // Either rendition can stand in for the other: a missing preview is only a
        // heavier grid cell, and a missing full size only a lower quality upload.
        if (preview == null) preview = full;
        if (full == null) full = preview;
        if (preview == null) return null;

        String previewUrl = preview.optString("url", "");
        String fullUrl = full.optString("url", "");
        if (previewUrl.isEmpty() || fullUrl.isEmpty()) return null;

        int[] previewDimensions = dimensions(preview);
        int[] fullDimensions = dimensions(full);

        return new TenorGif(
                result.optString("id", ""),
                result.optString("content_description", ""),
                previewUrl, previewDimensions[0], previewDimensions[1],
                fullUrl, fullDimensions[0], fullDimensions[1],
                full.optInt("size", 0),
                result.optString("itemurl", ""));
    }

    /** Reads the {@code dims} pair, defaulting to zero so callers fall back to a square cell. */
    private static int[] dimensions(JSONObject format) {
        JSONArray dims = format.optJSONArray("dims");
        if (dims == null || dims.length() < 2) return new int[]{0, 0};
        return new int[]{dims.optInt(0, 0), dims.optInt(1, 0)};
    }

    /**
     * Issues a request, and on an authentication failure re-fetches the credentials
     * and tries once more.
     *
     * <p>The retry is what keeps the picker working when tenor.com rotates the key
     * its web client uses. It is deliberately not attempted for a user supplied key,
     * which re-fetching cannot repair.
     */
    private static String getWithRetry(UrlFactory factory) throws TenorException {
        TenorWebConfig config = config();

        try {
            return get(factory.create(config), null);
        } catch (TenorException ex) {
            boolean usingUserKey = !Settings.TENOR_API_KEY.get().trim().isEmpty();
            if (usingUserKey || !isAuthenticationFailure(ex)) {
                throw ex;
            }

            Logger.printDebug(() -> "Tenor rejected the cached key, refreshing it");
            invalidateConfig();
            return get(factory.create(config()), null);
        }
    }

    /**
     * Whether a failure looks like a rejected key rather than a network problem.
     * Tenor answers an invalid key with 400 and a revoked one with 403.
     */
    private static boolean isAuthenticationFailure(TenorException ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("HTTP 400") || message.contains("HTTP 403"));
    }

    /**
     * Performs a GET and returns the body as text.
     *
     * @param userAgent User agent to send, or null to leave the default in place.
     */
    private static String get(String url, String userAgent) throws TenorException {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setRequestProperty("Accept", "application/json");
            if (userAgent != null) {
                connection.setRequestProperty("User-Agent", userAgent);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new TenorException("HTTP " + responseCode + " from Tenor");
            }

            try (InputStream stream = connection.getInputStream()) {
                return readAll(stream);
            }
        } catch (IOException ex) {
            throw new TenorException("Could not reach Tenor", ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;

        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > MAXIMUM_RESPONSE_BYTES) {
                throw new IOException("Tenor response exceeded " + MAXIMUM_RESPONSE_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }

        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    // endregion
}
