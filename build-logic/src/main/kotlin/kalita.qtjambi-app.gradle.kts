// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Convention for the desktop shell (app-desktop — dev_plan.md §9.2, §9.9). Desktop-only, so unlike
// kalita.kotlin-core it is not constrained to an Android-compatible bytecode target.
// QtJambi's native library is split per OS/arch on Maven Central; only linux-x64 is wired here
// (dev development platform and CI both run Ubuntu x64). Windows packaging in stage 0.3 adds
// qtjambi-native-windows-x64 as an extra runtime dependency, not a replacement for this one.
//
// QtJambi links against the system Qt libraries on Linux rather than bundling them, and must match
// its minor version exactly (dev_plan.md R21): Ubuntu 24.04 and 26.04 ship different system Qt
// minors, and 24.04's is older than any QtJambi release on Maven Central. The packaged app sidesteps
// this by bundling its own Qt + QtJambi (dev_plan.md §13.4), so this pin is a dev-run convenience,
// not the shipped version-matching strategy. The `-PqtjambiVersion=` override lets a future CI
// matrix (or a one-off local check) build/smoke-test against a different QtJambi/Qt pair without
// editing this file.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
    }
}

val qtjambiVersion: String =
    providers.gradleProperty("qtjambiVersion")
        .orElse(versionCatalogs.named("libs").findVersion("qtjambi").get().requiredVersion)
        .get()

dependencies {
    val libs = versionCatalogs.named("libs")
    add("implementation", "io.qtjambi:qtjambi:$qtjambiVersion")
    add("runtimeOnly", "io.qtjambi:qtjambi-native-linux-x64:$qtjambiVersion")
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("kotest-assertions-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
