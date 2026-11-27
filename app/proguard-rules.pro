# Add project specific ProGuard rules here.

# QuickJS JNI & JS bridge
-keep interface com.atrishub.atrisstream.core.sourceengine.driver.AtrisHttpBridge { *; }
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.atrishub.atrisstream.**$$serializer { *; }
-keepclassmembers class com.atrishub.atrisstream.** {
    *** Companion;
}
-keepclasseswithmembers class com.atrishub.atrisstream.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Room Database, Entities, and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase_Impl
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Media3 (ExoPlayer / HLS / DASH / Extractors)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
