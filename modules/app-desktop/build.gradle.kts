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

dependencies {
    implementation(project(":modules:presentation"))
    implementation(project(":modules:storage-local"))
    implementation(project(":modules:storage-mdb"))
    // Agent connection settings only (dev_plan.md §12.14/§12.15, AgentConnectionStore) — the
    // «Агенты» dialog manages the same per-database file `kalita mcp --connection` reads. agent-api
    // must not depend back on app-desktop (AgentApiModule's own deferred-scope note); this edge only
    // ever goes this one direction.
    implementation(project(":modules:agent-api"))
}
