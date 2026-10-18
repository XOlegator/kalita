// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Shared code-quality wiring (coverage targets from dev_plan.md NFR-5: data layer/sync >= 85%,
// domain >= 80%). Applied on top of kalita.kotlin-core, kalita.qtjambi-app or kalita.android-app.
// Registers its own "jacocoTestReport" rather than relying on the one the `java` plugin wires up,
// because AGP modules (app-android) don't apply `java` and so never get that implicit task.

plugins {
    jacoco
}

/**
 * Minimum line coverage a module promises (dev_plan.md NFR-5), enforced by `check` through
 * `jacocoTestCoverageVerification`. Unset (the default) means no gate — set it per module, e.g.
 * `kalitaCoverage { minimumLineCoverage.set(0.85) }` for the data layer.
 */
abstract class KalitaCoverageExtension {
    abstract val minimumLineCoverage: Property<Double>
}

val kalitaCoverage = extensions.create<KalitaCoverageExtension>("kalitaCoverage")

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

// The java plugin's own report defaults to HTML only; XML is what coverage gates and CI tooling read.
tasks.withType<JacocoReport>().configureEach {
    reports.xml.required.set(true)
}

// Only kotlin-jvm modules get the java plugin's verification task; AGP modules have no gate yet.
plugins.withId("java") {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        onlyIf("a minimum line coverage is set") { kalitaCoverage.minimumLineCoverage.isPresent }
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = kalitaCoverage.minimumLineCoverage.map { it.toBigDecimal() }.getOrElse(java.math.BigDecimal.ZERO)
                }
            }
        }
    }
    tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
}
