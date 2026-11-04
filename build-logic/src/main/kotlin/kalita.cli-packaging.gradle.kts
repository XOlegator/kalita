// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// jlink + jpackage wiring for app-cli (dev_plan.md §12.16, Этап 8 addendum): a self-contained
// `kalita` CLI app-image, bundled into kalita.mcpb (agent/, see release.yml's "Assemble kalita.mcpb"
// step) so Claude Desktop can run `kalita mcp` without the user installing anything else first.
// Deliberately NOT kalita.packaging (app-desktop's own convention plugin): this module has no Qt,
// no GUI, no installer wrapper (MSI/AppImage) — just the plain app-image jpackage produces on its
// own, the same "jlink + jpackage, no --runtime-image" reasoning as kalita.packaging's own header
// comment, minus everything Qt-specific (QtJambi's --enable-native-access, Qt bundling).

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Same source and reasoning as kalita.packaging's own appVersion — single source of truth for
// "what version is this build", shared with the desktop packages and (dev_plan.md's own addendum)
// app-android's versionName.
val appVersion: String = run {
    val manifest = rootProject.file(".release-please-manifest.json")
    val text = manifest.readText()
    Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        ?: error("Could not read the app version from ${manifest.path}")
}

val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
val jpackageLauncher = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

fun jpackageExecutable(): String {
    val home = jpackageLauncher.get().metadata.installationPath.asFile
    return home.resolve("bin").resolve(if (isWindows) "jpackage.exe" else "jpackage").absolutePath
}

// --win-console (Windows only): same reasoning as kalita.packaging's jpackageAppImage — without
// it, the launcher detaches into a background process instead of running as a normal console
// program, which `kalita mcp`'s stdio protocol depends on.
val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
    dependsOn("installDist")
    group = "packaging"
    description = "jpackage --type app-image: self-contained kalita CLI, no GUI/Qt — for kalita.mcpb."

    val installLibDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outputDir = layout.buildDirectory.dir("jpackage")
    inputs.dir(installLibDir)
    outputs.dir(outputDir)

    doFirst { outputDir.get().asFile.deleteRecursively() }

    commandLine(
        buildList {
            add(jpackageExecutable())
            add("--type"); add("app-image")
            add("--input"); add(installLibDir.get().asFile.absolutePath)
            add("--dest"); add(outputDir.get().asFile.absolutePath)
            add("--name"); add("kalita")
            add("--app-version"); add(appVersion)
            add("--main-jar"); add("${project.name}.jar")
            add("--main-class"); add("kalita.app.cli.MainKt")
            add("--vendor"); add("Oleg Ekhlakov")
            // This image ends up inside kalita.mcpb (release.yml), downloaded per-install, so its
            // size matters more than app-desktop's own jpackageAppImage (installed once via a
            // package manager/AppImage). --strip-debug/--no-header-files/--no-man-pages are the
            // standard safe jlink trio; confirmed to actually shrink the image. ct.sym (javac
            // --release's cross-compilation data, ~10 MB, never read at runtime) is NOT removed by
            // this — `jlink --exclude-files=glob:/java.base/lib/ct.sym` removes it when run
            // directly, but the identical string passed through jpackage's --jlink-options does
            // not (reproduced directly with jpackage, not a Gradle caching artifact) — left as a
            // known, minor, not-this-slice gap rather than spending more time on jpackage's own
            // --jlink-options plumbing for one secondary task.
            // Deliberately NOT `--generate-cds-archive` here, unlike app-desktop's own
            // jpackageAppImage (dev_plan.md §15 Этап 8): it does work through jpackage's
            // --jlink-options for this image too (tested directly, no caching artifact — unlike
            // --exclude-files above), but it bakes in `classes.jsa` + `classes_nocoops.jsa`, ~29 MB
            // combined, for only a modest ≈0.86s→0.82s `--help` improvement — a bad trade
            // specifically for this image's own stated size priority (previous paragraph), unlike
            // app-desktop's one-time AppImage/MSI download where the same archives (≈10% faster
            // self-test) were judged worth their ≈31 MB.
            add("--jlink-options"); add("--strip-debug --no-header-files --no-man-pages")
            if (isWindows) add("--win-console")
        },
    )
}
