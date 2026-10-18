// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

// dev_plan.md NFR-5 / stage 1 acceptance: data layer >= 85 % line coverage.
kalitaCoverage {
    minimumLineCoverage.set(0.85)
}

dependencies {
    api(project(":modules:storage-api"))
    implementation(project(":modules:storage-mdb"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.jqwik)
}
