// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.qtjambi-app")
    id("kalita.quality")
    id("kalita.packaging")
}

application {
    mainClass.set("kalita.app.desktop.MainKt")
}

dependencies {
    implementation(project(":modules:storage-mdb"))
}
