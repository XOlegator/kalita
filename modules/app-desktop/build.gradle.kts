// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.qtjambi-app")
    id("kalita.quality")
    id("kalita.packaging")
}

application {
    mainClass.set("kalita.app.desktop.MainKt")
}

// `Main.kt`'s loadAppIcon() reads "kalita.png" off the classpath at runtime (both under `:run`
// and from the packaged jar) — one file serves that and Linux packaging's own `Icon=kalita`
// (kalita.packaging.gradle.kts), instead of keeping two copies of the same icon in sync by hand.
sourceSets {
    main {
        resources.srcDir("packaging/linux")
    }
}

// Same source and reasoning as kalita.packaging's own appVersion — exposed here too so the "О
// программе" dialog (MainWindow.onAbout) can read it back at runtime via this jar's own manifest
// (Package.getImplementationVersion()), rather than hand-maintaining the version string a third
// place. Only present when run from the built jar (installDist/jpackage) — :run's classpath uses
// the raw class directories instead, no manifest, so onAbout falls back to "dev" there.
val appVersion: String = run {
    val manifest = rootProject.file(".release-please-manifest.json")
    val text = manifest.readText()
    Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        ?: error("Could not read the app version from ${manifest.path}")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Implementation-Version" to appVersion)
    }
}

dependencies {
    implementation(project(":modules:presentation"))
    implementation(project(":modules:storage-local"))
    implementation(project(":modules:storage-mdb"))
    implementation(project(":modules:sync"))
    // SecretStore interface (dev_plan.md NFR-8) — the Nextcloud app password's persistence;
    // DesktopSecretStore and its two real implementations live in this module (not `platform`
    // itself), so their JNA/D-Bus dependencies below never reach `app-android`'s APK.
    implementation(project(":modules:platform"))
    // Linux Secret Service (D-Bus) client — a focused, actively maintained library for exactly
    // this protocol, not the grab-bag java-keyring wrapper (unclear license, abandoned upstream,
    // pulls in a macOS Keychain binding this project never targets) — see dev_plan.md's Этап 9
    // срез for the comparison.
    implementation(libs.secret.service)
    // Windows Credential Manager, via JNA's Advapi32 — already pre-approved in dev_plan.md §13.1
    // for a different future use (Tess4J/OCR), same artifact either way.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // Agent connection settings only (dev_plan.md §12.14/§12.15, AgentConnectionStore) — the
    // «Агенты» dialog manages the same per-database file `kalita mcp --connection` reads. agent-api
    // must not depend back on app-desktop (AgentApiModule's own deferred-scope note); this edge only
    // ever goes this one direction.
    implementation(project(":modules:agent-api"))
    // Main.kt dispatches recognized CLI subcommands (`mcp`, `context`, …) to app-cli's own `run()`
    // before Qt is touched (dev_plan.md §12.12: one `kalita` on PATH is both the CLI/MCP server and
    // the GUI launcher) — same one-direction edge as agent-api above, app-cli never depends back.
    implementation(project(":modules:app-cli"))
    // Plugins (dev_plan.md §9.11, FR-P1): the host-side registry/settings, the bank reconciliation
    // library behind «Импортировать выписку…», and the built-in plugins themselves — runtimeOnly,
    // since the shell only ever meets them through ServiceLoader and plugin-api's contracts.
    implementation(project(":modules:plugin-host"))
    implementation(project(":plugins:reconcile"))
    runtimeOnly(project(":plugins:backup"))
    runtimeOnly(project(":plugins:import-apibank"))
}

// Opt-in rendering of the plugin dialogs to PNG (PluginUiPreviewTest, offscreen Qt):
// ./gradlew :modules:app-desktop:test --tests '*PluginUiPreviewTest*' -Dkalita.ui.preview=/some/dir
tasks.withType<Test>().configureEach {
    System.getProperty("kalita.ui.preview")?.let { systemProperty("kalita.ui.preview", it) }
}
