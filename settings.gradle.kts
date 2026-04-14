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

// iOS app is built via Xcode — not included here as a Gradle module.
// KMP framework is exported to apps/ios/ via the :shared:core:* modules.
