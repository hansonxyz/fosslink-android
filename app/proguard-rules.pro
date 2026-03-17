# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep WebSocket listener callbacks
-keep class * implements okhttp3.WebSocketListener { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Keep JSON parsing (org.json is part of Android SDK, but keep our data classes)
-keep class xyz.hanson.fosslink.network.ProtocolMessage { *; }
