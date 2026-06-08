// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
//
// RC-LIBXRAY-REALITY-WIRE1 (Trek 1) — STANDALONE DIAGNOSTIC MODULE.
//
// This is NOT a production module. It exists ONLY to discriminate the
// libXray Reality wire-stall hypothesis documented in memory pointer
// `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`. Per
// that mini-lock (Hard Gate 1): this module is diagnostic-only, MUST NOT
// be shipped as a release, MUST NOT be auto-built by CI as a shipping
// app, and MUST NOT depend on `:apps:android` to keep the bug surface
// isolated from the production messenger application.
//
// What it provides: a minimal Android Activity that starts a single
// `XrayService` instance with one Reality variant, opens an OkHttp call
// to `relay.phntm.pro/health` through the embedded SOCKS5 inbound, logs
// the wire-relevant events under tag `LIBXRAY_WIRE1`, and repeats per
// the iteration count passed via intent extra.
//
// Variant 1 (Baseline, this skeleton) uses the same Reality flow and
// network as production (`flow=xtls-rprx-vision`, `network=tcp`). Per
// the mini-lock's first-test-order gate: if Baseline on this standalone
// APK does NOT reproduce the single-segment Reality ClientHello stall
// observed in Arm G v10/v11, the matrix HALTS and the investigation
// pivots to Arm G harness integration. Only after Baseline reproduces
// the stall do Variants 2-4 (`flow=""` / `xhttp` / `httpupgrade`) get
// added in subsequent commits.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)
            // OkHttp arrives transitively via ktor-client-okhttp; we use the
            // OkHttp API directly (newCall + Proxy(SOCKS, ...)) to keep the
            // discriminator close to the Arm G v10 harness wire pattern and
            // avoid a second wrapper layer that could mask the stall.
            implementation(libs.ktor.client.okhttp)
            // `:shared:core:xray` provides XrayService + OperatorXrayConfig +
            // createXrayService(). Variant 1 / Baseline uses these unchanged.
            // Variants 2-4 will need a per-variant config JSON builder added
            // to this module in later commits (production XrayJsonBuilder
            // stays untouched).
            implementation(project(":shared:core:xray"))
        }
    }
}

android {
    // Separate applicationId from production `phantom.android` so the test
    // APK and production APK can co-exist on the same device. The mini-lock
    // does NOT require a fresh device — the field test runs side-by-side
    // with the production install (which stays as the Arm G v8/v10 evidence
    // baseline at commit 63f93f1c on the parent branch).
    namespace = "phantom.libxray.test"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "phantom.libxray.test"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-trek1-wire1-baseline"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        // Release build is configured to fail loudly rather than silently
        // produce a shippable artefact. The mini-lock Hard Gate 2 demands
        // that any release-build path produce no usable APK so accidental
        // promotion to a user device is impossible. We achieve that here
        // by disabling all release optimisations AND leaving signingConfig
        // unset — `assembleRelease` will then either fail to sign or
        // produce an unsigned APK that Android refuses to install.
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // No signingConfig assignment — unsigned release artefact.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
