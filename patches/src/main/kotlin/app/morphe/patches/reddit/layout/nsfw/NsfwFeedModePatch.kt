package app.morphe.patches.reddit.layout.nsfw

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFreeRegister
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/NsfwFeedModePatch;"

private const val DRAWER_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/NsfwDrawerRow;"

@Suppress("unused")
val nsfwFeedModePatch = bytecodePatch(
    name = "NSFW mode",
    description = "Adds an option that hides everything except NSFW (18+) posts from the feed. " +
            "Turned off until it is enabled in Morphe settings. " +
            "Reddit only sends 18+ posts to accounts that have 'Show NSFW content' enabled, " +
            "so the feed is empty without that account setting."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // Filter the posts where the feed page is built, which is the same place
        // 'Hide ads' removes promoted posts from. Both patches insert ahead of the
        // same field write, and chain in whichever order they are applied.
        NsfwListingFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->filterListing(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // region Navigation drawer row

        // Best effort. The drawer is a far more volatile surface than the listing model, so a
        // miss here costs the row and leaves the filter and its settings toggle working, rather
        // than failing the whole patch and leaving the user unable to build at all.
        try {
            // Resolve the click router first and inject it first: a row that cannot respond to
            // taps is worse than no row, so the two hooks go in together or not at all.
            NsfwDrawerItemClickFingerprint.let {
                it.method.apply {
                    // The tapped row lands in the register of the cast that follows the lookup.
                    val castIndex = it.instructionMatches[2].index
                    val rowRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA
                    val free = findFreeRegister(castIndex, rowRegister)

                    addInstructionsWithLabels(
                        castIndex + 1,
                        """
                            invoke-static { v$rowRegister }, $DRAWER_EXTENSION_CLASS->onDrawerRowClicked(Ljava/lang/Object;)Z
                            move-result v$free
                            if-eqz v$free, :not_handled
                            return-void
                            :not_handled
                            nop
                        """
                    )
                }
            }

            NsfwDrawerSectionFingerprint.method.apply {
                val collectionParameter = parameterTypes.indexOf("Ljava/util/Collection;")

                addInstructions(
                    0,
                    """
                        invoke-static/range { p$collectionParameter .. p$collectionParameter }, $DRAWER_EXTENSION_CLASS->addNsfwRow(Ljava/util/Collection;)Ljava/util/Collection;
                        move-result-object p$collectionParameter
                    """
                )
            }

            setExtensionIsPatchIncluded(DRAWER_EXTENSION_CLASS)
        } catch (ex: Exception) {
            Logger.getLogger(this::class.java.name).warning(
                "'NSFW mode' could not add its navigation drawer row: ${ex.message}. " +
                        "Feed filtering and the Morphe settings toggle are unaffected."
            )
        }

        // endregion
    }
}
