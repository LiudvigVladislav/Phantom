plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm()
    // iOS targets: add on macOS when building KMP XCFramework (Alpha-1).

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.uuid)
            // libsodium — needed for LibsodiumRandom in MigrationManager's
            // OPK key-id generation (16 random bytes per OPK). Already a
            // transitive dep via :shared:core:crypto and :shared:core:identity;
            // hoisted here so commonMain can reference the API directly.
            implementation(libs.libsodium.bindings)
            implementation(project(":shared:core:identity"))
            implementation(project(":shared:core:crypto"))
            implementation(project(":shared:core:storage"))
            implementation(project(":shared:core:transport"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.libsodium.bindings)
        }
    }
}

android {
    namespace = "phantom.core.messaging"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
