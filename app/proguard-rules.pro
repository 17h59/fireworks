# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.fwchat.**$$serializer { *; }
-keepclassmembers class app.fwchat.** {
    *** Companion;
}
-keepclasseswithmembers class app.fwchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# --- Coroutines ---
-dontwarn kotlinx.coroutines.debug.**

# Traces lisibles
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
