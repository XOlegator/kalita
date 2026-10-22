// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

// dev_plan.md NFR-5 / stage 2 acceptance: domain >= 80 % line coverage.
kalitaCoverage {
    minimumLineCoverage.set(0.80)
}

dependencies {
    // Saved filters and journal sort as JSON (dev_plan.md §9.7, FR-28).
    implementation(libs.kotlinx.serialization.json)
}
