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

// ─────────────────────────────────────────────────────────────────────────────
// CLIENT-PREKEY-SELFHEAL F7 / §6.7: single-implementation invariant for the
// `publishBundle` helper in PreKeyLifecycleService. The refactor consolidates
// what used to be two separate call surfaces (bootstrap/replenish/rotate vs
// force-join republish) into ONE helper with a `forceJoinInFlight` parameter
// and a `PublishExecutionOutcome` return type. This task fails the build if
// the file ever grows a second `suspend fun publishBundle`, or if the
// previously-scoped `publishBundleForceJoin` helper name is reintroduced.
// ─────────────────────────────────────────────────────────────────────────────
val assertSinglePublishBundleImplementation by tasks.registering {
    val serviceFile = file(
        "src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt",
    )
    inputs.file(serviceFile)
    doLast {
        val text = serviceFile.readText()
        val matches = text.lineSequence()
            .filter { it.matches(Regex("^\\s*(private\\s+)?suspend\\s+fun\\s+publishBundle\\b.*")) }
            .toList()
        check(matches.size == 1) {
            "CLIENT-PREKEY-SELFHEAL F7 invariant: expected exactly one " +
                "`publishBundle` implementation in PreKeyLifecycleService.kt, " +
                "found ${matches.size}:\n${matches.joinToString("\n")}"
        }
        check(!text.contains("publishBundleForceJoin")) {
            "CLIENT-PREKEY-SELFHEAL F7 invariant: `publishBundleForceJoin` " +
                "must not exist as a separate function; use " +
                "PublishExecutionOutcome return type instead."
        }
    }
}

tasks.named("check").configure { dependsOn(assertSinglePublishBundleImplementation) }

android {
    namespace = "phantom.core.messaging"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
