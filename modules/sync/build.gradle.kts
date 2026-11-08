// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // dev_plan.md §9.3: sync -> storage-api, core (never storage-local — that's local-mode-only).
    api(project(":modules:storage-api"))
    // Deviation from §9.2's sketch, documented in SyncModule.kt: the rebase step reuses
    // storage-mdb's AtomicCommit/MdbEngine against a private local cache file (never the shared
    // file storage-local manages), so sync depends on storage-mdb too, not only storage-api/core.
    implementation(project(":modules:storage-mdb"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
