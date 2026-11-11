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
    // Built-in StatementImporter for API Bank's "AccountData_Transactions_*.xls" export
    // (dev_plan.md §11.5 "шаблоны для конкретных банков — в плагине"). The .xls is read by a
    // small BIFF8 reader of our own (Biff8.kt) rather than Apache POI: the export uses a handful
    // of record types, POI would add six transitive artifacts to the verified dependency set for
    // that, and it is a poor fit for the Android build the same plugin must run in (§9.11).
    implementation(project(":modules:plugin-api"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Opt-in local check against real exports (never committed — they hold personal data):
// ./gradlew :plugins:import-apibank:test -Dkalita.apibank.sample=/path/a.xls;/path/b.xls
tasks.withType<Test>().configureEach {
    System.getProperty("kalita.apibank.sample")?.let { systemProperty("kalita.apibank.sample", it) }
}
