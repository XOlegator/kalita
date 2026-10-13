// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
    application
}

dependencies {
    implementation(project(":modules:storage-api"))
    implementation(project(":modules:storage-mdb"))
    testImplementation(libs.jackcess)
}

application {
    mainClass.set("kalita.devcli.MainKt")
}
