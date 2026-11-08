// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // dev_plan.md §9.3: sync -> storage-api, core (never storage-local — that's local-mode-only).
    api(project(":modules:storage-api"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
