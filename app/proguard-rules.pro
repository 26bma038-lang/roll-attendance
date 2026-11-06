# Roll — R8 rules for release builds.
#
# Firebase, Play services, Credential Manager, Hilt, WorkManager and kotlinx.serialization
# ship their own consumer rules. Firestore documents are mapped by hand (no toObject()),
# so no model classes need keeping. What's left:

# Readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Type-safe navigation looks routes up through their generated serializers.
-keep @kotlinx.serialization.Serializable class app.roll.navigation.** { *; }
-keepclassmembers class app.roll.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Credential Manager loads its Play services provider reflectively.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Google ID token credential is created from a Bundle by class name.
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Glance creates widget action callbacks by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
