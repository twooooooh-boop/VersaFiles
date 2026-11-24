# Archive libraries use optional dependencies and reflection: keep them intact.
-keep class org.apache.commons.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn **
