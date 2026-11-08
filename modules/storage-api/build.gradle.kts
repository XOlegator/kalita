// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // dev_plan.md §9.3 / ADR-0006: storage-api -> core; DomainMapping turns a snapshot into core's entities.
    api(project(":modules:core"))
    // ChangeSetJson (moved here from storage-local, Этап 9): storage-local's history and sync's
    // unsent-change queue both serialize ChangeSet the same way, and sync doesn't depend on
    // storage-local (§9.3), so the encoding has to live where both can reach it.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.jqwik)
}
