// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // dev_plan.md §9.3: plugin-api -> storage-api -> core. Dataset (InsightProvider's input)
    // and the Table/ChangeSet vocabulary a future StorageProvider-shaped extension would use
    // both live below storage-api, not duplicated here.
    api(project(":modules:storage-api"))
    // Every extension point interface here is `suspend fun` (network, recognition, AI calls can
    // all take a while) — a plugin implementation needs this on its own compile classpath too.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
