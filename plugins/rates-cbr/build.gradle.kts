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
    // Built-in RateSource for the Bank of Russia's public XML service (dev_plan.md FR-R12, the
    // original's «Сервис → Курсы из Интернета»). The two responses it reads are flat and
    // machine-generated, so they are parsed with regular expressions: no XML parser to configure
    // against external entities, nothing extra for the Android build.
    implementation(project(":modules:plugin-api"))
    testImplementation(libs.kotlinx.coroutines.test)
}
