// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Convention for pure-Kotlin modules (core, storage-*, sync, presentation, plugin-api, platform,
// agent-api, app-cli, app-server, cli — dev_plan.md §9.2). The toolchain that compiles and runs
// tests is JDK 25, but the emitted bytecode targets JVM 17 so the module also links into
// app-android (§9.1); actual Android-API availability is still checked by Android Lint's NewApi
// in the android-app convention, not by the bytecode version alone.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    // Keep compileJava's target in step with compileKotlin's (both JVM 17, run by the JDK 25
    // toolchain) — Kotlin's target-validation check fails the build otherwise.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
    }
}

dependencies {
    val libs = versionCatalogs.named("libs")
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("kotest-assertions-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
