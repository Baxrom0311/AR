# Add project specific ProGuard rules here.

# Firebase Firestore data classes - keep for serialization
-keep class com.bakhrom.fizika.catalog.CelestialBody { *; }
-keep class com.bakhrom.fizika.catalog.Category { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Sceneform
-keep class com.google.ar.sceneform.** { *; }
-dontwarn com.google.ar.sceneform.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Lottie
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile