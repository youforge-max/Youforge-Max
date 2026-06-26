# Youforge-Max R8 keep rules.
#
# The native ML libraries reach Java/Kotlin members reflectively or over JNI, so
# R8 must not rename or strip them. Compose + Kotlin metadata are handled by the
# default android-optimize rules; only the native deps need explicit keeps.

# --- MediaPipe (GenAI LLM + vision) : JNI + reflection ---
-keep class com.google.mediapipe.** { *; }
-keep class mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
# AutoValue / proto plumbing MediaPipe pulls in.
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
# AutoValue's shaded annotation-processor classes leak onto the runtime classpath
# but are compile-time only (javax.lang.model.* exists in the JDK, not on Android).
-dontwarn autovalue.shaded.**
-dontwarn javax.lang.model.**

# --- Vosk (speech-to-text) : uses JNA, which is entirely reflection/native ---
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Keep native method names (JNI binds by name).
-keepclasseswithmembernames class * {
    native <methods>;
}
