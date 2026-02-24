# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.photowallpaper.**$$serializer { *; }
-keepclassmembers class com.example.photowallpaper.** { *** Companion; }
-keepclasseswithmembers class com.example.photowallpaper.** { kotlinx.serialization.KSerializer serializer(...); }

# ML Kit Image Labeling
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
