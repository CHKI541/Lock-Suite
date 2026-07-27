# Proguard rules for LockSuite MDM

# Keep generic signatures and annotations
-keepattributes Signature,InnerClasses,EnclosingMethod,Deprecated,SourceFile,LineNumberTable,*Annotation*,Signature

# Keep our own classes to prevent R8 from obfuscating/shrinking them and causing JNI/reflection crashes
-keep class com.ejemplo.locksuite.** { *; }

# Keep Room database implementations (crucial for WorkManager / androidx.work.impl.WorkDatabase)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Keep WorkManager classes
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Keep AndroidX Startup classes
-keep class androidx.startup.** { *; }

# Keep your model classes and serialized objects if any (e.g. settings/presets)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Add specific rules for MediaPipe / TensorFlow Lite
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Suppress warnings for missing proto classes referenced inside MediaPipe SDK
-dontwarn com.google.mediapipe.proto.**

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
