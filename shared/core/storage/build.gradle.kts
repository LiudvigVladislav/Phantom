plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

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
            // SQLCipher: dependency is present and ready.
            // Replace AndroidSqliteDriver with SQLCipherDriver in DatabaseDriverFactory
            // during the hardening phase (post Alpha-0). See ADR-006.
            implementation(libs.sqlcipher.android)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
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
