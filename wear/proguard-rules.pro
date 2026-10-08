# JNA binds the UniFFI native interface by reflection: the Library proxy's method names are the
# Rust symbol names, Structure fields are read by name in declared order, and callbacks are invoked
# reflectively. Everything else in the generated bindings is plain Kotlin that R8 may shrink.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn java.awt.*

# Glance Wear ships no consumer rules and maps widget services to widgets by reading this
# annotation reflectively (GlanceWearWidgetManager), so keep it on the services.
-keep class androidx.glance.wear.AssociateWithGlanceWearWidget
-keep @androidx.glance.wear.AssociateWithGlanceWearWidget class *
