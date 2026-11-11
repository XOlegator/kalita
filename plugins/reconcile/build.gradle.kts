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
    // dev_plan.md §9.2 `plugins/reconcile`: the bank-side of §11 — matching BankTransactions
    // (plugin-api) against the journal (core) and remembering the outcome next to the database
    // (§9.4). A library both shells call, not a Plugin instance itself: there is nothing for the
    // user to switch off here, every StatementImporter/BankConnector goes through it.
    api(project(":modules:plugin-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
