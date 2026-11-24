# Keep debug line numbers for readable stack traces while shrinking unused code & resources
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, SourceFile, LineNumberTable
-dontnote kotlinx.serialization.AnnotationsKt

# Official kotlinx.serialization rules + keep all @Serializable models and enums intact across upgrades
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep @kotlinx.serialization.Serializable class com.houxinzhe.pib.** { *; }
-keep,includedescriptorclasses class com.houxinzhe.pib.**$$serializer { *; }
-keep enum com.houxinzhe.pib.** { *; }
-keepclassmembers class com.houxinzhe.pib.** {
    *** Companion;
}
-keepclasseswithmembers class com.houxinzhe.pib.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Android components referenced in AndroidManifest.xml
-keep class com.houxinzhe.pib.PibApp { *; }
-keep class com.houxinzhe.pib.MainActivity { *; }
-keep class com.houxinzhe.pib.service.** { *; }

# OkHttp / Okio / Conscrypt optional platform hooks
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
