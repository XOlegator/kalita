// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// jlink + jpackage wiring for app-desktop (dev_plan.md stage 0.3, §13.4, §9.2 `packaging/`).
// jpackage can't cross-compile: each task here only produces output for the OS it runs on, so the
// Windows MSI is built on a Windows runner and the Linux AppImage on a Linux one (stage 0.3's CI
// matrix does exactly that — there is no single-host way to produce both).
//
// No `--runtime-image` is passed to jpackage: without one, jpackage itself runs jlink internally to
// build a trimmed runtime from the JDK's own modules (via jdeps analysis of the input jars, falling
// back to a broader module set where that analysis can't fully resolve a non-modular classpath like
// QtJambi/Jackcess's). That already is "jlink + jpackage" — a separately hand-rolled jlink
// invocation ahead of it would just be redoing what jpackage does on its own.

import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("org.cyclonedx.bom")
}

// SBOM for the shipped release artifacts (dev_plan.md §13.4's "Сопутствующее"). Left unconfigured,
// `cyclonedxDirectBom` (confirmed by disassembly: that's the one with the per-configuration knobs,
// not the `cyclonedxBom` aggregate task this project actually runs) pulls in every resolvable
// configuration, test dependencies included — confirmed locally: a default run listed
// `junit-jupiter`/`apiguardian-api` as if they shipped in the app. `runtimeClasspath` is what
// `installDist`/`jpackageAppImage` actually bundle, so that's the one configuration that describes
// what a user's copy of Kalita really contains.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

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

val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
    dependsOn("installDist")
    group = "packaging"
    description = "jpackage --type app-image: self-contained runtime + app, no installer wrapper."

    val installLibDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outputDir = layout.buildDirectory.dir("jpackage")
    inputs.dir(installLibDir)
    outputs.dir(outputDir)

    doFirst { outputDir.get().asFile.deleteRecursively() }

    // --win-console: without it, jpackage's Windows launcher detaches into a background process
    // that doesn't run the console subsystem, so a caller invoking it synchronously (CI's
    // --self-test smoke check, msiexec-install-then-run) sees it return immediately with an
    // unrelated exit code while the JVM keeps running (and printing) in the detached process. A
    // real 1.0 release likely wants a separate windowed launcher for normal use — tracked as
    // follow-up, not stage 0.3's problem. Windows-only: jpackage rejects --win-* flags elsewhere.
    commandLine(
        buildList {
            add(jpackageExecutable())
            add("--type"); add("app-image")
            add("--input"); add(installLibDir.get().asFile.absolutePath)
            add("--dest"); add(outputDir.get().asFile.absolutePath)
            add("--name"); add("Kalita")
            add("--app-version"); add(appVersion)
            add("--main-jar"); add("${project.name}.jar")
            add("--main-class"); add("kalita.app.desktop.MainKt")
            add("--vendor"); add("Oleg Ekhlakov")
            // QtJambi's native library loading (io.qt.internal.LibraryUtility) calls the restricted
            // Runtime.load from an unnamed module; JDK 24+ warns about that today and a future JDK
            // will refuse it outright unless native access is explicitly granted. Baked into the
            // launcher config here so every install of the packaged app carries it, not just a dev run.
            add("--java-options"); add("--enable-native-access=ALL-UNNAMED")
            if (isWindows) add("--win-console")
        },
    )
}

// Same QtJambi-doesn't-bundle-Qt situation as Linux (see packageAppImage below and docs/adr/0008),
// but Windows DLL search checks the executable's own directory by default — no LD_LIBRARY_PATH-style
// wrapper needed — and Qt's platform plugin loader likewise defaults to a `platforms/` folder next to
// the executable, no QT_PLUGIN_PATH needed either. So this goes through jpackageAppImage first (like
// Linux), drops the DLLs next to Kalita.exe, then wraps the result with `jpackage --app-image`
// instead of handing jpackage the plain --input jars directly — --input's output ends up nested
// under app\, not next to the exe, which is the one place Windows' default DLL search looks.
val packageMsi = tasks.register("packageMsi") {
    dependsOn(jpackageAppImage)
    onlyIf { isWindows }
    group = "packaging"
    description = "jpackage --type msi (Windows only — stage 0.3 PoC, unsigned), bundling real Qt " +
        "(-PbundledQtDir=... — an aqtinstall Qt 6.10.2 win64_msvc2022_64 tree)."

    val appImageDir = jpackageAppImage.map { it.outputs.files.singleFile.resolve("Kalita") }
    val bundledQtDir = providers.gradleProperty("bundledQtDir")
    val outputDir = layout.buildDirectory.dir("jpackage-msi")
    inputs.dir(appImageDir)
    outputs.dir(outputDir)

    doLast {
        val qtDir = File(
            bundledQtDir.orNull
                ?: error(
                    "packageMsi needs -PbundledQtDir=<path to an aqtinstall Qt 6.10.2 " +
                        "win64_msvc2022_64 tree> — QtJambi does not bundle real Qt (docs/adr/0008).",
                ),
        )

        val appImageDirFile = appImageDir.get()
        project.copy {
            from(qtDir.resolve("bin")) {
                include("Qt6Core.dll", "Qt6Gui.dll", "Qt6Widgets.dll")
            }
            into(appImageDirFile)
        }
        project.copy {
            from(qtDir.resolve("plugins/platforms")) {
                include("qwindows.dll", "qoffscreen.dll")
            }
            into(appImageDirFile.resolve("platforms"))
        }

        val outputDirFile = outputDir.get().asFile
        outputDirFile.deleteRecursively()
        outputDirFile.mkdirs()

        val process = ProcessBuilder(
            jpackageExecutable(),
            "--type", "msi",
            "--app-image", appImageDirFile.absolutePath,
            "--dest", outputDirFile.absolutePath,
            "--name", "Kalita",
            "--app-version", appVersion,
            "--vendor", "Oleg Ekhlakov",
            "--win-dir-chooser",
            "--win-menu",
        ).inheritIO().start()
        check(process.waitFor() == 0) { "jpackage --type msi failed" }
    }
}

// QtJambi's Maven artifacts are only the JNI bridge — not real Qt. On Linux it dlopen()s the
// system's libQt6*.so at runtime (docs/adr/0008), so a self-contained app directory has to carry
// real Qt shared libraries itself, obtained independently of whatever (if any) Qt the host distro
// has. `-PbundledQtDir` points at an aqtinstall Qt 6.10.2 `gcc_64` tree (ci.yml's `package-smoke`
// job fetches one); only Qt's own libraries + the ICU build it links need bundling — libX11,
// libGL, glib, fontconfig, xkbcommon, libwayland-client/libwayland-cursor and the xcb family are
// assumed present on any Linux desktop, same as every other Linux Qt app distribution (verified
// via `readelf -d`/NEEDED on the aqtinstall build).
//
// Qt's Wayland *client* QPA integration (libqwayland.so + libQt6WaylandClient) ships inside
// qtbase itself as of this Qt version — confirmed against aqtinstall's actual 6.10.2 linux_gcc_64
// tree, not a separate `qtwayland` addon module (aqt only lists `qtwaylandcompositor`, which is
// the unrelated compositor-authoring side, for writing a Wayland compositor, not for running as a
// Wayland client). So no extra `aqt install-qt -m ...` module is needed in ci.yml/ppa-build.yml;
// the files below already exist in the plain qtbase fetch those workflows already do.
//
// Split out of `packageAppImage` (which now just wraps this in an AppImage) so the PPA/.deb
// pipeline (dev_plan.md §13.4) can depend on the directory alone, without needing `appimagetool`
// on PATH at all — a .deb's `debian/rules` installs these same files, it doesn't wrap them.
val assembleAppDir = tasks.register("assembleAppDir") {
    dependsOn(jpackageAppImage)
    onlyIf { !isWindows }
    group = "packaging"
    description = "Assembles build/AppDir: the jpackage app-image plus bundled Qt " +
        "(-PbundledQtDir=...) and the AppImage-style AppRun launcher/desktop file/icon."

    val appImageDir = jpackageAppImage.map { it.outputs.files.singleFile.resolve("Kalita") }
    val packagingAssets = layout.projectDirectory.dir("packaging/linux")
    val bundledQtDir = providers.gradleProperty("bundledQtDir")
    val appDir = layout.buildDirectory.dir("AppDir")

    inputs.dir(appImageDir)
    inputs.dir(packagingAssets)
    outputs.dir(appDir)

    doLast {
        val qtDir = File(
            bundledQtDir.orNull
                ?: error(
                    "assembleAppDir needs -PbundledQtDir=<path to an aqtinstall Qt 6.10.2 " +
                        "gcc_64 tree> — QtJambi does not bundle real Qt (docs/adr/0008).",
                ),
        )

        val appDirFile = appDir.get().asFile
        appDirFile.deleteRecursively()
        appDirFile.mkdirs()

        project.copy {
            from(appImageDir.get())
            into(appDirFile.resolve("usr"))
        }
        project.copy {
            from(packagingAssets.file("kalita.desktop"))
            from(packagingAssets.file("kalita.png"))
            into(appDirFile)
        }

        val qtLibTarget = appDirFile.resolve("usr/lib")
        qtLibTarget.mkdirs()
        project.copy {
            from(qtDir.resolve("lib")) {
                include(
                    "libQt6Core.so*",
                    "libQt6Gui.so*",
                    "libQt6Widgets.so*",
                    "libQt6DBus.so*",
                    "libQt6XcbQpa.so*",
                    "libQt6WaylandClient.so*",
                    "libQt6OpenGL.so*",
                    "libicu*.so.73*",
                )
            }
            into(qtLibTarget)
        }

        val qtPluginsTarget = appDirFile.resolve("usr/lib/qt-plugins")
        project.copy {
            from(qtDir.resolve("plugins/platforms")) {
                include("libqxcb.so", "libqoffscreen.so", "libqwayland.so")
            }
            into(qtPluginsTarget.resolve("platforms"))
        }
        // The rest of Qt's client-side Wayland support: a decoration (window-frame-drawing, since
        // Wayland compositors don't draw decorations themselves the way X11 window managers do), the
        // xdg-shell protocol integration (what every mainstream compositor — GNOME/Mutter, KWin,
        // wlroots-based ones — actually speaks), and the EGL graphics integration for OpenGL-backed
        // rendering. The other wayland-graphics-integration-client plugins (dmabuf/drm-egl/vulkan/
        // shm-emulation "-server") are for *writing* a Wayland compositor, not for running as a
        // client, so they're deliberately left out.
        project.copy {
            from(qtDir.resolve("plugins/wayland-decoration-client")) { include("libbradient.so") }
            into(qtPluginsTarget.resolve("wayland-decoration-client"))
        }
        project.copy {
            from(qtDir.resolve("plugins/wayland-shell-integration")) { include("libxdg-shell.so") }
            into(qtPluginsTarget.resolve("wayland-shell-integration"))
        }
        project.copy {
            from(qtDir.resolve("plugins/wayland-graphics-integration-client")) {
                include("libqt-plugin-wayland-egl.so")
            }
            into(qtPluginsTarget.resolve("wayland-graphics-integration-client"))
        }

        val appRun = appDirFile.resolve("AppRun")
        appRun.writeText(
            "#!/bin/sh\n" +
                "HERE=\$(dirname \"\$(readlink -f \"\$0\")\")\n" +
                "export LD_LIBRARY_PATH=\"\$HERE/usr/lib:\$LD_LIBRARY_PATH\"\n" +
                "export QT_PLUGIN_PATH=\"\$HERE/usr/lib/qt-plugins\"\n" +
                "exec \"\$HERE/usr/bin/Kalita\" \"\$@\"\n",
        )
        // setExecutable(true) alone is setExecutable(true, ownerOnly = true) — mode 0744. A newer
        // appimagetool build now hard-errors on that ("some users ... cannot execute it"; root cause
        // found via packageAppImage's own stdout/stderr capture below, not a FUSE/environment issue
        // after all) — set correctly here too, even though only packageAppImage's appimagetool run
        // actually checks it, so assembleAppDir's own output is correct on its own terms.
        appRun.setExecutable(true, false)
    }
}

val packageAppImage = tasks.register("packageAppImage") {
    dependsOn(assembleAppDir)
    onlyIf { !isWindows }
    group = "packaging"
    description = "Wraps build/AppDir (assembleAppDir) into a Linux .AppImage via appimagetool. " +
        "appimagetool must be on PATH — CI installs it, see ci.yml."

    val appDir = assembleAppDir.map { it.outputs.files.singleFile }
    val outputDir = layout.buildDirectory.dir("appimage")
    inputs.dir(appDir)
    outputs.dir(outputDir)

    doLast {
        val appDirFile = appDir.get()
        val outputDirFile = outputDir.get().asFile
        outputDirFile.deleteRecursively()
        outputDirFile.mkdirs()

        // Project.exec {} is deprecated in favor of injected ExecOperations, which a precompiled
        // script plugin's top-level `doLast {}` can't easily receive — plain ProcessBuilder avoids
        // the whole question and needs no Gradle-API import.
        //
        // `appimagetool` on PATH must be a plain ELF binary, not the AppImage it ships as — see
        // ci.yml's "Install appimagetool" step for why (GitHub's hosted Ubuntu runners don't allow
        // the unprivileged runner user to mount one via FUSE).
        //
        // redirectOutput/redirectError to real files instead of inheritIO(): on CI, inheritIO() was
        // producing zero output on either stream even on failure — this build's own JVM file
        // descriptors apparently don't map to what the GitHub Actions log actually captures when
        // Gradle runs this (temporary diagnosis, dev_plan.md PR #63). Printing the files' content
        // through Gradle's own logger, which demonstrably does reach the CI log, sidesteps the
        // question entirely rather than depending on an answer to it.
        val stdout = File.createTempFile("appimagetool-stdout", ".log")
        val stderr = File.createTempFile("appimagetool-stderr", ".log")
        val process = ProcessBuilder(
            "appimagetool",
            appDirFile.absolutePath,
            outputDirFile.resolve("Kalita-x86_64.AppImage").absolutePath,
        ).redirectOutput(stdout).redirectError(stderr).start()
        val exitCode = process.waitFor()
        logger.lifecycle("appimagetool stdout:\n{}", stdout.readText())
        logger.lifecycle("appimagetool stderr:\n{}", stderr.readText())
        check(exitCode == 0) { "appimagetool failed with exit code $exitCode" }
    }
}

// A plain archive for anyone who can't (or doesn't want to) run an AppImage — ironically including
// this very project's own CI runners (ci.yml's "Install appimagetool" step: GitHub's hosted Ubuntu
// runners won't let the unprivileged runner user FUSE-mount one). build/AppDir's own `AppRun` is
// already fully relative (`$(dirname "$(readlink -f "$0")")`-based, see assembleAppDir above), so
// the directory needs no further changes to be relocatable — this just renames it to `Kalita/` on
// the way into the archive, so extracting lands a `Kalita/` folder instead of `AppDir/`.
val packageTarGz = tasks.register<Tar>("packageTarGz") {
    dependsOn(assembleAppDir)
    onlyIf { !isWindows }
    group = "packaging"
    description = "Archives build/AppDir (assembleAppDir) as a portable .tar.gz — run ./Kalita/AppRun after extracting."

    compression = Compression.GZIP
    archiveFileName.set("Kalita-$appVersion-linux-x86_64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(assembleAppDir) {
        into("Kalita")
        // Gradle's archive tasks do not preserve a copied file's original Unix permissions
        // (confirmed locally: AppRun, chmod 0755 on disk by assembleAppDir, came out 0644 inside
        // the .tar.gz without this) — set explicitly so `./Kalita/AppRun` is runnable straight out
        // of the archive, with no `chmod` step the way an AppImage itself never needs one either.
        filePermissions { unix("rwxr-xr-x") }
        dirPermissions { unix("rwxr-xr-x") }
    }
}

// The MSI's own unpacking already leaves a self-contained, Qt-DLLs-included app directory next to
// Kalita.exe (packageMsi's appImageDir, Windows DLL search checks the executable's own directory by
// default) — this just zips that same directory for anyone who wants to run Kalita without an
// installer (a portable USB stick, a locked-down machine with no admin rights to run the MSI, …).
val packagePortableZip = tasks.register<Zip>("packagePortableZip") {
    dependsOn(packageMsi)
    onlyIf { isWindows }
    group = "packaging"
    description = "Zips the MSI's already Qt-bundled app directory as a portable .zip — run Kalita.exe after extracting, no install needed."

    val appImageDir = jpackageAppImage.map { it.outputs.files.singleFile.resolve("Kalita") }
    archiveFileName.set("Kalita-$appVersion-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(appImageDir) { into("Kalita") }
}
