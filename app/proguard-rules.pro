# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.aichat.sandbox.data.remote.** { *; }
-keep class com.aichat.sandbox.data.model.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
