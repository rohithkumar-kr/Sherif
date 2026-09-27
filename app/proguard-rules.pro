# SHERIF - R8 / ProGuard rules for release builds.
# R8 is enabled for release (isMinifyEnabled = true). These rules keep the
# generated serializers and reflection-heavy libraries working.

# --- kotlinx.serialization --------------------------------------------------
# R8 must keep the generated serializers and Companion objects for every
# @Serializable model so runtime serialization keeps working.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.example.aiinterviewapp.**$$serializer { *; }
-keepclassmembers class com.example.aiinterviewapp.** { *** Companion; }
-keepclasseswithmembers class com.example.aiinterviewapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit / OkHttp ------------------------------------------------------
-keepattributes Signature, Exceptions
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- PDFBox (tom_roush) -----------------------------------------------------
# PDFBox relies on reflection internally; keep everything to avoid runtime
# failures in resume extraction.
-keep class com.tom_roush.** { *; }
# JPEG-2000 (gemalto) support is an optional dependency not bundled with the
# app; the classes referenced by PDFBox's JPXFilter are intentionally absent.
-dontwarn com.gemalto.jp2.**

# --- Room -------------------------------------------------------------------
-keep @androidx.room.Entity class *