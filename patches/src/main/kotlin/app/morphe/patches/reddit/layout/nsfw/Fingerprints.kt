package app.morphe.patches.reddit.layout.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
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
