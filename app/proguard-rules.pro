# Archive libraries use optional dependencies and reflection: keep them intact.
-keep class org.apache.commons.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn **

# 7-Zip-JBinding uses JNI callbacks: keep everything
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
