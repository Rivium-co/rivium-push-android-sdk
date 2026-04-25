# Rivium Push Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/co.rivium/rivium-push-android)](https://central.sonatype.com/artifact/co.rivium/rivium-push-android)

Native Android SDK for real-time push notifications. No Firebase dependency - full control over your push infrastructure.

## Features

- Real-time push via pn-protocol
- Survives app close and device restart
- Rich notifications with images, actions, and deep links
- In-app messaging (modal, banner, fullscreen, card)
- Message inbox with persistent storage
- Topic subscription for targeted messaging
- A/B testing support
- User segmentation
- Analytics and delivery tracking
- No Google/Firebase dependency

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.rivium:rivium-push-android:0.1.2")
}
```

### Permissions

The SDK requires these permissions (automatically merged via manifest):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

## Quick Start

### 1. Initialize in Application class

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = RiviumPushConfig(
            apiKey = "your_api_key_here"
        )
        RiviumPush.init(this, config)
    }
}
```

### 2. Register and listen for messages

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Set up callbacks
        RiviumPush.setCallback(object : RiviumPushCallbackAdapter() {
            override fun onMessageReceived(message: RiviumPushMessage) {
                Log.d("Push", "Title: ${message.title}, Body: ${message.body}")
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                Log.d("Push", "Connected: $connected")
            }

            override fun onRegistered(deviceId: String) {
                Log.d("Push", "Registered: $deviceId")
            }

            override fun onError(error: String) {
                Log.e("Push", "Error: $error")
            }
        })

        // Register device
        RiviumPush.register(
            userId = "user_123",  // optional
            metadata = mapOf(     // optional
                "plan" to "premium",
                "language" to "en"
            )
        )
    }
}
```

### 3. Handle notification taps

```kotlin
val initialMessage = RiviumPush.getInitialMessage()
if (initialMessage != null) {
    Log.d("Push", "Opened from notification: ${initialMessage.title}")
    RiviumPush.clearInitialMessage()
}
```

## Device Management

### User ID

```kotlin
// Set user ID (after login)
RiviumPush.setUserId("user_123")

// Clear user ID (after logout)
RiviumPush.clearUserId()
```

### Topics

```kotlin
// Subscribe
RiviumPush.subscribeToTopic("news")

// Unsubscribe
RiviumPush.unsubscribeFromTopic("news")
```

## Rich Notifications

| Field | Type | Description |
|-------|------|-------------|
| `imageUrl` | string | Large image in the notification |
| `actions` | array | Action buttons (max 3) |
| `sound` | string | Custom sound or "default" |
| `deepLink` | string | Deep link URI |
| `priority` | string | "high" or "normal" |
| `ttl` | number | Time-to-live in seconds |

## In-App Messages

```kotlin
RiviumPush.setInAppMessageCallback(object : InAppMessageCallback {
    override fun onMessageReady(message: InAppMessage) { }
    override fun onButtonClick(message: InAppMessage, button: InAppButton) { }
    override fun onMessageDismissed(message: InAppMessage) { }
})

// Trigger messages
RiviumPush.triggerInAppOnAppOpen()
RiviumPush.triggerInAppEvent("viewed_product")
```

## Message Inbox

```kotlin
// Fetch messages
RiviumPush.getInboxMessages(
    filter = InboxFilter(limit = 50),
    onSuccess = { response ->
        val messages = response.messages
        val unread = response.unreadCount
    },
    onError = { error -> Log.e("Inbox", error) }
)

// Real-time updates
RiviumPush.getInboxManager().setCallback(object : InboxCallback {
    override fun onMessageReceived(message: InboxMessage) {
        // New inbox message arrived
    }
    override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) { }
})

// Get unread count
val count = RiviumPush.getInboxManager().getUnreadCount()
```

## Configuration

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | String | **required** | Your API key from the dashboard |
| `notificationIcon` | String? | null | Notification icon resource name |
| `showNotificationInForeground` | Boolean | true | Show notifications when app is in foreground |
| `enableAnalytics` | Boolean | true | Send analytics events |
| `logLevel` | String | "debug" | none, error, warning, info, debug, verbose |

## VoIP Calls (Optional)

For incoming call UI, add the [VoIP SDK](https://github.com/Rivium-co/rivium-push-voip-android-sdk):

```kotlin
dependencies {
    implementation("co.rivium:rivium-push-android:0.1.2")
    implementation("co.rivium:rivium-push-voip:0.1.0")  // Optional
}
```

```kotlin
import co.rivium.push.voip.RiviumPushVoip
import co.rivium.push.voip.VoipConfig
import co.rivium.push.voip.VoipCallback

// Initialize
RiviumPushVoip.initialize(this, VoipConfig(appName = "MyApp"))

// Handle call events
RiviumPushVoip.setCallback(object : VoipCallback {
    override fun onCallAccepted(callData: CallData) {
        // Connect to your calling service (Jitsi, WebRTC)
    }
    override fun onCallDeclined(callData: CallData) { }
    override fun onCallTimeout(callData: CallData) { }
})
```

Send push with `{"type": "voip_call", "callerName": "John"}` to trigger the incoming call screen automatically.

The Push SDK works independently without VoIP. VoIP is only needed for apps with real calling features.

## Example App

The `example/` folder contains a complete demo app with:
- Push notification receiving
- In-app message triggers
- Inbox management
- A/B test variant assignment
- VoIP calling (simulate and receive)
- Settings and debugging tools

## Links

- [Rivium Push](https://rivium.co/cloud/rivium-push) - Learn more about Rivium Push
- [Documentation](https://rivium.co/cloud/rivium-push/docs/quick-start) - Full documentation and guides
- [Rivium Console](https://console.rivium.co) - Manage your push notifications

## License

MIT License - see [LICENSE](LICENSE) for details.
