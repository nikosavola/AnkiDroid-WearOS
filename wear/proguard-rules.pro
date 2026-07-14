# JNA resolves native symbols and maps types reflectively; R8 must not rename or strip its
# classes, nor the classes JNA instantiates/derives at runtime.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
# JNA's desktop (AWT) integration paths are never used on Android.
-dontwarn java.awt.*

# UniFFI bindings: the library interface's method names are looked up as native symbols via JNA,
# and record/enum classes are constructed through the generated FFI converters. Keep the whole
# generated package rather than chasing individual reflective edges.
-keep class uniffi.wearos_core.** { *; }
