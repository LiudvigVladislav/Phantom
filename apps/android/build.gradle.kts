import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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

// Local dev overrides — values in local.properties or env vars override the
// defaults below. local.properties is gitignored (Android Studio default).
// Example for local relay on emulator: relay.url=ws://10.0.2.2:8080/ws
// Example for local relay on physical device: relay.url=ws://192.168.x.y:8080/ws
// Note: cleartext (ws://) is allowed to 10.0.2.2 and localhost only by
//       network_security_config.xml. Physical device local testing requires
//       adding the LAN IP there (not committed) or using wss:// via a tunnel.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(propKey: String, envKey: String, default: String): String =
    localProps.getProperty(propKey) ?: System.getenv(envKey) ?: default

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
            implementation(project(":shared:core:xray"))
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
            // Default: production relay. Override in local.properties (gitignored):
            //   relay.url=ws://10.0.2.2:8080/ws     ← emulator → host machine
            //   relay.url=ws://192.168.x.y:8080/ws  ← physical device → host machine
            // Cleartext (ws://) is only allowed to 10.0.2.2 / localhost by
            // network_security_config.xml; for a LAN IP you must also add it there locally.
            val relayUrl = localOrEnv("relay.url", "RELAY_URL", "wss://relay.phntm.pro/ws")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
            buildConfigField("String", "RELAY_TOKEN", "null")
            // Tor onion endpoint for the relay (ADR-016 Stage 2).
            // Wired in Stage 2B; Stage 2A only exposes the constant. Plain
            // HTTP/WS over the onion is intentional — Tor's circuit already
            // provides confidentiality, integrity and onion-address auth.
            val relayOnionUrl = localOrEnv(
                "relay.onion.url",
                "RELAY_ONION_URL",
                "ws://zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion:80/ws"
            )
            buildConfigField("String", "RELAY_ONION_URL", "\"$relayOnionUrl\"")
            // Stage 2C kill-switch for Tor transport. Privacy-Mode UI in
            // Stage 4 will replace this with a runtime preference; until then
            // a debug build can flip into Tor via local.properties:
            //   tor.enabled=true
            // Default false → existing direct-WSS path is unchanged.
            val torEnabled = localOrEnv("tor.enabled", "USE_TOR", "false").toBoolean()
            buildConfigField("boolean", "USE_TOR", "$torEnabled")
            // Stage 5E.B kill-switch for the Xray VLESS+REALITY outer
            // transport. Mutually exclusive with USE_TOR — both wrap the
            // same WebSocket but in different ways, and the wiring in
            // PhantomMessagingService picks one path. Enable via
            // local.properties:
            //   xray.enabled=true
            // The reachable RELAY URL stays clearnet (RELAY_URL); Xray
            // simply funnels the WSS through its local SOCKS5 listener
            // and out via VLESS+REALITY to the Hetzner Xray server.
            val xrayEnabled = localOrEnv("xray.enabled", "USE_XRAY", "false").toBoolean()
            if (torEnabled && xrayEnabled) {
                error(
                    "USE_TOR and USE_XRAY are mutually exclusive: both define the outer " +
                        "transport for the WebSocket. Set exactly one to true in local.properties.",
                )
            }
            buildConfigField("boolean", "USE_XRAY", "$xrayEnabled")
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
            buildConfigField(
                "String",
                "RELAY_ONION_URL",
                "\"ws://zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion:80/ws\""
            )
            // Release builds default to direct WSS — the Privacy-Mode UI
            // (Stage 4) will toggle this at runtime, not at build time.
            buildConfigField("boolean", "USE_TOR", "false")
            buildConfigField("boolean", "USE_XRAY", "false")

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

    // Required by kmp-tor:resource-noexec-tor 409.x (ADR-016 Stage 2).
    // The bundled tor JNI library must be extracted to
    // ApplicationInfo.nativeLibraryDir at install time so dlopen() can find
    // it; legacy packaging keeps the .so files uncompressed and out of the
    // base.apk asset blob. Combined with
    // android.bundle.enableUncompressedNativeLibs=false in gradle.properties.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
