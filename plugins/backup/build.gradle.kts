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
    // A built-in plugin (dev_plan.md §9.11): registered through META-INF/services on the host's
    // own classloader, compiled against plugin-api only — the same jar would also load from an
    // external `plugins/` folder, nothing here knows it is built in.
    implementation(project(":modules:plugin-api"))
    testImplementation(libs.kotlinx.coroutines.test)
}
