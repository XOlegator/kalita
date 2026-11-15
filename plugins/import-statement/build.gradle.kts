// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

kalitaCoverage {
    minimumLineCoverage.set(0.80)
}

dependencies {
    // Built-in universal StatementImporter: CSV (columns recognised by header names) and
    // OFX/QFX (dev_plan.md §11.5 "выписка-файл — универсальный путь, делается первым").
    // Pure Kotlin on plugin-api: no CSV/OFX library, so nothing new in verification-metadata.xml
    // and the same code runs in the Android build (§9.11).
    implementation(project(":modules:plugin-api"))
    testImplementation(libs.kotlinx.coroutines.test)
}
