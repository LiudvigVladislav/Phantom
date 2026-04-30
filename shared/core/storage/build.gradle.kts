plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm()
    // iOS targets added when building KMP XCFramework on macOS (Alpha-1).
    // Kotlin/Native cross-compilation to iOS is not supported on Windows.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(project(":shared:core:identity"))
            implementation(project(":shared:core:crypto"))
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        create("PhantomDatabase") {
            packageName.set("phantom.core.storage.db")
            srcDirs("src/commonMain/sqldelight")
            version = 13
        }
    }
}

android {
    namespace = "phantom.core.storage"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
