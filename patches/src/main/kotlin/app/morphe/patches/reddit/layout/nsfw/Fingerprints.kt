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
 * The drawer's click router. Reddit renamed the enclosing class and method, but kept the name of
 * the lambda class this method instantiates, which makes a stable anchor.
 *
 * The tapped row is not a parameter: it is looked up from a map and cast, so the match walks to
 * that cast and the patch reads the row from the register it lands in.
 */
internal object NsfwDrawerItemClickFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(
            name = "get",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(
            Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        opcode(
            Opcode.CHECK_CAST,
            location = MatchAfterImmediately()
        ),
        newInstance(
            type = $$"Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter$handleGenericItemClicked$1;"
        )
    )
)
