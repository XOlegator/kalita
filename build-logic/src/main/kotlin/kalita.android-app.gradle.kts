// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Convention for the Android module (app-android — dev_plan.md §9.2, §9.10). Only this module
// (and Android-specific `platform` source sets, once they exist) may depend on android.*/androidx.*
// (§9.3); that boundary is enforced by Konsist once the core modules land in stage 2.

// `com.android.application`, not `com.android.library`: app-android is meant to end up as the
// real installable Compose app of stage 10 (§9.2's own tree already names it that way). This
// convention plugin got a minimal real screen ahead of stage 10, as a stage-8 side quest — to
// walk the whole build→sign→install procedure on a real device once, before the actual UI exists
// (dev_plan.md's Этап 8 addendum). AGP 9 bundles Kotlin support for Android modules directly;
// applying org.jetbrains.kotlin.android on top is rejected (https://kotl.in/gradle/agp-built-in-kotlin).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = versionCatalogs.named("libs")

// Same source as kalita.packaging's appVersion (build-logic/build.gradle.kts comment there) —
// single source of truth for "what version is this build", desktop and Android alike.
val appVersionName: String = run {
    val manifest = rootProject.file(".release-please-manifest.json")
    val text = manifest.readText()
    Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        ?: error("Could not read the app version from ${manifest.path}")
}

// Play Store monotonicity rules don't apply here (not F-Droid's either, strictly, but it follows
// the same convention) — this is just "strictly increasing per release", good enough until a real
// signing/release pipeline (stage 10/11) asks for more. 0.1.36 -> 1036; breaks past minor/patch 999.
val appVersionCode: Int = run {
    val (major, minor, patch) = appVersionName.split(".").map { it.toInt() }
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "kalita.android"
    compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        applicationId = "io.github.xolegator.kalita"
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// AGP's own unit test task isn't a `JvmTestSuite` (unlike kalita.kotlin-core's plain-JVM modules,
// which get JUnit 5 through `testing { suites { ... useJUnitJupiter() } } }`) — it has to be told
// directly. Needed from stage 10's first real `src/test` (AndroidAppControllerTest); every Android
// unit test dependency below (junit-jupiter, kotest, junit-platform-launcher) already assumed this.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    add("implementation", libs.findLibrary("androidx-core-ktx").get())
    add("implementation", libs.findLibrary("androidx-activity-compose").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
    add("implementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    add("implementation", libs.findLibrary("androidx-compose-ui").get())
    add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    add("implementation", libs.findLibrary("androidx-compose-material3").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())

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

    // Android Lint runs from its own classpath and pulls the same vulnerable build-tool libraries
    // as AGP (catalog: build-floor-*); it never ends up in the APK.
    libs.findBundle("build-tool-security-floors").get().get().forEach {
        constraints.add("androidLintTool", it) { because("security floor for build tooling") }
    }
}
