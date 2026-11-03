# R8 rules for the release build. Library consumer rules cover Retrofit, OkHttp,
# kotlinx-serialization, Room, Hilt, Media3 and the local FFmpeg decoder.

# libtorrent4j ships no consumer rules; its SWIG JNI glue resolves classes, methods and fields by name.
-keep class org.libtorrent4j.swig.** { *; }

# ML Kit (firebase-components) instantiates registrars listed in the manifest via reflection;
# R8 full mode drops their no-arg constructors without this.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
