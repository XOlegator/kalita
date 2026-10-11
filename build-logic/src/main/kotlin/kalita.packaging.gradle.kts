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

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

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

    commandLine(
        jpackageExecutable(),
        "--type", "app-image",
        "--input", installLibDir.get().asFile.absolutePath,
        "--dest", outputDir.get().asFile.absolutePath,
        "--name", "Kalita",
        "--app-version", appVersion,
        "--main-jar", "${project.name}.jar",
        "--main-class", "kalita.app.desktop.MainKt",
        "--vendor", "Oleg Ekhlakov",
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
// system's libQt6*.so at runtime (docs/adr/0008), so a self-contained AppImage has to carry real
// Qt shared libraries itself, obtained independently of whatever (if any) Qt the host distro has.
// `-PbundledQtDir` points at an aqtinstall Qt 6.10.2 `gcc_64` tree (ci.yml's `package-smoke` job
// fetches one); only Qt's own libraries + the ICU build it links need bundling — libX11, libGL,
// glib, fontconfig, xkbcommon and the xcb family are assumed present on any Linux desktop, same as
// every other Linux Qt app distribution (verified via `readelf -d`/NEEDED on the aqtinstall build).
val packageAppImage = tasks.register("packageAppImage") {
    dependsOn(jpackageAppImage)
    onlyIf { !isWindows }
    group = "packaging"
    description = "Wraps the jpackage app-image into a Linux .AppImage via appimagetool, bundling " +
        "real Qt (-PbundledQtDir=...). appimagetool must be on PATH — CI installs it, see ci.yml."

    val appImageDir = jpackageAppImage.map { it.outputs.files.singleFile.resolve("Kalita") }
    val packagingAssets = layout.projectDirectory.dir("packaging/linux")
    val bundledQtDir = providers.gradleProperty("bundledQtDir")
    val appDir = layout.buildDirectory.dir("AppDir")
    val outputDir = layout.buildDirectory.dir("appimage")

    inputs.dir(appImageDir)
    inputs.dir(packagingAssets)
    outputs.dir(outputDir)

    doLast {
        val qtDir = File(
            bundledQtDir.orNull
                ?: error(
                    "packageAppImage needs -PbundledQtDir=<path to an aqtinstall Qt 6.10.2 " +
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
                    "libicu*.so.73*",
                )
            }
            into(qtLibTarget)
        }

        val qtPluginsTarget = appDirFile.resolve("usr/lib/qt-plugins/platforms")
        qtPluginsTarget.mkdirs()
        project.copy {
            from(qtDir.resolve("plugins/platforms")) {
                include("libqxcb.so", "libqoffscreen.so")
            }
            into(qtPluginsTarget)
        }

        val appRun = appDirFile.resolve("AppRun")
        appRun.writeText(
            "#!/bin/sh\n" +
                "HERE=\$(dirname \"\$(readlink -f \"\$0\")\")\n" +
                "export LD_LIBRARY_PATH=\"\$HERE/usr/lib:\$LD_LIBRARY_PATH\"\n" +
                "export QT_PLUGIN_PATH=\"\$HERE/usr/lib/qt-plugins\"\n" +
                "exec \"\$HERE/usr/bin/Kalita\" \"\$@\"\n",
        )
        appRun.setExecutable(true)

        val outputDirFile = outputDir.get().asFile
        outputDirFile.deleteRecursively()
        outputDirFile.mkdirs()

        // Project.exec {} is deprecated in favor of injected ExecOperations, which a precompiled
        // script plugin's top-level `doLast {}` can't easily receive — plain ProcessBuilder avoids
        // the whole question and needs no Gradle-API import.
        val process = ProcessBuilder(
            "appimagetool",
            appDirFile.absolutePath,
            outputDirFile.resolve("Kalita-x86_64.AppImage").absolutePath,
        ).inheritIO().start()
        check(process.waitFor() == 0) { "appimagetool failed" }
    }
}
