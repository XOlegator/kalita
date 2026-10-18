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
// per-build-type names like "debugAndroidTestRuntimeClasspath" — not the plain
// "androidTestRuntimeClasspath" bucket, which isn't itself resolvable. `matching` + `configureEach`
// reacts once a matching configuration actually appears, instead of requiring it to already exist.
configurations.matching { it.name.endsWith("AndroidTestRuntimeClasspath") }.configureEach {
    // The unpatched jar would otherwise still arrive transitively via storage-mdb's own
    // `implementation(libs.jackcess)` — excluded here, not at compile time, since the public API
    // we actually call never references java.lang.System.Logger (only Jackcess's internal classes
    // do), so compiling against the real artifact is unaffected.
    exclude(group = "io.github.spannm", module = "jackcess")
    exclude(group = "com.healthmarketscience.jackcess", module = "jackcess")
}

dependencies {
    // Stage 0.4 PoC (dev_plan.md): only the instrumented tests need storage-mdb/Jackcess so far —
    // the real Compose shell wires this into `main` from stage 10 onward.
    androidTestImplementation(project(":modules:storage-mdb"))

    add("jackcessForPatch", jackcessLibrary)
    add("androidTestRuntimeOnly", files(patchJackcessForAndroid.map { it.outputJar.get().asFile }))
}
