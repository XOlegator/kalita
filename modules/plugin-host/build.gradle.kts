// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // The host side of dev_plan.md §9.11 — what both shells share on top of plugin-api's contracts:
    // the registry of loaded plugins, enabled flags and per-plugin settings persistence, and the
    // per-plugin scoping of PluginHost services. No Qt, no Android, no network stack of its own
    // (the shell supplies PluginHttpClient), so app-android can take it as-is.
    api(project(":modules:plugin-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
