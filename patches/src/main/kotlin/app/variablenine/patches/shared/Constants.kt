package app.variablenine.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * YouTube compatibility. Keep [AppTarget.version] pinned to the version Morphe currently
     * recommends (the version you built and tested against); bump it when Morphe bumps its target.
     */
    val COMPATIBILITY_YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000,
        targets = listOf(
            // TODO: set this to the exact YouTube version you confirmed working with Morphe.
            AppTarget(
                version = null,
                isExperimental = true,
            ),
        ),
    )
}
