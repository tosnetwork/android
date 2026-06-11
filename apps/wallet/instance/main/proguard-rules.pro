-keepattributes *Annotation*
-keepclassmembers class com.ton_keeper.** {
    @org.jetbrains.annotations.** <fields>;
    @org.jetbrains.annotations.** <methods>;
}

-keep class io.tonapi.** { *; }

-keep class io.batteryapi.** { *; }

-keep class com.google.j2objc.annotations.** { *; }

# Keep enum values to ensure correct deserialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep methods annotated with Retrofit annotations
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Moshi generated adapter methods
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keep class java.time.** { *; }

-keep class com.tonapps.tonkeeper.worker.** { *; }

-keep class com.tonapps.tonkeeper.manager.** { *; }

-keep class android.graphics.ColorSpace { *; }
-dontwarn android.graphics.ColorSpace
-dontwarn android.graphics.ColorSpace$**

-keep class org.koin.** { *; }
-keep class com.tonapps.tonkeeper.App { *; }

-keepnames class com.tonapps.tonkeeper.ui.screen.** { *; }

-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl
-keep class com.fasterxml.jackson.databind.ext.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.w3c.dom.**
-dontwarn com.fasterxml.jackson.databind.ext.DOMSerializer

-keep class com.facebook.imagepipeline.** { *; }
-dontwarn com.facebook.imagepipeline.**
-keep class com.facebook.imageutils.** { *; }
-dontwarn com.facebook.imageutils.**

# Strip all debug-level logging from release builds. R8 removes these calls
# (and the string concatenation feeding them) so no debug data — addresses,
# request bodies, tokens, mnemonics — can leak via logcat in production.
# Log.w / Log.e are kept for genuine error diagnostics (must never carry secrets).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}

