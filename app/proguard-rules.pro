# Keep generic signatures and annotations for Gson
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep all models so R8 doesn't rename or strip fields
-keep class hh.kensakudl.app.model.** { *; }
-keepclassmembers class hh.kensakudl.app.model.** { *; }

# Keep ApiClient models and inner classes
-keep class hh.kensakudl.app.network.ApiClient$* { *; }
-keepclassmembers class hh.kensakudl.app.network.ApiClient$* { *; }

# Keep FFmpegKit native and JNI classes
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }

# OkHttp optional TLS platform dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**