# Room generates implementations reflectively at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# WorkManager workers are instantiated by name.
-keep class * extends androidx.work.ListenableWorker { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Enum names are persisted by Room TypeConverters as Strings; obfuscating the
# names would silently break reading back any previously written row.
-keepclassmembers enum com.elham.priorityringer.domain.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
