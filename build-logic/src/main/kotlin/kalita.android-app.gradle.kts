// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Convention for the Android module (app-android — dev_plan.md §9.2, §9.10). Only this module
// (and Android-specific `platform` source sets, once they exist) may depend on android.*/androidx.*
// (§9.3); that boundary is enforced by Konsist once the core modules land in stage 2.

// AGP 9 bundles Kotlin support for Android modules directly; applying
// org.jetbrains.kotlin.android on top is rejected (https://kotl.in/gradle/agp-built-in-kotlin).
plugins {
    id("com.android.library")
}

val libs = versionCatalogs.named("libs")

android {
    namespace = "kalita.android"
    compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Re-enable once stage 2 gives this module real sources — an empty stub has nothing
        // for NewApi and friends to check, and lint's own analysis is expensive to run per build.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("kotest-assertions-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())

    // Instrumented (on-device/emulator) tests use JUnit4 + androidx.test, not JUnit5 — AGP's
    // connectedAndroidTest runner doesn't support the JUnit5 Jupiter engine out of the box.
    add("androidTestImplementation", libs.findLibrary("androidx-test-core").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-runner").get())
    add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    add("androidTestImplementation", libs.findLibrary("kotest-assertions-core").get())
}
