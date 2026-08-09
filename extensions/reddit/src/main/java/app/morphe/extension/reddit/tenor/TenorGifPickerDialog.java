/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.tenor;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The Tenor GIF picker.
 *
 * <p>Laid out in code rather than XML: the extension has no resources of its own,
 * and building the views directly is how the settings screen already does it.
 *
 * <p>Structure, following the shape Discord's picker settled on: a search field
 * pinned to the top, type ahead suggestions beneath it, and a staggered two column
 * grid of animating GIFs that pages in as it is scrolled. With no query entered it
 * shows Tenor's featured feed behind a row of category tiles.
 */
@SuppressWarnings("deprecation")
public final class TenorGifPickerDialog extends DialogFragment {

    /** Notified once, with the GIF the user chose. */
    public interface Listener {
        void onGifPicked(TenorGif gif);
    }

    /** Wait after the last keystroke before searching, so typing does not issue a request per letter. */
    private static final int SEARCH_DEBOUNCE_MILLISECONDS = 350;

    /** Start loading the next page once the scroll is within this many screens of the end. */
    private static final float PREFETCH_SCREENS = 1.5f;

    private static final int COLUMN_SPACING_DP = 8;
    private static final int WIDE_LAYOUT_MINIMUM_DP = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Listener listener;

    // Views retained for state changes.
    private EditText searchField;
    private LinearLayout suggestionRow;
    private HorizontalScrollView suggestionScroll;
    private ScrollView contentScroll;
    private LinearLayout columnContainer;
    private LinearLayout[] columns;
    private ProgressBar progressBar;
    private TextView statusText;

    private MasonryColumns masonry;
    private int columnWidth;

    /** Query currently displayed. Empty means the featured feed. */
    private String query = "";

    /** Cursor for the next page, empty when exhausted. */
    private String nextCursor = "";

    private boolean loading;
    private boolean exhausted;

    /**
     * Incremented whenever the displayed query changes. Responses carrying a stale
     * token are discarded, so a slow first search cannot land on top of a later one.
     */
    private int requestToken;

    private int backgroundColor;
    private int foregroundColor;
    private int surfaceColor;

    /**
     * Opens the picker.
     *
     * @param listener Notified with the chosen GIF. Not called if the picker is dismissed.
     */
    public static void show(Activity activity, Listener listener) {
        try {
            TenorGifPickerDialog dialog = new TenorGifPickerDialog();
            dialog.listener = listener;
            dialog.show(activity.getFragmentManager(), "morphe_tenor_picker");
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the Tenor picker", ex);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_NoActionBar);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Activity activity = getActivity();

        backgroundColor = Utils.getAppBackgroundColor();
        foregroundColor = Utils.getAppForegroundColor();
        surfaceColor = blend(backgroundColor, foregroundColor, 0.10f);

        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setStatusBarColor(backgroundColor);
            }
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backgroundColor);
        root.setFitsSystemWindows(true);
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        root.addView(createHeader(activity));
        root.addView(createSuggestionRow(activity));
        root.addView(createContent(activity), new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(createFooter(activity));

        // The grid is sized from the window width, which is match parent here.
        int totalWidth = getResources().getDisplayMetrics().widthPixels;
        int columnCount = totalWidth >= dp(WIDE_LAYOUT_MINIMUM_DP) ? 3 : 2;
        int spacing = dp(COLUMN_SPACING_DP);
        columnWidth = (totalWidth - spacing * (columnCount + 1)) / columnCount;

        buildColumns(activity, columnCount, spacing);

        loadFirstPage();
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        // Previews are only useful while the picker is open, and they are the
        // largest thing this feature holds in memory.
        GifImageLoader.clearCache();
    }

    // region Layout

    private View createHeader(Activity activity) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(8));

        searchField = new EditText(activity);
        searchField.setHint(str("morphe_tenor_search_hint"));
        searchField.setHintTextColor(blend(backgroundColor, foregroundColor, 0.45f));
        searchField.setTextColor(foregroundColor);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setBackground(roundedRectangle(surfaceColor, dp(20)));
        searchField.setPadding(dp(16), dp(10), dp(16), dp(10));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                scheduleSearch(editable.toString());
            }
        });
        header.addView(searchField, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(foregroundColor);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(14), dp(6), dp(4), dp(6));
        close.setContentDescription(str("morphe_tenor_close"));
        close.setOnClickListener(view -> dismiss());
        header.addView(close);

        return header;
    }

    private View createSuggestionRow(Activity activity) {
        suggestionScroll = new HorizontalScrollView(activity);
        suggestionScroll.setHorizontalScrollBarEnabled(false);
        suggestionScroll.setVisibility(View.GONE);
        suggestionScroll.setPadding(dp(12), 0, dp(12), dp(4));

        suggestionRow = new LinearLayout(activity);
        suggestionRow.setOrientation(LinearLayout.HORIZONTAL);
        suggestionScroll.addView(suggestionRow, new FrameLayout.LayoutParams(-2, -2));

        return suggestionScroll;
    }

    private View createContent(Activity activity) {
        FrameLayout frame = new FrameLayout(activity);

        contentScroll = new ScrollView(activity);
        contentScroll.setFillViewport(true);
        contentScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> maybeLoadNextPage());

        columnContainer = new LinearLayout(activity);
        columnContainer.setOrientation(LinearLayout.HORIZONTAL);
        contentScroll.addView(columnContainer, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(contentScroll, new FrameLayout.LayoutParams(-1, -1));

        progressBar = new ProgressBar(activity);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-2, -2);
        progressParams.gravity = Gravity.CENTER;
        frame.addView(progressBar, progressParams);

        statusText = new TextView(activity);
        statusText.setTextColor(blend(backgroundColor, foregroundColor, 0.6f));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(24), dp(24), dp(24), dp(24));
        statusText.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2);
        statusParams.gravity = Gravity.CENTER;
        frame.addView(statusText, statusParams);

        return frame;
    }

    /**
     * Tenor's terms require visible attribution wherever its results are shown.
     */
    private View createFooter(Activity activity) {
        TextView footer = new TextView(activity);
        footer.setText(str("morphe_tenor_powered_by"));
        footer.setTextColor(blend(backgroundColor, foregroundColor, 0.45f));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(6), dp(8), dp(8));
        return footer;
    }

    private void buildColumns(Activity activity, int columnCount, int spacing) {
        columns = new LinearLayout[columnCount];
        masonry = new MasonryColumns(columnCount);

        columnContainer.setPadding(spacing, 0, spacing, spacing);
        for (int i = 0; i < columnCount; i++) {
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(columnWidth, -2);
            if (i > 0) params.leftMargin = spacing;
            columnContainer.addView(column, params);

            columns[i] = column;
        }
    }

    // endregion

    // region Loading

    /** Restarts the grid for the current {@link #query}. */
    private void loadFirstPage() {
        final int token = ++requestToken;

        nextCursor = "";
        exhausted = false;
        loading = true;

        clearGrid();
        setStatus(null);
        progressBar.setVisibility(View.VISIBLE);

        final String requestedQuery = query;
        Utils.runOnBackgroundThread(() -> {
            try {
                // Category tiles only make sense on the landing state, and are
                // fetched alongside the feed so the grid appears in one pass.
                List<TenorCategory> categories = requestedQuery.isEmpty()
                        ? TenorApiClient.categories()
                        : null;

                TenorApiClient.TenorPage page = TenorApiClient.search(requestedQuery, null);

                Utils.runOnMainThread(() -> {
                    if (token != requestToken || !isAdded()) return;

                    progressBar.setVisibility(View.GONE);
                    loading = false;

                    if (categories != null && !categories.isEmpty()) {
                        addCategories(categories);
                    }

                    if (page.results.isEmpty() && (categories == null || categories.isEmpty())) {
                        setStatus(str("morphe_tenor_no_results"));
                        return;
                    }

                    addGifs(page.results);
                    nextCursor = page.next;
                    exhausted = !page.hasMore();

                    // A short first page leaves the scroll view unscrollable, so
                    // the scroll listener would never ask for more.
                    contentScroll.post(this::maybeLoadNextPage);
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Tenor request failed", ex);
                Utils.runOnMainThread(() -> {
                    if (token != requestToken || !isAdded()) return;
                    progressBar.setVisibility(View.GONE);
                    loading = false;
                    setStatus(str("morphe_tenor_load_failed"));
                });
            }
        });
    }

    /** Appends the following page, if the scroll is close enough to the end. */
    private void maybeLoadNextPage() {
        if (loading || exhausted || nextCursor.isEmpty() || !isAdded()) return;

        int scrolled = contentScroll.getScrollY() + contentScroll.getHeight();
        int total = columnContainer.getHeight();
        if (total - scrolled > contentScroll.getHeight() * PREFETCH_SCREENS) return;

        final int token = requestToken;
        final String requestedQuery = query;
        final String cursor = nextCursor;
        loading = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                TenorApiClient.TenorPage page = TenorApiClient.search(requestedQuery, cursor);

                Utils.runOnMainThread(() -> {
                    if (token != requestToken || !isAdded()) return;

                    loading = false;
                    addGifs(page.results);
                    nextCursor = page.next;
                    exhausted = !page.hasMore() || page.results.isEmpty();
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Tenor pagination failed", ex);
                Utils.runOnMainThread(() -> {
                    if (token != requestToken || !isAdded()) return;
                    loading = false;
                    // Stop paging rather than retry in a loop against a failing endpoint.
                    exhausted = true;
                });
            }
        });
    }

    /** Debounces keystrokes into one search, and refreshes the suggestion row. */
    private void scheduleSearch(String text) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            String trimmed = text.trim();
            if (trimmed.equals(query)) return;

            query = trimmed;
            loadFirstPage();
            loadSuggestions(trimmed);
        }, SEARCH_DEBOUNCE_MILLISECONDS);
    }

    private void loadSuggestions(String partial) {
        if (partial.isEmpty()) {
            suggestionScroll.setVisibility(View.GONE);
            return;
        }

        final int token = requestToken;
        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> suggestions = TenorApiClient.autocomplete(partial);

                Utils.runOnMainThread(() -> {
                    if (token != requestToken || !isAdded()) return;
                    showSuggestions(suggestions);
                });
            } catch (Exception ex) {
                // Suggestions are an accessory; failing to load them is not worth surfacing.
                Logger.printDebug(() -> "Tenor autocomplete failed: " + ex.getMessage());
            }
        });
    }

    // endregion

    // region Grid population

    private void clearGrid() {
        for (LinearLayout column : columns) {
            column.removeAllViews();
        }
        masonry.reset();
    }

    private void setStatus(String message) {
        if (message == null) {
            statusText.setVisibility(View.GONE);
        } else {
            statusText.setText(message);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private void addGifs(List<TenorGif> gifs) {
        Activity activity = getActivity();
        if (activity == null) return;

        int spacing = dp(COLUMN_SPACING_DP);

        for (TenorGif gif : gifs) {
            int height = gif.scaledHeight(columnWidth);

            ImageView cell = new ImageView(activity);
            cell.setScaleType(ImageView.ScaleType.FIT_XY);
            cell.setBackground(roundedRectangle(surfaceColor, dp(8)));
            cell.setClipToOutline(true);
            cell.setContentDescription(gif.description);
            cell.setOnClickListener(view -> pick(gif));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(columnWidth, height);
            params.bottomMargin = spacing;

            columns[masonry.place(height + spacing)].addView(cell, params);
            GifImageLoader.load(cell, gif.previewUrl);
        }
    }

    /**
     * Adds the category tiles above the featured feed.
     *
     * <p>Tiles are placed through the same masonry accounting as GIFs so the feed
     * that follows them stays level.
     */
    private void addCategories(List<TenorCategory> categories) {
        Activity activity = getActivity();
        if (activity == null) return;

        int spacing = dp(COLUMN_SPACING_DP);
        int height = (int) (columnWidth * 0.6f);

        for (TenorCategory category : categories) {
            FrameLayout tile = new FrameLayout(activity);
            tile.setBackground(roundedRectangle(surfaceColor, dp(8)));
            tile.setClipToOutline(true);
            tile.setOnClickListener(view -> {
                // Runs the category as an ordinary search, which also puts the term
                // in the field so the user can edit it.
                searchField.setText(category.searchTerm);
                searchField.setSelection(category.searchTerm.length());
            });

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));

            // Scrim, so the label stays readable over a bright GIF.
            View scrim = new View(activity);
            scrim.setBackgroundColor(Color.argb(110, 0, 0, 0));
            tile.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            TextView label = new TextView(activity);
            label.setText(category.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-1, -2);
            labelParams.gravity = Gravity.CENTER;
            tile.addView(label, labelParams);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(columnWidth, height);
            params.bottomMargin = spacing;

            columns[masonry.place(height + spacing)].addView(tile, params);
            GifImageLoader.load(image, category.imageUrl);
        }
    }

    private void showSuggestions(List<String> suggestions) {
        suggestionRow.removeAllViews();

        if (suggestions.isEmpty()) {
            suggestionScroll.setVisibility(View.GONE);
            return;
        }

        Activity activity = getActivity();
        if (activity == null) return;

        for (String suggestion : suggestions) {
            TextView chip = new TextView(activity);
            chip.setText(suggestion);
            chip.setTextColor(foregroundColor);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setBackground(roundedRectangle(surfaceColor, dp(14)));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(view -> {
                searchField.setText(suggestion);
                searchField.setSelection(suggestion.length());
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = dp(6);
            suggestionRow.addView(chip, params);
        }

        suggestionScroll.setVisibility(View.VISIBLE);
        suggestionScroll.scrollTo(0, 0);
    }

    private void pick(TenorGif gif) {
        try {
            if (listener != null) {
                listener.onGifPicked(gif);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Tenor picker listener failure", ex);
        } finally {
            dismiss();
        }
    }

    // endregion

    // region Drawing helpers

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private static GradientDrawable roundedRectangle(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /**
     * Mixes {@code overlay} into {@code base}, used to derive surface and muted text
     * colors from the app's own two theme colors. Doing it arithmetically keeps the
     * picker correct in both light and dark mode without reading Reddit's theme.
     *
     * @param amount Proportion of {@code overlay}, from zero to one.
     */
    private static int blend(int base, int overlay, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                (int) (Color.red(base) * inverse + Color.red(overlay) * amount),
                (int) (Color.green(base) * inverse + Color.green(overlay) * amount),
                (int) (Color.blue(base) * inverse + Color.blue(overlay) * amount));
    }

    // endregion
}
