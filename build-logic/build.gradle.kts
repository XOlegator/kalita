// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

// build-logic is its own Gradle build (pluginManagement.includeBuild), so it cannot share the
// root project's "libs" version catalog. Keep these two versions equal to kotlin / android-gradle-plugin
// in gradle/libs.versions.toml.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    implementation("com.android.tools.build:gradle:9.4.1")
    // PatchJackcessForAndroidTask (kalita.buildlogic): rewrites Jackcess's java.lang.System.Logger
    // usage, which doesn't exist on Android (see that task's own comment), to a local shim type.
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-commons:9.11.1")

    // AGP's transitive deps run inside every Gradle build; raise known-vulnerable ones (catalog: build-floor-*).
    constraints {
        libs.bundles.build.tool.security.floors.get().forEach { implementation(it) { because("security floor for build tooling") } }
    }
}
