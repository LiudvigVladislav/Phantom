rootProject.name = "phantom"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// ── Shared Core modules ──────────────────────────────────────────────────────
include(":shared:core:identity")
include(":shared:core:crypto")
include(":shared:core:storage")
include(":shared:core:transport")
include(":shared:core:xray")
include(":shared:core:messaging")
include(":shared:core:discovery")
include(":shared:core:policy")

// ── Shared Feature modules ───────────────────────────────────────────────────
include(":shared:features:onboarding")
include(":shared:features:chatlist")
include(":shared:features:chat")
include(":shared:features:profile")
include(":shared:features:settings")
include(":shared:features:message-requests")

// ── Platform adapters ────────────────────────────────────────────────────────
include(":shared:platform:secure-storage")
include(":shared:platform:background")
include(":shared:platform:connectivity")

// ── App shells ───────────────────────────────────────────────────────────────
include(":apps:android")

// ── Diagnostic-only modules ──────────────────────────────────────────────────
//
// RC-LIBXRAY-REALITY-WIRE1 (Trek 1) standalone test APK. Diagnostic-only —
// MUST NOT be promoted to a shipping artefact and MUST NOT depend on
// `:apps:android`. See `apps/android-libxray-test/README.md` for scope and
// the Trek 1 mini-lock memory file for the first-test-order gate. The
// `release` build is configured to fail-loud (unsigned APK) so accidental
// release-build paths cannot ship a working binary.
include(":apps:android-libxray-test")

// iOS app is built via Xcode — not included here as a Gradle module.
// KMP framework is exported to apps/ios/ via the :shared:core:* modules.
