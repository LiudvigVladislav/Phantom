import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("com.google.gms.google-services")
}

// Load release signing credentials from keystores/signing.properties (gitignored)
// or fall back to SIGNING_* env vars (for CI). If neither is available, the
// release build falls back to the debug signing config — lets contributors
// build release APKs locally without access to the production key.
val signingProps = Properties().apply {
    val f = rootProject.file("keystores/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propertyKey: String, envKey: String): String? =
    signingProps.getProperty(propertyKey) ?: System.getenv(envKey)

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.uuid)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.mlkit.barcode)
            implementation(libs.libsodium.bindings)
            implementation(libs.sqlcipher.android)
            implementation("androidx.biometric:biometric:1.1.0")
            // WebRTC for voice calls — provides PeerConnectionFactory, AudioTrack, IceCandidate.
            // stream/webrtc-android wraps Google's pre-built libwebrtc .aar so we avoid
            // compiling WebRTC from source (which requires depot_tools + Linux host).
            implementation("io.getstream:stream-webrtc-android:1.1.1")
            // FCM — silent push wakes the device so the WebSocket drains queued messages.
            // Requires google-services.json in apps/android/ and the plugin uncommented above.
            implementation("com.google.firebase:firebase-messaging-ktx:23.4.1")
            implementation(project(":shared:core:identity"))
            implementation(project(":shared:core:crypto"))
            implementation(project(":shared:core:storage"))
            implementation(project(":shared:core:transport"))
            implementation(project(":shared:core:messaging"))
        }
    }
}

android {
    namespace = "phantom.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "phantom.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-alpha"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFileProp = signingValue("storeFile", "SIGNING_STORE_FILE")
            val storePasswordProp = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            val keyAliasProp = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            val keyPasswordProp = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")

            if (storeFileProp != null && storePasswordProp != null &&
                keyAliasProp != null && keyPasswordProp != null
            ) {
                storeFile = rootProject.file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
            // If any field is null, this config is left unusable and release
            // below falls back to the debug signing config.
        }
    }

    buildTypes {
        debug {
            // Local network IP of the dev machine — phone and PC must be on same Wi-Fi.
            buildConfigField("String", "RELAY_URL", "\"ws://192.168.0.105:8080/ws\"")
            // No token in dev — relay runs without RELAY_SECRET_TOKEN (backward compatible).
            buildConfigField("String", "RELAY_TOKEN", "null")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "RELAY_URL", "\"wss://relay.phntm.pro/ws\"")
            // Override this via CI secrets: -PRELAY_TOKEN=<value>
            buildConfigField("String", "RELAY_TOKEN", "null")

            // Use the release key if keystores/signing.properties or SIGNING_*
            // env vars supplied valid credentials; otherwise fall back to debug
            // signing so contributors without the production key can still
            // build a release APK locally (it just won't be Play Store-ready).
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile != null) {
                releaseConfig
            } else {
                logger.warn("No release keystore configured — falling back to debug signing for release build.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
