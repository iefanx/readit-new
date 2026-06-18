# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Room compiler rules
-keep class * extends androidx.room.RoomDatabase
-keep class com.iefan.readout.data.** { *; }

# Moshi JSON serialization rules
-keep class com.iefan.readout.data.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-dontwarn com.squareup.moshi.**

# OkHttp & Retrofit rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# iTextG PDF processing rules
-dontwarn com.itextpdf.**
