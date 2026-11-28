# Preserve GeckoView's Java API and classes reached through JNI/reflection.
# R8 still optimizes JA's own application code.
-keep class org.mozilla.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# AGP 9.1+ repackages classes into the unnamed package by default. Keep
# original package names app-wide because libraries may derive resource,
# parcelizer, or reflective class names from Class.getPackage().
-dontrepackage

# GeckoView's DebugConfig uses SnakeYAML. TypeDescription initializes its
# logger with TypeDescription.class.getPackage().getName(), which fails if R8
# repackages the class into the unnamed package. SnakeYAML also reflects over
# DebugConfig fields, so preserve parser classes and their names.
-keeppackagenames org.yaml.snakeyaml.**
-keep class org.yaml.snakeyaml.** { *; }

# Preserve runtime metadata used by Java/Kotlin reflection and serialization.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Room discovers its generated implementation by database class name.
-keep class com.example.ja.AppDatabase { *; }
-keep class com.example.ja.AppDatabase_Impl { *; }
-keep class com.example.ja.WatchItem { *; }
