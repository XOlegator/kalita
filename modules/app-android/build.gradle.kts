// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

import kalita.buildlogic.PatchJackcessForAndroidTask

plugins {
    id("kalita.android-app")
    id("kalita.quality")
}

val libs = versionCatalogs.named("libs")

// Same switch as storage-mdb (-Pkalita.jackcess=spannm|upstream, dev_plan.md §8.3): the patched jar
// must be the line storage-mdb was compiled against. PatchJackcessForAndroidTask is line-agnostic —
// both lines log through java.lang.System.Logger.
val jackcessLine = providers.gradleProperty("kalita.jackcess").getOrElse("spannm")
val jackcessLibrary = libs.findLibrary(if (jackcessLine == "upstream") "jackcess-upstream" else "jackcess").get()

// Resolved separately from the normal dependency graph so we can patch it before it reaches the
// device — see PatchJackcessForAndroidTask (build-logic) and dev_plan.md's stage 0.4 finding.
val jackcessForPatch: Configuration = configurations.create("jackcessForPatch") {
    isCanBeConsumed = false
}

val patchJackcessForAndroid = tasks.register<PatchJackcessForAndroidTask>("patchJackcessForAndroid") {
    inputJar.fileProvider(project.provider { jackcessForPatch.singleFile })
    outputJar.set(layout.buildDirectory.file("jackcess-android-patched/jackcess-android-patched.jar"))
}

// AGP creates Android variant configurations lazily, after this script evaluates, under
// per-build-type names like "debugRuntimeClasspath"/"debugAndroidTestRuntimeClasspath" — not
// plain buckets like "runtimeClasspath", which aren't themselves resolvable. `matching` +
// `configureEach` reacts once a matching configuration actually appears, instead of requiring it
// to already exist. Matches every "*RuntimeClasspath" (debug/release *and* androidTest) — stage
// 10's start (dev_plan.md Этап 10 addendum) wires storage-mdb into `main` itself, not just the
// stage 0.4 PoC's instrumented tests, so the exclusion has to cover the real app's own runtime too.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    // The unpatched jar would otherwise still arrive transitively via storage-mdb's own
    // `implementation(libs.jackcess)` — excluded here, not at compile time, since the public API
    // we actually call never references java.lang.System.Logger (only Jackcess's internal classes
    // do), so compiling against the real artifact is unaffected.
    exclude(group = "io.github.spannm", module = "jackcess")
    exclude(group = "com.healthmarketscience.jackcess", module = "jackcess")
}

dependencies {
    // Stage 10's start (dev_plan.md Этап 10 addendum, FR-A1/FR-A2): local-only, read-only journal
    // screen — storage-local (not storage-mdb directly) so a database this app opens shares the
    // same lock/backups/history (DataDirectory.kt) as the desktop app and agent-api, and
    // presentation for the same JournalPresenter/JournalRowFormatter the desktop grid itself uses.
    implementation(project(":modules:storage-local"))
    implementation(project(":modules:sync"))
    implementation(project(":modules:presentation"))
    implementation(project(":modules:platform"))
    // AndroidSecretStore (dev_plan.md NFR-8 "Android Keystore") — §9.3 permits android.*/androidx.*
    // "в Android-реализациях platform"; this one concrete implementation of `platform`'s
    // SecretStore interface lives here rather than inside the `platform` module itself, since
    // `platform` is a plain JVM module with no Android source set to put it in, and app-android
    // already is where every other androidx.* import in this project lives.
    implementation(libs.findLibrary("androidx-security-crypto").get())

    // Plain JVM (AndroidAppControllerTest, not an instrumented test) — createDemoFixture, same as
    // storage-mdb's own unit tests, unpatched Jackcess runs fine on a desktop JVM.
    testImplementation(project(":modules:storage-mdb"))

    androidTestImplementation(project(":modules:storage-mdb"))

    add("jackcessForPatch", jackcessLibrary)
    // runtimeOnly (not androidTestRuntimeOnly): AGP's variant configuration hierarchy fans this out
    // to every build type's own *RuntimeOnly (debugRuntimeOnly, releaseRuntimeOnly) as well as
    // androidTest's, so the one patched jar now covers all of them, matching the broadened
    // exclusion above.
    add("runtimeOnly", files(patchJackcessForAndroid.map { it.outputJar.get().asFile }))
}
