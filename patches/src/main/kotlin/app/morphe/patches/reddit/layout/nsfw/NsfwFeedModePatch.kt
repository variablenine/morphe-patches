package app.morphe.patches.reddit.layout.nsfw

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/NsfwFeedModePatch;"

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
    }
}
