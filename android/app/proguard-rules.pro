# kotlinx.serialization ships its own consumer rules; these cover the app's models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.mainnetwallet.polybot.** {
    *** Companion;
}
-keepclasseswithmembers class com.mainnetwallet.polybot.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
