/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Downloads and displays animated GIFs in the picker grid.
 *
 * <p>Encoded bytes are cached rather than decoded drawables: an
 * {@link AnimatedImageDrawable} holds animation state and cannot be attached to
 * more than one view, so a shared instance would stall whenever the same GIF
 * appeared twice. Re-decoding from cached bytes costs little and keeps each cell
 * independent.
 *
 * <p>Loads are tagged with their url and checked again before display, so a view
 * that has been rebound to a different GIF while its download was in flight is
 * not overwritten with the stale one.
 */
public final class GifImageLoader {

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 20_000;

    /** Grid previews are tens of kilobytes; anything far larger is not a preview. */
    private static final int MAXIMUM_PREVIEW_BYTES = 8 * 1024 * 1024;

    /** Roughly two screens of previews, so scrolling back up does not re-download. */
    private static final int CACHE_ENTRIES = 120;

    private static final Map<String, byte[]> CACHE =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(CACHE_ENTRIES));

    /**
     * Several downloads run at once so the grid fills in roughly in view order,
     * but not so many that they compete with the search request.
     */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "morphe-tenor-image");
            thread.setDaemon(true);
            // Below default so decoding never competes with the UI thread.
            thread.setPriority(Thread.MIN_PRIORITY + 2);
            return thread;
        }
    });

    private GifImageLoader() {
    }

    /**
     * Loads {@code url} into {@code view}, animating it once decoded.
     *
     * <p>Safe to call repeatedly on a recycled view: the most recent call wins.
     */
    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) return;

        // The single argument tag is used rather than a keyed one: keyed tags must be
        // application resource ids, and the extension has no resources of its own.
        // These views are created here and read by nothing else.
        view.setTag(url);

        byte[] cached = CACHE.get(url);
        if (cached != null) {
            display(view, url, cached);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                // No staleness check here: touching the view off the main thread is
                // what display() exists to avoid, and a download that turns out to be
                // unwanted still populates the cache for when it scrolls back in.
                byte[] bytes = fetch(url, MAXIMUM_PREVIEW_BYTES);
                CACHE.put(url, bytes);
                Utils.runOnMainThread(() -> display(view, url, bytes));
            } catch (IOException ex) {
                // A single failed preview is not worth an error dialog; the cell
                // simply stays blank.
                Logger.printDebug(() -> "Could not load Tenor preview: " + url + " " + ex.getMessage());
            } catch (Exception ex) {
                Logger.printException(() -> "Tenor preview load failure", ex);
            }
        });
    }

    /** Decodes and attaches, on the main thread, if the view still wants this url. */
    private static void display(ImageView view, String url, byte[] bytes) {
        try {
            // The view may have been rebound to another GIF while this was in flight.
            if (!url.equals(view.getTag())) return;

            Drawable drawable = decode(bytes);
            view.setImageDrawable(drawable);

            if (drawable instanceof AnimatedImageDrawable animated) {
                animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                animated.start();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not decode Tenor preview: " + url + " " + ex.getMessage());
        }
    }

    /**
     * Decodes GIF bytes into a drawable.
     *
     * <p>{@link ImageDecoder} produces an {@link AnimatedImageDrawable} for animated
     * sources and a plain bitmap drawable for static ones, so both are handled without
     * having to sniff the content first.
     */
    private static Drawable decode(byte[] bytes) throws IOException {
        return ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                (decoder, info, source) -> {
                    // Software allocation: hardware bitmaps cannot be drawn into the
                    // dialog's software layers on every device.
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
    }

    /**
     * Downloads a url in full.
     *
     * <p>Blocking, and used both for grid previews and for fetching the full size
     * GIF that gets uploaded once one is picked.
     *
     * @param maximumBytes Refuse responses larger than this.
     */
    public static byte[] fetch(String url, int maximumBytes) throws IOException {
        Utils.verifyOffMainThread();

        byte[] cached = CACHE.get(url);
        if (cached != null) return cached;

        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);

            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " fetching " + url);
            }

            // Reject on the declared length when there is one, so an oversized
            // download is refused before any of it is read.
            final int contentLength = connection.getContentLength();
            if (contentLength > maximumBytes) {
                throw new IOException("Response of " + contentLength + " bytes exceeds " + maximumBytes);
            }

            try (InputStream stream = connection.getInputStream()) {
                return readAll(stream, maximumBytes);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream stream, int maximumBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;

        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > maximumBytes) {
                throw new IOException("Response exceeded " + maximumBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
        }

        return buffer.toByteArray();
    }

    /** Drops every cached preview. Called when the picker closes. */
    public static void clearCache() {
        CACHE.clear();
    }
}
