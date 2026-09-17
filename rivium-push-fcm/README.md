# Rivium Push FCM Add-on (Android)

Optional add-on that lets Rivium Push also deliver notifications through Firebase Cloud
Messaging (FCM). The core SDK (`co.rivium:rivium-push-android`) has no Firebase dependency and
works without this add-on. Only add it if your app already uses Firebase, or you want FCM as a
second delivery path for devices where the Rivium Push connection is often killed.

## How it works

- Rivium Push sends each message over its own connection **and** over FCM (when FCM is set up
  for the app in the Rivium console).
- Both copies go through the same handling in the SDK. The first copy is shown; the second is
  dropped by `messageId`, so users see one notification.
- Delivery receipts report which transport delivered the message (`pn` or `fcm`).
- The Rivium Push connection keeps running as before. In-app messages and inbox updates still
  use it.

## Installation

```kotlin
dependencies {
    implementation("co.rivium:rivium-push-android:<version>")
    implementation("co.rivium:rivium-push-fcm:0.1.0")
}
```

Set up Firebase as usual:

1. Add your Android app to a Firebase project and put `google-services.json` in the app module.
2. Apply the Google services Gradle plugin (`com.google.gms.google-services`).
3. Upload the Firebase service account for that project in the Rivium Push console.

No code is needed. The add-on starts through `androidx.startup`, fetches the FCM token in the
background and sends it with the next device registration (`RiviumPush.register()`, or a silent
re-registration when the token changes).

If Firebase is not configured, Google Play services are missing, or FCM is blocked on the
network, the add-on logs a debug message and does nothing. Notifications keep arriving through
the Rivium Push connection. It tries again on the next app start.

On Android 13+ the `POST_NOTIFICATIONS` permission is still needed, as for the core SDK.

## Apps that already have a FirebaseMessagingService

Android delivers FCM events to only one service. If your app has its own, forward Rivium
messages and tokens to `RiviumPushFcm`:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (RiviumPushFcm.handleMessage(message)) return // true if it was a Rivium message
        // your own handling
    }

    override fun onNewToken(token: String) {
        RiviumPushFcm.onNewToken(token)
        // your own handling
    }
}
```

and remove the add-on's service from the merged manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <service
            android:name="co.rivium.push.fcm.RiviumFcmMessagingService"
            tools:node="remove" />
    </application>
</manifest>
```

## Message format

Rivium messages are FCM data messages. `data["rivium"]` holds the same message JSON the SDK
receives over its own connection. If `data["truncated"] = "1"`, the server removed the image
and action buttons to fit FCM's 4 KB limit; the notification is still shown.

## Removing the add-on

Remove the dependency. The core SDK keeps working over its own connection.
