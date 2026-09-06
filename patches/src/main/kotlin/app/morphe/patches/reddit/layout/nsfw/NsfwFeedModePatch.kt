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
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->filterListing(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // region Home feed

        // The one hook that reaches the home feed. Filters the GraphQL response's edges before
        // they become feed elements, which is the last point a post's NSFW state is visible:
        // the elements carry only a link id, a unique id, promoted and an identifier.
        try {
            NsfwHomeFeedPageFingerprint.method.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->filterHomeFeedResponse(Ljava/lang/Object;)V
                """
            )
        } catch (ex: Exception) {
            Logger.getLogger(this::class.java.name).warning(
                "'NSFW mode' could not hook the home feed page builder: ${ex.message}"
            )
        }

        // endregion

        // region Modern feed

        // The listing hook above is the cache path: on device it filters a listing to nothing
        // while the rendered feed carries on unchanged. Posts reach the screen through this
        // mapper, so this is the hook that actually filters what is on screen.
        try {
            NsfwFeedElementMapperFingerprint.method.apply {
                val listParameter = parameterTypes.indexOfFirst { it == "Ljava/util/List;" }
                // Instance method, so parameter n is register p(n + 1).
                val register = "p${listParameter + 1}"

                addInstructions(
                    0,
                    """
                        invoke-static/range { $register .. $register }, $EXTENSION_CLASS->filterFeedLinks(Ljava/util/List;)Ljava/util/List;
                        move-result-object $register
                    """
                )
            }
        } catch (ex: Exception) {
            Logger.getLogger(this::class.java.name).warning(
                "'NSFW mode' could not hook the feed element mapper: ${ex.message}"
            )
        }

        // endregion

        // region Navigation drawer row

        // Best effort, and the two hooks are independent on purpose: wiring the row to the click
        // router meant one bad fingerprint cost both, which is exactly what happened on 2026.14.0.
        try {
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
                "'NSFW mode' could not add its navigation drawer row: ${ex.message}"
            )
        }

        try {
            NsfwDrawerItemClickFingerprint.method.apply {
                val free = findFreeRegister(0)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p0 .. p1 }, $DRAWER_EXTENSION_CLASS->onDrawerActionDispatched(Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v$free
                        if-eqz v$free, :not_handled
                        return-void
                        :not_handled
                        nop
                    """
                )
            }
        } catch (ex: Exception) {
            Logger.getLogger(this::class.java.name).warning(
                "'NSFW mode' could not hook the drawer click router: ${ex.message}"
            )
        }

        // endregion
    }
}
