/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds Tenor v2 request URLs.
 *
 * <p>Pure string handling with no Android or network dependencies, so the query
 * shapes can be asserted with a plain JDK. Every Tenor URL the picker issues is
 * built here; nothing else in the package concatenates a query string.
 *
 * <p>The endpoints are the documented v2 API. What is undocumented is only where
 * the key comes from (see {@link TenorWebConfig}), which means the request and
 * response shapes below stay valid even if the key source is later swapped for a
 * user supplied one.
 */
public final class TenorRequestBuilder {

    /**
     * Renditions requested from Tenor. Restricting this keeps responses small:
     * unfiltered results carry a dozen formats per GIF that are never used.
     *
     * <ul>
     *   <li>{@code tinygif} - grid preview.
     *   <li>{@code gif} - what gets uploaded to Reddit when picked.
     * </ul>
     */
    public static final String MEDIA_FILTER = "tinygif,gif";

    /** Results per page. Matches what the picker can render before the user scrolls. */
    public static final int PAGE_SIZE = 30;

    private TenorRequestBuilder() {
    }

    /**
     * Percent encodes a query parameter value.
     *
     * @return The encoded value, or the original string if the JVM somehow lacks UTF-8.
     */
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            // Cannot happen: UTF-8 is required of every JVM.
            return value;
        }
    }

    /**
     * Joins a base url, a path and query parameters into a request url.
     *
     * <p>Parameters with a null or empty value are dropped, so callers can pass an
     * absent pagination cursor without branching.
     */
    static String build(String baseUrl, String path, Map<String, String> parameters) {
        StringBuilder builder = new StringBuilder(baseUrl);
        builder.append(path);

        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            String value = parameter.getValue();
            if (value == null || value.isEmpty()) continue;

            builder.append(first ? '?' : '&');
            builder.append(parameter.getKey()).append('=').append(encode(value));
            first = false;
        }

        return builder.toString();
    }

    /**
     * Parameters every endpoint needs: the key pair identifying the caller, the
     * locale, and the content filter.
     */
    private static Map<String, String> commonParameters(TenorWebConfig config, String contentFilter) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("key", config.apiKey);
        parameters.put("client_key", config.clientKey);
        parameters.put("contentfilter", contentFilter);
        return parameters;
    }

    /**
     * Search for GIFs matching {@code query}.
     *
     * @param position Pagination cursor from the previous response, or null for the first page.
     */
    public static String search(TenorWebConfig config, String query,
                                String contentFilter, String position) {
        Map<String, String> parameters = commonParameters(config, contentFilter);
        parameters.put("q", query);
        parameters.put("limit", String.valueOf(PAGE_SIZE));
        parameters.put("media_filter", MEDIA_FILTER);
        parameters.put("pos", position);
        return build(config.apiUrl, "/search", parameters);
    }

    /**
     * The default feed shown before the user types anything.
     *
     * @param position Pagination cursor from the previous response, or null for the first page.
     */
    public static String featured(TenorWebConfig config, String contentFilter, String position) {
        Map<String, String> parameters = commonParameters(config, contentFilter);
        parameters.put("limit", String.valueOf(PAGE_SIZE));
        parameters.put("media_filter", MEDIA_FILTER);
        parameters.put("pos", position);
        return build(config.apiUrl, "/featured", parameters);
    }

    /** The category tiles shown alongside the default feed. */
    public static String categories(TenorWebConfig config, String contentFilter) {
        Map<String, String> parameters = commonParameters(config, contentFilter);
        parameters.put("type", "featured");
        return build(config.apiUrl, "/categories", parameters);
    }

    /** Type ahead completions for a partially typed query. */
    public static String autocomplete(TenorWebConfig config, String query, String contentFilter) {
        Map<String, String> parameters = commonParameters(config, contentFilter);
        parameters.put("q", query);
        parameters.put("limit", "8");
        return build(config.apiUrl, "/autocomplete", parameters);
    }
}
