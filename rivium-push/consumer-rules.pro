# Consumer ProGuard rules for Rivium Push SDK
-keep class co.rivium.push.sdk.** { *; }

# Keep PN Protocol classes
-keep class co.rivium.protocol.** { *; }

# Coil image loading library
-dontwarn coil.**
-keep class coil.** { *; }
-dontwarn coil.bitmap.**
