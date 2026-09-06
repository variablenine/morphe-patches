# Reddit NSFW mode — APK research notes

Findings from decompiling **Reddit 2026.35.0** (`com.reddit.frontpage`, the newest experimental
target in `COMPATIBILITY_REDDIT`). Recorded so the next session does not have to redo the work.

Method: apkcombo serves the `.xapk` over a signed R2 URL that this environment can reach
(most APK mirrors are blocked by the egress proxy, apkcombo is not). `unzip` the xapk →
`com.reddit.frontpage.apk` → `classes*.dex` (13 files, 97 MB), then jadx 1.5.3
(`--single-class` for one class, or a whole dex at a time; `classes6.dex` holds the feed code).

## Confirmed: the shipped `Listing` hook is correct for 2026.35.0

- `com.reddit.domain.model.listing.Listing<T>` is still a Kotlin data class whose constructor
  writes `children`, `after`, `before`, `adDistance`, `geoFilter`, `hasRecommendations`,
  `uxExperiences`. `NsfwListingFingerprint` (IPUT `children` → IPUT `after` → IPUT `before`)
  still matches.
- `com.reddit.domain.model.Link` declares **`public final boolean getOver18()`** over an
  `over18` field. That is the first accessor `NsfwPostDetector` tries, so detection resolves on
  the first candidate with no fallback needed.

Not yet confirmed on-device: whether the **home feed** is actually built from `Listing` in this
version, or only subreddit/user listings are. That needs a patched install, not a decompile.

## The modern (section-backed) feed carries no NSFW flag

The section/element classes are obfuscated but their `toString` prefixes survive:

- Post sections: `FeedPostSection(linkId=` (`defpackage.wki` in `classes6.dex`),
  plus `ClassicPostSection`, `ChromelessCustomPostSection`, `PostTitleWithThumbnailSection`,
  `TitleWithLinkedPreviewSection`. Ad equivalent: `AdPostSection(linkId=` (what `Hide ads` hooks).
- `FeedPostSection` fields: `linkId, sections, uniqueId, identifier, promoted, recommended,
  removed, isSubredditFeed, isVideo, disabledA11yLabels, cachingMetadata`.
- `PostElement(linkId=` (`defpackage.gy10`) fields: `linkId, feedElements, identifier,
  groupRecommendationContext, gilded, isGildable, removed, crosspostSeedLinkId, dataSourceType,
  cachingMetadata`.

**Neither exposes an NSFW flag**, so the `Hide ads` trick of hooking the section constructor does
not transfer: `promoted` is a section field, `over18` is not. Filtering the modern feed needs the
flag looked up upstream of section construction (by `linkId`, or in the GraphQL post fragment)
rather than read off the section. This is the open problem for Popular/Latest coverage.

## The home feed selector has no "Popular" in this version

`com.reddit.feedslegacy.switcher.*` is unobfuscated:

- `HomePagerScreenTab` is a sealed `Parcelable` with exactly two subclasses: `HomeTab` (id
  `"home"`) and `FollowingTab`.
- `HomePagerScreenPresenter` (`defpackage`-renamed to `.impl.homepager.b`) builds the visible
  tabs in `l(List)`: it maps `HomeTab` → string res `2131955469`/`2131955707`, `FollowingTab` →
  `2131955463`, and calls `zeq.d()` (unreachable) for anything else, producing
  `HomePagerScreenTabUiModel(id, displayName, isBadged, tab)`.
- Popular is not one of these tabs. It lives on its own screen — resources
  `popular_app_bar_title`, `popular_feed_label`, `discover_all_nav_popular_title`.
- `feed_switcher` / `feed_switcher_menu` exist only as analytics event names
  (`com.reddit.data.events.feed_switcher.*`), not as a UI the tabs come from.

So "add an entry next to Popular in the feed dropdown" does not map onto 2026.35.0 as described.
Adding a **third home tab** is tractable — new `HomePagerScreenTab` subclass, inject it into the
list `l()` receives, extend `l()`'s branch, and give the pager a screen for it — but it is a
different, larger change than adding a row to an existing menu, and it needs the target version
pinned first.

## The target UI is the navigation drawer, not a tab row

A screenshot from the user's own install shows the left nav drawer: **Popular**, **Latest**,
then a divider, then *Discover communities*, *Start a community*, then the `Games on Reddit`,
`Recently Visited`, `Favorites` and `Moderating` sections. "The feed dropdown menu" means this
drawer, and "next to Popular" means a new row under Popular/Latest.

This is a surface the fork already reaches. `hideSidebarComponentsPatch` hooks
`CommunityDrawerBuilderFingerprint` — a static method taking a `Collection` of drawer items —
and `HideSidebarComponentsPatch.hideComponents(Collection, HeaderItemInterface)` returns a
replacement collection. Adding a row is the same hook used to *add* rather than *drop*.

Note `HeaderItem` (the enum that patch filters on: ABOUT, COMMUNITIES, FAVORITES,
GAMES_ON_REDDIT, MODERATING, RECENTLY_VISITED, REDDIT_PRO, RESOURCES) covers the *section
headers* only. Popular/Latest are top-level rows built elsewhere;
`com.reddit.screens.drawer.community.CommunityDrawerPresenter` (largely unobfuscated method
names — `handleGenericItemClicked`, `handleCtaItemClicked`, `handlePredefinedItemFavUnfavClicked`,
`loadAboutDrawerItems`) is where they are assembled and where their clicks are routed.
`GenericPredefinedUiModelType` is **not** it — that enum holds only `CUSTOM_FEEDS`.

## Reddit has a first-class MATURE feed

Both feed enums carry a mature entry:

- `com.reddit.feeds.data.FeedType`: `NEWS, HOME, POPULAR, LATEST, MATURE, SUBREDDIT,
  COMMUNITIES, TOPIC, ALL, CUSTOM, SAVED_POSTS, ARENA, GAMES, CLUB, CATEGORY,
  CONTRIBUTION_COPILOT, DYNAMIC, INTERESTS, FOLLOWING`
- `com.reddit.listing.common.ListingType`: `HOME, POPULAR, LATEST, MATURE, ...`

`MATURE` is live, not dead code: it appears in the generated `WhenMappings` ordinal tables
(`defpackage.api`, `defpackage.bpi`) beside HOME/POPULAR/LATEST, `defpackage.zfi` branches on
`feedType == FeedType.MATURE` for post navigation, and `com.reddit.feeds.impl.ui.h` treats
`{POPULAR, MATURE}` as one set when deciding overflow-menu behaviour.

That reframes the feature. Instead of client-side filtering of the home feed, NSFW mode could be
a drawer row that opens Reddit's **own** mature feed — the app already knows how to render it,
and the result is a real paginated feed rather than a thinned-out Home. Open questions before
committing to that: which navigator builds a feed screen from a `FeedType` (several
`defpackage` classes take a `FeedType` parameter), and whether the mature listing endpoint still
returns anything server-side for an account with 18+ browsing enabled.
