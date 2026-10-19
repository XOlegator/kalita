// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

dependencies {
    // dev_plan.md §9.3 / ADR-0006: storage-api -> core; DomainMapping turns a snapshot into core's entities.
    api(project(":modules:core"))
}
