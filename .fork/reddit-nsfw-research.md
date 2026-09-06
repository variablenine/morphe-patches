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

## How the drawer's Popular / Latest rows are actually built

`CommunityDrawerPresenter` is `com.reddit.screens.drawer.community.c` in `classes6.dex`
(the class keeps its name; its members are obfuscated). Drawer rows are plain constructor calls
carrying **resource ids**, which makes them cheap to imitate:

```java
new qt6(boolean, int titleRes, int iconRes, long uniqueId)   // simple row
new u7k(long uniqueId, int titleRes, int iconRes, boolean, GenericPredefinedUiModelType, int)
```

The Home/Popular/Watch/Latest rows come from one merged synthetic `Function0` in `c`, switched on
a lambda index — each is a lazily built field of the presenter:

| case | titleRes | resolves to | iconRes |
|---|---|---|---|
| 1 | 2131956125 | (home) | 2131231656 |
| 2 | 2131959179 | `popular_feed_label` | 2131231831 |
| 3 | 2131958780 | (watch) | 2131231761 |
| default | 2131956400 | `latest_feed_label` | 2131231755 |

Other rows, for reference: `communities_cta_title` + `icon_communities` (Discover communities),
`label_custom_feeds`, `label_start_a_community`, `label_mail`, `label_mod_mail`,
`label_manage_moderated_communities`.

Rows reach the visible list through two static helpers, both already in fingerprint range:

- `hh3.q(ListBuilder, Collection, HeaderItemUiModel, PaginationType, boolean, int)` — a whole
  section. This is what `CommunityDrawerBuilderFingerprint` matches and what
  `HideSidebarComponentsPatch.hideComponents` already intercepts.
- `hh3.r(ListBuilder, item)` — a single row.

`HeaderItemUiModel` is `defpackage.ion` (`uniqueId`, `HeaderItem`, boolean, String, boolean).

Clicks land in `CommunityDrawerPresenter.handleGenericItemClicked`, which does **identity**
comparisons (`Intrinsics.areEqual(item, this.B1)` and so on) against those presenter fields, then
calls a navigator interface (`defpackage.hox`: `.d()` create community, `.e()` custom feeds,
`.k()` login, `.l()` mod queue, `.m()`, `.r()` recap). An injected row will match none of them and
fall through, so recognising it is better done by **comparing its title resource id** to the one
Morphe adds than by identity.

### Sketch for the NSFW row

1. Add a Morphe string, take its generated id via `resourceMappingPatch`, and reuse Reddit's own
   `2131231831` (the Popular icon) so no drawable has to be shipped.
2. Hook `hh3.r` (or the presenter method that adds the Latest row) and append
   `new qt6(false, <morphe title id>, 2131231831, <uniqueId>)`. Constructor descriptor is
   `(ZIIJ)V`.
3. Hook `handleGenericItemClicked`, match on the title id, and run the NSFW action.

### Still open: how to navigate to the MATURE feed

Step 3's action is the unresolved piece. `hox`'s methods are the drawer's navigation surface but
the interface is split across dex files and decompiles empty in `classes4.dex`, so the call that
opens a feed for a given `FeedType` has not been pinned down yet. Several `defpackage` classes
take a `FeedType` parameter (`w1o, z570, nug, xoi, gd3, t570, p110, i8h0, n9i, omi`) and are the
place to look next.

Strongest evidence yet that MATURE is renderable client-side: `defpackage.vh40.L(FeedType)`
maps **every** feed type to a `com.reddit.qsf.screens.QsfScreenType`, and `MATURE` has its own
`MATURE_FEED` entry sitting in the same complete `switch` as `HOME_FEED`, `POPULAR_FEED`,
`LATEST_FEED` and `ALL_FEED`. It is not a leftover constant.

Two navigation helpers the drawer already uses look like an easier action than the internal
navigator, since both take a plain destination rather than an obfuscated screen type:
`defpackage.hrx.d(..., Activity, Uri, ..., int)` (used for the Reddit Rules / Privacy Policy /
User Agreement rows) and `com.reddit.deeplink.a.a(deeplinkNav, Context, String, int)` (used for
link rows). No `/mature`-style route showed up in the deeplink path strings, though, so whether
either can address the mature feed is still unproven.

One caution found along the way: `QsfScreenType.MATURE_FEED` exists alongside
`RESOLVER_GATE_PRESENTATION_MATURE_DESTINATION` and
`CHECKPOINT_GATE_PRESENTATION_MATURE_DESTINATION`, which look like age-verification gates in front
of a mature destination. So even if the navigation is wired up, the feed may demand verification
before it renders — one more reason the fallback path has to exist and has to say which branch it
took.


## Settled: the MATURE feed is not reachable, and the row toggles the filter

Every feed the app can show has its own screen class — `HomeFeedScreen`, `PopularFeedScreen`,
`LatestFeedScreen`, `AllFeedScreen`, `NewsFeedScreen`, `GamesFeedScreen`, `FollowingFeedScreen`,
`HistoryFeedScreen`, `SavedPostsFeedScreen`, `InterestsFeedScreen`. **There is no
`MatureFeedScreen`**, and nothing constructs a feed for `FeedType.MATURE`.

The drawer's navigator confirms it from the other direction: each feed row calls its own no-arg
method (`hox.p()` Popular, `hox.n()` Watch, `hox.h()` Latest). There is no "open feed by type"
call to pass MATURE to, and no mature method to call. Several feed-behaviour sets
(`d670`, `RedditFeedViewModel`) list the feed types they apply to and simply omit MATURE.

So the MATURE constants are exhaustiveness artifacts: Kotlin `when` over an enum must cover every
entry, which is exactly why MATURE shows up in `api`/`bpi`/`vh40` mapping tables and nowhere that
builds UI. `MATURE_DESTINATION` sits among GraphQL enum values, not screens; the only real
`Mature*` screens are age-gating and settings (`MatureContentFlowHostScreen`,
`MatureContentSettingsScreen`, `MatureContentBottomSheetScreen`, `CommunityTypeMatureSettingsScreen`).

The drawer row therefore toggles NSFW mode's feed filter. Reaching a real mature feed would mean
building a feed screen inside the patch, which is out of proportion to the feature.
