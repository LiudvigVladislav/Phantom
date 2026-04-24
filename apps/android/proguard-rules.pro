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
-keep class phantom.core.transport.KtorRelayTransport { *; }
-keep class phantom.core.messaging.MessagingLog_androidKt { *; }
-keep class phantom.core.messaging.DefaultMessagingService { *; }


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
# Stack traces — keep line numbers for crash reporting
# --------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
