# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.spotineil.**$$serializer { *; }
-keepclassmembers class com.spotineil.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit (suspend functions, generic signatures)
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Spotify App Remote SDK uses Gson reflection on its protocol types
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }
-dontwarn com.spotify.**

# Tink / misc annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ONNX Runtime (karaoke voice model): its native code calls back into these classes by name
-keep class ai.onnxruntime.** { *; }
