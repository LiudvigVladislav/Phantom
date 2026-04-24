plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("com.google.gms.google-services")
}

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

    buildTypes {
        debug {
            // Local network IP of the dev machine — phone and PC must be on same Wi-Fi.
            buildConfigField("String", "RELAY_URL", "\"ws://192.168.0.105:8080/ws\"")
            // No token in dev — relay runs without RELAY_SECRET_TOKEN (backward compatible).
            buildConfigField("String", "RELAY_TOKEN", "null")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            buildConfigField("String", "RELAY_URL", "\"wss://relay.phntm.pro/ws\"")
            // Override this via CI secrets: -PRELAY_TOKEN=<value>
            buildConfigField("String", "RELAY_TOKEN", "null")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
