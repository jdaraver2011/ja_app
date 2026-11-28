# Room locates its generated database implementation from the database class name.
-keep class com.example.ja.AppDatabase { *; }
-keep class com.example.ja.AppDatabase_Impl { *; }
-keep class com.example.ja.WatchItem { *; }

# GeckoView crosses JNI and loads extension/runtime components dynamically.
# Preserve its Java API names while allowing R8 to optimize the app's own code.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
