# ---------------------------------------------------------------------------
# R8 / ProGuard rules for Animal Health Bridge (release builds only).
# ---------------------------------------------------------------------------

# --- Kotlin serialization --------------------------------------------------
# Generated serializers are looked up reflectively by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sohailsquad.animalhealthbridge.**$$serializer { *; }
-keepclassmembers class com.sohailsquad.animalhealthbridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.sohailsquad.animalhealthbridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- MediaPipe Tasks GenAI -------------------------------------------------
# The LLM Inference API is reached through JNI, so the Java entry points and
# the protobuf-lite message classes must survive shrinking.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# --- ONNX Runtime ----------------------------------------------------------
# ai.onnxruntime calls back into Java from native code.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- CameraX ---------------------------------------------------------------
-dontwarn androidx.camera.**

# --- Coroutines ------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Keep line numbers so offline crash reports stay readable during the hackathon.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile