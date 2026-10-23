# Proguard rules for libtorrent4j JNI bridge
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
