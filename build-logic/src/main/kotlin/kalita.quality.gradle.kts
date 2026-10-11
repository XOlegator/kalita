// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Shared code-quality wiring (coverage targets from dev_plan.md NFR-5: data layer/sync >= 85%,
// domain >= 80%). Applied on top of kalita.kotlin-core, kalita.qtjambi-app or kalita.android-app.
// Registers its own "jacocoTestReport" rather than relying on the one the `java` plugin wires up,
// because AGP modules (app-android) don't apply `java` and so never get that implicit task.

plugins {
    jacoco
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// The `java` plugin (applied transitively by kotlin-jvm) already wires a "jacocoTestReport" task
// once the jacoco plugin is present; AGP modules don't apply `java`, so only register it ourselves
// when it's missing.
if (tasks.findByName("jacocoTestReport") == null) {
    tasks.register<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.withType<Test>())
        executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

tasks.withType<Test>().configureEach {
    finalizedBy(tasks.named("jacocoTestReport"))
}
