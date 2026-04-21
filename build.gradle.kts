// Root build file — plugin declarations only.
// Module-level build logic lives in each module's own build.gradle.kts.
//
// FCM: once google-services.json is in apps/android/, uncomment the line below:
// TODO: id("com.google.gms.google-services") version "4.4.1" apply false
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Align Java/Kotlin JVM target for all Android library modules.
subprojects {
    plugins.withId("com.android.library") {
        configure<com.android.build.gradle.LibraryExtension> {
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}
