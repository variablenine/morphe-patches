group = "app.variablenine"

patches {
    about {
        name = "variablenine Patches"
        description = "Add-on patches that keep Claude Duet reminders in view inside YouTube and Reddit"
        source = "git@github.com:variablenine/morphe-patches.git"
        author = "variablenine"
        contact = "https://github.com/variablenine/morphe-patches/issues"
        website = "https://github.com/variablenine/morphe-patches"
        license = "GNU General Public License v3.0"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the patch bundle.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

// The tests build a stand-in app with the same libraries the patcher itself uses to read
// apps. Compiling against the patcher's full runtime classpath keeps those versions in
// step with the patcher instead of naming them a second time here.
sourceSets.test {
    compileClasspath += configurations.testRuntimeClasspath.get()
}

tasks {
    test {
        useJUnitPlatform()
        // The tests patch a small stand-in app with the bundle exactly as it is released.
        dependsOn("buildAndroid")
        systemProperty("duet.bundleDir", layout.buildDirectory.dir("libs").get().asFile.absolutePath)
        // Optional: a real official bundle and real apps, for the compatibility check.
        // See docs/MAINTENANCE.md.
        listOf("duet.officialBundle", "duet.youtubeApk", "duet.redditApk").forEach { key ->
            providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
        }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("app.variablenine.util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
