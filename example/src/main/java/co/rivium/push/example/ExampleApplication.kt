package co.rivium.push.example

import android.app.Application
import android.util.Log
import co.rivium.push.sdk.RiviumPush
import co.rivium.push.sdk.RiviumPushConfig
import co.rivium.push.sdk.RiviumPushLogLevel
// Uncomment if using VoIP module: implementation("co.rivium:rivium-push-voip-android:0.1.0")
// import co.rivium.push.voip.RiviumPushVoip
// import co.rivium.push.voip.VoipConfig

/**
 * Example Application class demonstrating Rivium Push SDK initialization.
 *
 * This is the first entry point where you MUST initialize the Rivium Push SDK.
 * The SDK should be initialized in Application.onCreate() before any activities start.
 */
class ExampleApplication : Application() {

    companion object {
        private const val TAG = "RiviumPushExample"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application onCreate - Initializing Rivium Push SDK")

        // Initialize Rivium Push SDK
        initializeRiviumPush()

        // Initialize VoIP (optional - only if your app handles calls)
        // initializeVoip()
    }

    private fun initializeRiviumPush() {
        // API key is read from local.properties (gitignored).
        // Copy local.properties.example → local.properties and fill in your values.
        val apiKey = BuildConfig.RIVIUM_PUSH_API_KEY

        // Create configuration - only apiKey is required
        val config = RiviumPushConfig(
            apiKey = apiKey,
            notificationIcon = "ic_notification",
            showServiceNotification = false  // Hide foreground service notification
        )

        // Initialize SDK
        RiviumPush.init(this, config)

        // Set log level (DEBUG for development, ERROR or NONE for production)
        RiviumPush.setLogLevel(RiviumPushLogLevel.DEBUG)

        Log.i(TAG, "Rivium Push SDK initialized (debug=${BuildConfig.DEBUG})")
    }

    // Uncomment if using VoIP module
    // private fun initializeVoip() {
    //     val voipConfig = VoipConfig(
    //         appName = getString(R.string.app_name),
    //         ringtoneUri = null,
    //         timeoutSeconds = 30,
    //         showMissedCallNotification = true,
    //         callerNameKey = "callerName",
    //         callerIdKey = "callerId",
    //         callerAvatarKey = "callerAvatar",
    //         callTypeKey = "callType"
    //     )
    //     RiviumPushVoip.initialize(this, voipConfig)
    //     Log.i(TAG, "Rivium Push VoIP initialized")
    // }
}
