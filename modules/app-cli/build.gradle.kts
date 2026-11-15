// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
    id("kalita.cli-packaging")
    application
}

dependencies {
    implementation(project(":modules:agent-api"))
    implementation(project(":modules:storage-mdb"))
    // `kalita rates load` (dev_plan.md FR-R12): the shared host-side rate loader and the built-in
    // Bank of Russia source, found through ServiceLoader like any other plugin.
    implementation(project(":modules:plugin-host"))
    runtimeOnly(project(":plugins:rates-cbr"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk.server)

    // Test fixtures only (dev_plan.md §14.2): building a throwaway .mdb needs storage-api's
    // ChangeSet types and storage-mdb's AtomicCommit/fixture generator directly, the same way
    // modules:cli's CliTest does — agent-api only re-exposes these through its own tool JSON.
    testImplementation(project(":modules:storage-api"))
    testImplementation(project(":modules:storage-mdb"))

    // Test-only: drives buildMcpServer's Server in-process via the SDK's Client + ChannelTransport
    // (kotlin-sdk-testing), never through stdio/a subprocess.
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.mcp.kotlin.sdk.testing)
}

application {
    mainClass.set("kalita.app.cli.MainKt")
}
