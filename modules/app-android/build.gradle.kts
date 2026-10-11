// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.android-app")
    id("kalita.quality")
}

dependencies {
    // Stage 0.4 PoC (dev_plan.md): only the instrumented tests need storage-mdb/Jackcess so far —
    // the real Compose shell wires this into `main` from stage 10 onward.
    androidTestImplementation(project(":modules:storage-mdb"))
}
