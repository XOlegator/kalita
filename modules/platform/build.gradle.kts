// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // Both desktop-OS secret stores (dev_plan.md NFR-8/§9.4) — selected at runtime by `os.name`
    // (DesktopSecretStore.kt), never both touched in the same process. No Qt/Android import
    // either way, so this stays within §9.3's module boundaries for `platform`.
    implementation(libs.secret.service)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}
