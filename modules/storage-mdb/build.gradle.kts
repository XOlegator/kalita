// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("kalita.kotlin-core")
    id("kalita.quality")
}

// dev_plan.md NFR-5 / stage 1 acceptance: data layer >= 85 % line coverage.
kalitaCoverage {
    minimumLineCoverage.set(0.85)
}

// Which Jackcess line storage-mdb compiles against (dev_plan.md §8.3, ADR-0003): the spannm fork by
// default, upstream with -Pkalita.jackcess=upstream. Only src/<line>/ knows the line's package and
// builder naming; all other code goes through it, so switching is a build flag, not a code change.
val jackcessLine = providers.gradleProperty("kalita.jackcess").getOrElse("spannm")
require(jackcessLine in setOf("spannm", "upstream")) {
    "kalita.jackcess must be 'spannm' or 'upstream', got '$jackcessLine'"
}

sourceSets.main {
    kotlin.srcDir("src/$jackcessLine/kotlin")
}
sourceSets.test {
    // OtherLine: the line *not* compiled in, read directly by CrossLineTest (both lines' packages differ,
    // so the two jars coexist on the test classpath).
    kotlin.srcDir("src/${jackcessLine}Test/kotlin")
}

tasks.withType<Test>().configureEach {
    // Lets a test report show which line it actually exercised.
    systemProperty("kalita.jackcess", jackcessLine)
}

dependencies {
    api(project(":modules:storage-api"))
    implementation(if (jackcessLine == "upstream") libs.jackcess.upstream else libs.jackcess)
    testImplementation(if (jackcessLine == "upstream") libs.jackcess else libs.jackcess.upstream)
    testImplementation(libs.jqwik)
}
