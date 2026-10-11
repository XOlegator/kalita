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

val packageMsi = tasks.register<Exec>("packageMsi") {
    dependsOn("installDist")
    onlyIf { isWindows }
    group = "packaging"
    description = "jpackage --type msi (Windows only — stage 0.3 PoC, unsigned)."

    val installLibDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outputDir = layout.buildDirectory.dir("jpackage-msi")
    inputs.dir(installLibDir)
    outputs.dir(outputDir)

    doFirst { outputDir.get().asFile.deleteRecursively() }

    commandLine(
        jpackageExecutable(),
        "--type", "msi",
        "--input", installLibDir.get().asFile.absolutePath,
        "--dest", outputDir.get().asFile.absolutePath,
        "--name", "Kalita",
        "--app-version", appVersion,
        "--main-jar", "${project.name}.jar",
        "--main-class", "kalita.app.desktop.MainKt",
        "--vendor", "Oleg Ekhlakov",
        "--win-dir-chooser",
        "--win-menu",
    )
}

val packageAppImage = tasks.register("packageAppImage") {
    dependsOn(jpackageAppImage)
    onlyIf { !isWindows }
    group = "packaging"
    description = "Wraps the jpackage app-image into a Linux .AppImage via appimagetool " +
        "(stage 0.3 PoC; appimagetool must be on PATH — CI installs it, see ci.yml)."

    val appImageDir = jpackageAppImage.map { it.outputs.files.singleFile.resolve("Kalita") }
    val packagingAssets = layout.projectDirectory.dir("packaging/linux")
    val appDir = layout.buildDirectory.dir("AppDir")
    val outputDir = layout.buildDirectory.dir("appimage")

    inputs.dir(appImageDir)
    inputs.dir(packagingAssets)
    outputs.dir(outputDir)

    doLast {
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

        val appRun = appDirFile.resolve("AppRun")
        appRun.writeText(
            "#!/bin/sh\n" +
                "HERE=\$(dirname \"\$(readlink -f \"\$0\")\")\n" +
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
