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
