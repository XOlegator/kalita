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
}
