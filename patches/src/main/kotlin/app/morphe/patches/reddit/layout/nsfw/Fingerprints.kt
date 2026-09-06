package app.morphe.patches.reddit.layout.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.parametersMatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * The constructor of the model a feed page is deserialized into, matched on the writes of its
 * own fields. The first match is the write of the post list, which is what NSFW mode filters.
 *
 * This deliberately does not reuse the identical fingerprint of the 'Hide ads' patch: a
 * [Fingerprint] caches its match, so a shared instance would hand the second patch to run
 * instruction indices that the first patch had already shifted. Two separate instances each
 * match the method as it stands when their own patch executes, so the two filters chain
 * safely no matter which order the patches are applied in.
 */
internal object NsfwListingFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/listing/Listing;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            smali = "Lcom/reddit/domain/model/listing/Listing;->children:Ljava/util/List;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            smali = "Lcom/reddit/domain/model/listing/Listing;->after:Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            smali = "Lcom/reddit/domain/model/listing/Listing;->before:Ljava/lang/String;"
        )
    )
)

/**
 * The static helper that turns one drawer section into rows. Deliberately a separate instance
 * from the identical fingerprint in the 'Hide sidebar components' patch, for the same reason
 * [NsfwListingFingerprint] is separate from the ads one: a shared instance would hand the second
 * patch to run indices the first had already shifted. Both inject at the head of this method and
 * chain in either order, one dropping sections and the other appending a row.
 */
private object DrawerSectionBuilderParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = $$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;",
    parameters = listOf("Lcom/reddit/screens/drawer/community/HeaderItem;"),
    filters = listOf(
        methodCall("Ljava/lang/Enum;->ordinal()I"),
        fieldAccess(
            $$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;->" +
                    $$"ABOUT:Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;"
        )
    )
)

internal object NsfwDrawerSectionFingerprint : Fingerprint(
    classFingerprint = DrawerSectionBuilderParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        methodCall("Ljava/util/Collection;->isEmpty()Z"),
    ),
    custom = { method, _ ->
        parametersMatch(
            method.parameters,
            listOf(
                "L",
                "Ljava/util/List;",
                "Ljava/util/Collection;",
                "L",
                "L",
                "Z",
                "I"
            )
        ) || parametersMatch( // 2026.12.0+
            method.parameters,
            listOf(
                "Ljava/util/List;",
                "Ljava/util/Collection;",
                "L",
                "L",
                "Z",
                "I"
            )
        )
    }
)

/**
 * Where the modern feed turns a listing of posts into feed elements.
 *
 * This is the hook that matters. `Listing` carries `Link` objects with their `over18` flag, but
 * on-device testing showed emptying `Listing.children` leaves the rendered feed untouched: that
 * model is the cache path (Reddit's own Room schema calls its tables `listing` and `link`).
 * Posts reach the screen through this mapper instead, whatever fetched them, and its list really
 * is `List<Link>` - the method body casts each element to `com.reddit.domain.model.Link`.
 *
 * Anchored on the suspend continuation class, whose name Reddit does not obfuscate, so the match
 * does not depend on the enclosing class or method keeping their names.
 */
internal object NsfwFeedElementMapperFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance(
            type = $$"Lcom/reddit/feeds/impl/data/mapper/link/RedditListingFeedElementMapper$getFeedElements$1;"
        )
    ),
    custom = { method, _ ->
        method.parameters.any { it.startsWith("Ljava/util/List;") }
    }
)

/**
 * The drawer's click router.
 *
 * The previous anchor here assumed the tapped row was fetched from a map and cast, which is how
 * a later app version does it. On 2026.14.0 the router instead receives an *action* carrying an
 * index and reads the row out of its own list, so nothing matched and the row was never added
 * either - the two hooks used to be applied together.
 *
 * Anchoring on the lambda class Reddit leaves unobfuscated avoids guessing at either shape; the
 * extension resolves the row from the action reflectively and only acts when it finds its own
 * title resource id, so a wrong guess is inert rather than harmful.
 */
internal object NsfwDrawerItemClickFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        newInstance(
            type = $$"Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter$handleGenericItemClicked$1;"
        )
    )
)
