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
    // Agent connection settings only (dev_plan.md §12.14/§12.15, AgentConnectionStore) — the
    // «Агенты» dialog manages the same per-database file `kalita mcp --connection` reads. agent-api
    // must not depend back on app-desktop (AgentApiModule's own deferred-scope note); this edge only
    // ever goes this one direction.
    implementation(project(":modules:agent-api"))
    // Main.kt dispatches recognized CLI subcommands (`mcp`, `context`, …) to app-cli's own `run()`
    // before Qt is touched (dev_plan.md §12.12: one `kalita` on PATH is both the CLI/MCP server and
    // the GUI launcher) — same one-direction edge as agent-api above, app-cli never depends back.
    implementation(project(":modules:app-cli"))
}
