# PHANTOM ProGuard / R8 rules.
#
# R8 renames fields and classes by default. Several PHANTOM dependencies
# communicate with native code via field name lookup (JNI GetFieldID) or
# via reflection. Native lookups fail silently if the target name has
# been obfuscated, producing UnsatisfiedLinkError at startup.
#
# Rule of thumb: anything on a crypto, DB, or serialization code path
# must either be kept or proven not to use reflection / JNI name lookup.


# --------------------------------------------------------------------------
# JNA — Java Native Access
# --------------------------------------------------------------------------
# com.sun.jna.Pointer exposes a `peer` field that the JNA native code
# (libjnidispatch.so) looks up by name via GetFieldID. If R8 renames it,
# the very first JNA call throws:
#   UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer
# Keep the whole com.sun.jna.** surface including members.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# JNA references AWT / Swing classes only for desktop use. Android doesn't
# ship those, and R8 warns on missing references — silence that noise.
-dontwarn java.awt.**
-dontwarn javax.swing.**


# --------------------------------------------------------------------------
# libsodium-kmp (ionspin multiplatform crypto)
# --------------------------------------------------------------------------
# The Android artifact delegates to lazysodium-java, which uses JNA to
# reach libsodium.so. Keep the Kotlin-side bindings, the lazysodium Java
# bridge, and the ResourceLoader that unpacks the .so at startup.
-keep class com.ionspin.kotlin.crypto.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keep class com.goterl.resourceloader.** { *; }


# --------------------------------------------------------------------------
# SQLCipher (net.zetetic / net.sqlcipher)
# --------------------------------------------------------------------------
# Loads a C++ SQLite build via JNI. Any missing symbol here and the local
# encrypted DB cannot be opened.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }


# --------------------------------------------------------------------------
# Any class with native methods
# --------------------------------------------------------------------------
# Catch-all: preserve the class name + native method signatures so native
# code can resolve them by symbol. This covers anything we miss above.
-keepclasseswithmembernames class * {
    native <methods>;
}


# --------------------------------------------------------------------------
# kotlinx.serialization
# --------------------------------------------------------------------------
# @Serializable classes are resolved at runtime via companion object
# lookups. R8 strips those companions unless we keep them, breaking
# MessagePayload and every wire-format data class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class <1>.<2> {
    <1>.<2>$Companion Companion;
}
-keepclassmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep every data class we ship over the wire so field names survive.
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}


# --------------------------------------------------------------------------
# PHANTOM transport — keep logger helpers so R8 does not inline them away
# when isMinifyEnabled is on, otherwise diagnostic output disappears in
# release builds exactly when we need it most.
# --------------------------------------------------------------------------
-keep class phantom.core.transport.RelayLog_androidKt { *; }
-keep class phantom.core.messaging.MessagingLog_androidKt { *; }
-keep class phantom.core.messaging.DefaultMessagingService { *; }

# --------------------------------------------------------------------------
# PHANTOM transport — narrowed `KtorRelayTransport` keeps (path-2 step 2)
# --------------------------------------------------------------------------
# Previously this file carried `-keep class phantom.core.transport.KtorRelayTransport { *; }`
# which preserved every member of the class in release. That wildcard is
# removed so future test seams (`*ForTest`), future debug surfaces
# (`debugForce*`), and `*Synthetic*` members get stripped from the release
# APK. R8 strip is verified by the `verifyR8StripsTestSeams` Gradle task
# wired as `finalizedBy assembleRelease`.
#
# The class itself is reached via the production `phantom.core.transport.RelayTransport`
# interface; R8 keeps interface implementations automatically. The members
# listed below are accessed BY NAME via the concrete `KtorRelayTransport`
# type (NOT via the `RelayTransport` interface), so R8 cannot prove they
# are reachable through interface dispatch and would strip them without
# an explicit keep.
#
# Each entry below cites the call site that needs it. If you add a new
# entry, justify in the commit message + PR body why R8 cannot reach it
# on its own. Do NOT collapse this list back to the over-broad shape
# `class ... { *; }` or `class ... { public *; }` — the structural pin
# test in `androidUnitTest` will fail.
-keepclassmembers class phantom.core.transport.KtorRelayTransport {
    # R3.6 lifecycle channel consumed by `HybridRelayTransport.startWsPassthroughCollectors`
    # at `apps/android/src/androidMain/kotlin/phantom/android/transport/HybridRelayTransport.kt:495`
    # via the concrete `wsTransport.wsSessionLifecycle` flow. Not on `RelayTransport`.
    public *** wsSessionLifecycle;
    public *** getWsSessionLifecycle();
    # PR-D1d ACK-deadline expiry flow consumed by `HybridRelayTransport`
    # at `HybridRelayTransport.kt:510`. Not on `RelayTransport`.
    public *** outboundAckDeadlineExpired;
    public *** getOutboundAckDeadlineExpired();
    # PR-RECV-DIAG1 inbound stall flow consumed by `HybridRelayTransport`
    # at `HybridRelayTransport.kt:553`. Not on `RelayTransport`.
    public *** inboundStalled;
    public *** getInboundStalled();
    # PR-D1c snapshot API consumed by `HybridRelayTransport.startRestFallbackMigration`
    # at `HybridRelayTransport.kt:894`. Returns the WS pending-outbound union;
    # the WS → REST migration path uses it to drain in encrypt-time order.
    # Not on `RelayTransport`.
    public *** snapshotPendingOutbound(...);
    # PR-D1c mark-accepted API consumed by `HybridRelayTransport` at
    # `HybridRelayTransport.kt:946` and `:954`. Removes a successfully
    # REST-fallback-delivered envelope from BOTH WS pending maps so a
    # later WS reconnect cannot re-flush a duplicate. Not on `RelayTransport`.
    public *** markPendingOutboundAcceptedByFallback(java.lang.String);
}


# --------------------------------------------------------------------------
# Ktor
# --------------------------------------------------------------------------
# Ktor uses kotlinx.serialization, coroutines, and reflective plugin
# discovery. The official recommendation is to keep the io.ktor package
# surface until Ktor ships its own consumer-rules.pro.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**


# --------------------------------------------------------------------------
# Kotlin reflection + coroutines
# --------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.debug.**


# --------------------------------------------------------------------------
# WebRTC (stream/webrtc-android)
# --------------------------------------------------------------------------
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-dontwarn org.webrtc.**


# --------------------------------------------------------------------------
# Firebase Cloud Messaging
# --------------------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**


# --------------------------------------------------------------------------
# CameraX + ML Kit (QR scanner)
# --------------------------------------------------------------------------
# CameraX uses reflection to resolve camera extension implementations. ML Kit's
# Barcode Scanning module loads model files and wires JNI callbacks whose names
# must survive R8 — failure here produces NPE in the ML Kit Task callback which
# surfaces as 'jb2.run' in the Handler main-thread stack trace.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**


# --------------------------------------------------------------------------
# Stack traces — keep line numbers for crash reporting
# --------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
