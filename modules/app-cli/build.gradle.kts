// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
    application
}

dependencies {
    implementation(project(":modules:agent-api"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)

    // Test fixtures only (dev_plan.md §14.2): building a throwaway .mdb needs storage-api's
    // ChangeSet types and storage-mdb's AtomicCommit/fixture generator directly, the same way
    // modules:cli's CliTest does — agent-api only re-exposes these through its own tool JSON.
    testImplementation(project(":modules:storage-api"))
    testImplementation(project(":modules:storage-mdb"))
}

application {
    mainClass.set("kalita.app.cli.MainKt")
}
