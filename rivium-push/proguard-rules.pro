# Rivium Push SDK ProGuard Rules

# Keep Rivium Push SDK classes
-keep class co.rivium.push.sdk.** { *; }

# Keep PN Protocol classes
-keep class co.rivium.protocol.** { *; }

# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
