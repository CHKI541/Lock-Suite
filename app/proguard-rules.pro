# Proguard rules for LockSuite MDM

# Keep generic signatures and annotations
-keepattributes Signature,InnerClasses,EnclosingMethod,Deprecated,SourceFile,LineNumberTable,*Annotation*,Signature

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
