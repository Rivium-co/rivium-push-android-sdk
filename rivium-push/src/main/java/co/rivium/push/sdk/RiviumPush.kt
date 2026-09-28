package co.rivium.push.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import co.rivium.push.sdk.inapp.InAppMessage
import co.rivium.push.sdk.inapp.InAppMessageCallback
import co.rivium.push.sdk.inapp.InAppMessageManager
import co.rivium.push.sdk.abtesting.ABTestingCallback
import co.rivium.push.sdk.abtesting.ABTestingManager
import co.rivium.push.sdk.abtesting.ABTestVariant
import co.rivium.push.sdk.abtesting.ABTestSummary
import co.rivium.push.sdk.abtesting.ABTestEvent
import co.rivium.push.sdk.inbox.InboxCallback
import co.rivium.push.sdk.inbox.InboxFilter
import co.rivium.push.sdk.inbox.InboxManager
import co.rivium.push.sdk.inbox.InboxMessage
import co.rivium.push.sdk.inbox.InboxMessageStatus
import co.rivium.push.sdk.inbox.InboxMessagesResponse
import co.rivium.push.sdk.internal.InstallId
import co.rivium.push.sdk.internal.MqttEndpoints
import co.rivium.push.sdk.internal.PrefsEndpointStore
import co.rivium.push.sdk.internal.RegistrationRefreshPolicy
import co.rivium.push.sdk.internal.SdkIdentity

/**
 * Main entry point for Rivium Push SDK
 *
 * Only apiKey is required. pn-protocol configuration is automatically
 * fetched from the server during registration.
 *
 * Usage:
 * ```kotlin
 * val config = RiviumPushConfig(
 *     apiKey = "rv_live_your_api_key"
 * )
 *
 * RiviumPush.init(context, config)
 * RiviumPush.setCallback(object : RiviumPushCallbackAdapter() {
 *     override fun onMessageReceived(message: RiviumPushMessage) {
 *         // Handle message
 *     }
 * })
 * RiviumPush.register()
 * ```
 */
object RiviumPush {
    /** Version of this native SDK, e.g. "0.1.12". */
    const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private const val TAG = "RiviumPush"
    private const val PREFS_NAME = "rivium_push_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_APP_VERSION = "app_version"
    private const val KEY_LAST_REGISTER_AT = "last_register_at"
    private const val KEY_REGISTER_FINGERPRINT = "register_fingerprint"
    private const val KEY_REGISTER_METADATA = "register_metadata"
    private const val KEY_REGISTERED_USER_ID = "registered_user_id"
    private const val KEY_TRANSPORT_FIELDS = "transport_register_fields"

    /** Grace period so an explicit register() right after init() wins over auto-refresh. */
    private const val AUTO_REFRESH_DELAY_MS = 5_000L

    private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 10001

    private var context: Context? = null
    private var config: RiviumPushConfig? = null
    private var apiClient: ApiClient? = null
    private var callback: RiviumPushCallback? = null
    private var deviceId: String? = null
    private var subscriptionId: String? = null
    private var appId: String? = null
    private var currentUserId: String? = null
    private var notificationPermissionRequested = false
    @Volatile private var explicitRegisterRequested = false
    @Volatile private var autoRefreshScheduled = false
    @Volatile private var pendingTransportRefresh = false

    /**
     * Extension point for official add-ons (e.g. co.rivium:rivium-push-fcm).
     * Not for app use.
     */
    @RiviumPushInternalApi
    val internalTransports: RiviumPushTransports
        get() = RiviumPushTransports

    /**
     * Initialize Rivium Push SDK
     */
    fun init(context: Context, config: RiviumPushConfig) {
        this.context = context.applicationContext
        this.config = config
        this.apiClient = ApiClient(config)
        this.deviceId = getOrCreateDeviceId(context)

        // Restore previously-issued subscriptionId so the foreground service can
        // subscribe to the new topic immediately on boot — register() will refresh it.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        this.subscriptionId = prefs.getString(KEY_SUBSCRIPTION_ID, null)
        // Restore the userId set in a previous session so inbox queries hit the
        // right backend rows immediately on app launch (without requiring the
        // host app to call setUserId on every cold start).
        this.currentUserId = prefs.getString(KEY_USER_ID, null)

        // Use API key hash as app ID for pn-protocol topics
        this.appId = config.apiKey.take(16)

        Log.d(TAG, "RiviumPush initialized with deviceId: $deviceId")

        // Save config for boot recovery
        saveConfig(context, config)

        // Check for app update
        checkForAppUpdate(context)

        // Keep a previously registered install fresh (app/SDK upgrades, userId, 24h)
        if (config.autoRefresh) {
            scheduleAutoRefresh(context.applicationContext)
        }

        // An add-on transport (FCM) reported new fields before init() ran in this process
        if (pendingTransportRefresh) {
            pendingTransportRefresh = false
            scheduleTransportRefresh(context.applicationContext, AUTO_REFRESH_DELAY_MS)
        }
    }

    private fun saveConfig(context: Context, config: RiviumPushConfig) {
        // Save config on background thread to avoid ANR
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("apiKey", config.apiKey)
                .putString("notificationIcon", config.notificationIcon)
                .putBoolean("showServiceNotification", config.showServiceNotification)
                .putString("wrapperSdkName", config.wrapperSdkName)
                .putString("wrapperSdkVersion", config.wrapperSdkVersion)
                // Save appIdentifier (packageName) for boot recovery
                .putString("appIdentifier", context.packageName)
                .apply()
        }
    }

    private fun saveAppId(context: Context, appId: String) {
        // Save server-provided appId for boot recovery
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("appId", appId)
                .apply()
        }
    }

    private fun savePNHost(context: Context, pnHost: String) {
        // Save PN Protocol host for boot recovery
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("pnHost", pnHost)
                .apply()
        }
    }

    private fun saveMqttEndpoints(context: Context, endpoints: List<co.rivium.protocol.PNEndpoint>) {
        try {
            // apply() updates the in-memory prefs at once, so the push service started
            // right after registration already sees the list.
            PrefsEndpointStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
                .saveServerEndpoints(endpoints)
            if (endpoints.isNotEmpty()) Log.d(TAG, "Stored ${endpoints.size} PN endpoint(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store PN endpoints: ${e.message}")
        }
    }

    private fun savePNToken(context: Context, pnToken: String) {
        // Save PN Protocol JWT token for boot recovery
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("pnToken", pnToken)
                .apply()
        }
    }

    /**
     * Set callback for push events
     */
    fun setCallback(callback: RiviumPushCallback) {
        this.callback = callback
        RiviumPushService.callback = callback
    }

    /**
     * Set log level for SDK logging
     */
    fun setLogLevel(level: RiviumPushLogLevel) {
        RiviumPushLogger.logLevel = level
        Log.d(TAG, "Log level set to: $level")
    }

    /**
     * Request notification permission on Android 13+ (API 33+).
     * Call this from an Activity before or after register().
     * On older Android versions, this is a no-op (permission is granted at install time).
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * Register device and start push service.
     *
     * If [userId] is null, the SDK falls back to the persisted userId from a
     * previous session (matches OneSignal/Airship). Pass an explicit userId
     * only when associating a new identity. Use [clearUserId] to dissociate.
     */
    fun register(userId: String? = null, metadata: Map<String, Any>? = null) {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val cfg = config ?: throw IllegalStateException("RiviumPush not initialized")
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val devId = deviceId ?: throw IllegalStateException("Device ID not available")

        explicitRegisterRequested = true

        // Fall back to the previously-persisted userId so the host app can
        // call register() on every launch without forgetting the user identity.
        val effectiveUserId = userId ?: currentUserId

        Log.d(TAG, "Registering device: $devId, userId: $effectiveUserId" +
                if (userId == null && effectiveUserId != null) " (restored from previous session)" else "")

        // Fetch push config from server if not already fetched
        if (!cfg.hasPushConfig()) {
            Log.d(TAG, "Fetching push config from server...")
            client.fetchPushConfigAsync(object : ApiClient.ApiCallback<ApiClient.PushConfig> {
                override fun onSuccess(pushConfig: ApiClient.PushConfig) {
                    Log.d(TAG, "Push config fetched: host=${pushConfig.host}, port=${pushConfig.port}")
                    cfg.updatePushConfig(host = pushConfig.host, port = pushConfig.port, username = pushConfig.username, password = pushConfig.password)
                    // Now proceed with registration
                    doRegister(ctx, cfg, client, devId, effectiveUserId, metadata)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Failed to fetch push config: $error")
                    callback?.onError("Failed to fetch push config: $error")
                    callback?.onDetailedError(RiviumPushError(RiviumPushErrorCode.CONFIGURATION_FAILED, error))
                }
            })
        } else {
            // Push config already available, proceed with registration
            doRegister(ctx, cfg, client, devId, effectiveUserId, metadata)
        }
    }

    /**
     * @param silent true for init()-triggered auto-refresh: no callbacks, and the
     *        push service is not started (it may not be allowed from background).
     */
    private fun doRegister(
        ctx: Context,
        cfg: RiviumPushConfig,
        client: ApiClient,
        devId: String,
        userId: String?,
        metadata: Map<String, Any>?,
        silent: Boolean = false
    ) {
        // Store userId for InboxManager
        currentUserId = userId

        // Register with server (pass packageName as appIdentifier for per-app isolation)
        val appIdentifier = ctx.packageName

        // Auto-captured device attributes — sent as top-level fields so the
        // dashboard can offer typed segment filters (appVersion >, country =).
        val attrs = captureDeviceAttributes(ctx)

        // Fields from add-on transports (e.g. fcmToken). Empty without add-ons,
        // in which case the request is unchanged.
        @OptIn(RiviumPushInternalApi::class)
        val transportFields = RiviumPushTransports.collectRegisterFields(ctx)

        client.registerDevice(
            devId,
            userId,
            metadata,
            appIdentifier,
            appVersion = attrs.appVersion,
            appBuild = attrs.appBuild,
            osVersion = attrs.osVersion,
            deviceModel = attrs.deviceModel,
            language = attrs.language,
            country = attrs.country,
            timezone = attrs.timezone,
            installId = InstallId.get(ctx),
            extraFields = transportFields.takeIf { it.isNotEmpty() },
            callback = object : ApiClient.ApiCallback<ApiClient.RegisterResponse> {
            override fun onSuccess(response: ApiClient.RegisterResponse) {
                Log.d(TAG, "Device registered: ${response.deviceId}, appId: ${response.appId}")

                // Update appId from server response if provided (projectId-based)
                if (!response.appId.isNullOrEmpty()) {
                    appId = response.appId
                    Log.d(TAG, "Using server-provided appId: $appId")
                    // Save appId for boot recovery
                    saveAppId(ctx, response.appId)
                }

                // Capture subscriptionId — the per-install UUID — and persist it.
                if (!response.subscriptionId.isNullOrEmpty()) {
                    subscriptionId = response.subscriptionId
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SUBSCRIPTION_ID, response.subscriptionId)
                        .apply()
                    Log.d(TAG, "Stored subscriptionId: ${response.subscriptionId}")
                }

                // Update PN Protocol config from registration response (includes JWT token and TLS setting)
                response.mqtt?.let { pnConfig ->
                    Log.d(TAG, "Updating PN config: host=${pnConfig.host}, port=${pnConfig.port}, secure=${pnConfig.secure}, hasToken=${pnConfig.token != null}")
                    cfg.updatePushConfig(pnConfig.host, pnConfig.port, pnConfig.secure, pnConfig.token)
                    // Save PN host and token for boot recovery
                    savePNHost(ctx, pnConfig.host)
                    pnConfig.token?.let { savePNToken(ctx, it) }
                }

                // Optional failover endpoints. Absent = default endpoint only (clears any old list).
                saveMqttEndpoints(ctx, MqttEndpoints.parse(response.mqttEndpoints))

                // Update InboxManager with userId if it exists
                inboxManager?.setUserId(userId)

                // Remember what was registered so init() can tell when a refresh is due
                recordSuccessfulRegistration(ctx, cfg, attrs, userId, metadata, transportFields)

                if (silent) {
                    Log.d(TAG, "Registration refreshed")
                    return
                }

                callback?.onRegistered(response.deviceId)

                // Start foreground service
                startService(ctx, cfg)

                // Save state
                saveServiceState(ctx, true)
            }

            override fun onError(error: String) {
                if (silent) {
                    Log.w(TAG, "Registration refresh failed: $error")
                    return
                }
                Log.e(TAG, "Registration failed: $error")
                callback?.onError(error)
                callback?.onDetailedError(RiviumPushError(RiviumPushErrorCode.REGISTRATION_FAILED, error))
            }
        })
    }

    /**
     * Subscribe to a topic
     */
    fun subscribeTopic(topic: String) {
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val devId = deviceId ?: throw IllegalStateException("Device ID not available")

        Log.d(TAG, "Subscribing to topic: $topic")

        client.subscribeTopic(devId, topic, object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) {
                Log.d(TAG, "Subscribed to topic: $topic")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Failed to subscribe to topic: $error")
                callback?.onError(error)
                callback?.onDetailedError(RiviumPushError(RiviumPushErrorCode.SUBSCRIPTION_FAILED, error))
            }
        })
    }

    /**
     * Unsubscribe from a topic
     */
    fun unsubscribeTopic(topic: String) {
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val devId = deviceId ?: throw IllegalStateException("Device ID not available")

        Log.d(TAG, "Unsubscribing from topic: $topic")

        client.unsubscribeTopic(devId, topic, object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) {
                Log.d(TAG, "Unsubscribed from topic: $topic")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Failed to unsubscribe from topic: $error")
                callback?.onError(error)
                callback?.onDetailedError(RiviumPushError(RiviumPushErrorCode.UNSUBSCRIPTION_FAILED, error))
            }
        })
    }

    /**
     * Set user ID for the current device.
     *
     * Persisted across app launches (matches OneSignal/Airship behaviour) so
     * inbox queries and any future user-scoped APIs hit the right backend rows
     * immediately on cold start. Call [clearUserId] on logout to wipe it.
     */
    fun setUserId(userId: String) {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val devId = deviceId ?: throw IllegalStateException("Device ID not available")

        Log.d(TAG, "Setting user ID: $userId")

        // Store userId locally and persist for next launch
        currentUserId = userId
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_ID, userId)
            .apply()

        // Update InboxManager
        inboxManager?.setUserId(userId)

        client.setUserId(devId, userId, object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) {
                Log.d(TAG, "User ID set: $userId")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Failed to set user ID: $error")
                callback?.onError(error)
            }
        })
    }

    /**
     * Clear user ID for the current device. Call this on logout.
     */
    fun clearUserId() {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val devId = deviceId ?: throw IllegalStateException("Device ID not available")

        Log.d(TAG, "Clearing user ID")

        // Clear userId locally and on disk
        currentUserId = null
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_USER_ID)
            .apply()

        // Update InboxManager
        inboxManager?.setUserId(null)

        client.clearUserId(devId, object : ApiClient.ApiCallback<String> {
            override fun onSuccess(response: String) {
                Log.d(TAG, "User ID cleared")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Failed to clear user ID: $error")
                callback?.onError(error)
            }
        })
    }

    /**
     * Get the currently-stored userId, if any. Survives app restarts.
     */
    fun getUserId(): String? = currentUserId

    /**
     * Get the message that launched the app (when user tapped a notification)
     */
    fun getInitialMessage(): RiviumPushMessage? {
        val ctx = context ?: return null
        return NotificationHelper.getInitialMessage(ctx)
    }

    /**
     * Clear the initial message after handling
     */
    fun clearInitialMessage() {
        val ctx = context ?: return
        NotificationHelper.clearInitialMessage(ctx)
    }

    /**
     * Get the clicked action from a notification
     */
    fun getClickedAction(): Map<String, String?>? {
        val ctx = context ?: return null
        return NotificationHelper.getClickedAction(ctx)
    }

    /**
     * Inspect an Activity intent for a notification tap and, if found, fire the
     * onNotificationTapped callback, track the A/B click, and (when no live
     * callback is registered yet) store it as the initial message.
     *
     * Call this from your Activity's onCreate and onNewIntent so taps from the
     * status-bar shade — which launch the activity via PendingIntent.getActivity
     * to avoid Android 10+ background-launch restrictions — are delivered to
     * your app. The Flutter plugin invokes this automatically.
     *
     * The relevant extra is consumed so repeated calls on the same intent are
     * safe; returns true if a tap was processed.
     */
    fun handleNotificationIntent(intent: Intent?): Boolean {
        val ctx = context ?: return false
        if (intent == null) return false
        if (!intent.getBooleanExtra(NotificationHelper.EXTRA_RIVIUM_NOTIFICATION_TAP, false)) {
            return false
        }
        // Consume so onNewIntent re-deliveries (config changes etc.) don't refire.
        intent.removeExtra(NotificationHelper.EXTRA_RIVIUM_NOTIFICATION_TAP)

        val messageJson = intent.getStringExtra(NotificationHelper.EXTRA_MESSAGE_JSON)
        val abTestId = intent.getStringExtra(NotificationActionReceiver.EXTRA_AB_TEST_ID)
        val variantId = intent.getStringExtra(NotificationActionReceiver.EXTRA_VARIANT_ID)

        // Best-effort A/B click tracking (no-op if the IDs are absent).
        if (!abTestId.isNullOrBlank() && !variantId.isNullOrBlank()) {
            try {
                getABTestingManager().trackClicked(
                    testId = abTestId,
                    variantId = variantId,
                    onSuccess = { Log.d(TAG, "A/B click tracked from tap intent") },
                    onError = { err -> Log.e(TAG, "A/B click track failed: $err") }
                )
            } catch (e: Exception) {
                Log.e(TAG, "A/B click tracking threw: ${e.message}")
            }
        }

        if (messageJson.isNullOrBlank()) return true

        val message = try {
            val map = com.google.gson.Gson().fromJson(
                messageJson,
                object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            ) as? Map<String, Any?>
            map?.let { RiviumPushMessage.fromMap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tapped message JSON: ${e.message}")
            null
        } ?: return true

        val cb = callback
        if (cb != null) {
            // Deliver on the main thread for parity with the receiver path.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                cb.onNotificationTapped(message)
            }
        } else {
            // No callback yet — store so getInitialMessage() can pick it up.
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("initial_message", messageJson)
                .apply()
        }
        return true
    }

    private fun startService(context: Context, config: RiviumPushConfig) {
        RiviumPushService.config = config
        RiviumPushService.appId = appId
        RiviumPushService.deviceId = deviceId
        RiviumPushService.appIdentifier = context.packageName
        RiviumPushService.subscriptionId = subscriptionId
        RiviumPushService.callback = callback

        val intent = Intent(context, RiviumPushService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d(TAG, "Service started")
    }

    /**
     * Stop push service
     */
    fun unregister() {
        val ctx = context ?: return
        ctx.stopService(Intent(ctx, RiviumPushService::class.java))
        saveServiceState(ctx, false)
        Log.d(TAG, "Service stopped")
    }

    /**
     * Check if pn-protocol is connected
     */
    fun isConnected(): Boolean {
        return RiviumPushService.isConnected()
    }

    /**
     * Get current device ID
     */
    fun getDeviceId(): String? = deviceId

    /** Live config when init() ran in this process, else null. */
    internal fun configOrNull(): RiviumPushConfig? = config

    /**
     * Get the per-install subscription ID issued by the server during register().
     * This is the canonical addressing key for inbox/A-B/in-app calls and the new
     * MQTT topic. Returns null until register() succeeds.
     */
    fun getSubscriptionId(): String? = subscriptionId

    @SuppressLint("HardwareIds")
    private fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)

        if (id == null) {
            // Generate device ID using Android ID
            id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: java.util.UUID.randomUUID().toString()

            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            Log.d(TAG, "Generated new device ID: $id")
        }

        return id
    }

    private data class DeviceAttributes(
        val appVersion: String?,
        val appBuild: Long?,
        val osVersion: String?,
        val deviceModel: String?,
        val language: String?,
        val country: String?,
        val timezone: String?
    )

    /**
     * Read platform-native device attributes. Sent on every register() so
     * the dashboard can segment by app version, OS, locale, timezone, etc.
     * without customer apps having to populate metadata manually.
     */
    private fun captureDeviceAttributes(context: Context): DeviceAttributes {
        var appVersion: String? = null
        var appBuild: Long? = null
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion = pi.versionName
            appBuild = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (_: Exception) {
        }

        val locale = java.util.Locale.getDefault()

        return DeviceAttributes(
            appVersion = appVersion,
            appBuild = appBuild,
            osVersion = android.os.Build.VERSION.RELEASE,
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            language = locale.language.ifEmpty { null },
            country = locale.country.ifEmpty { null },
            timezone = java.util.TimeZone.getDefault().id
        )
    }

    private fun recordSuccessfulRegistration(
        ctx: Context,
        cfg: RiviumPushConfig,
        attrs: DeviceAttributes,
        userId: String?,
        metadata: Map<String, Any>?,
        transportFields: Map<String, Any?>
    ) {
        val fingerprint = RegistrationRefreshPolicy.fingerprint(
            attrs.appVersion, attrs.appBuild, SdkIdentity.from(cfg), userId
        )
        // Persist an explicitly registered userId so later launches keep it (see register()).
        if (userId != null) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_ID, userId)
                .apply()
        }
        val metadataJson = metadata?.let {
            try { com.google.gson.Gson().toJson(it) } catch (_: Exception) { null }
        }
        RiviumPushExecutors.executeIO {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_REGISTER_AT, System.currentTimeMillis())
                .putString(KEY_REGISTER_FINGERPRINT, fingerprint)
                .putString(KEY_REGISTER_METADATA, metadataJson)
                .putString(KEY_REGISTERED_USER_ID, userId)
                .putString(KEY_TRANSPORT_FIELDS, transportFieldsJson(transportFields))
                .apply()
        }
    }

    /**
     * OneSignal-style "on session" refresh. Runs off the main thread after a short
     * grace period; skipped if the app calls register() itself in the meantime.
     * Never throws, never prompts, never starts the push service.
     */
    private fun scheduleAutoRefresh(ctx: Context) {
        if (autoRefreshScheduled) return
        autoRefreshScheduled = true
        try {
            RiviumPushExecutors.scheduleBackground(AUTO_REFRESH_DELAY_MS) {
                try {
                    runAutoRefresh(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-refresh skipped: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-refresh not scheduled: ${e.message}")
        }
    }

    private fun runAutoRefresh(ctx: Context) {
        if (explicitRegisterRequested) {
            Log.d(TAG, "Auto-refresh skipped: register() called by app")
            return
        }
        val cfg = config ?: return
        val client = apiClient ?: return
        val devId = deviceId ?: return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAt = prefs.getLong(KEY_LAST_REGISTER_AT, 0L)
        val hasRegisteredBefore = prefs.getBoolean(KEY_SERVICE_ENABLED, false) &&
            (lastAt > 0L || prefs.getString(KEY_SUBSCRIPTION_ID, null) != null)

        val attrs = captureDeviceAttributes(ctx)
        val userId = currentUserId
        val reason = RegistrationRefreshPolicy.decide(
            hasRegisteredBefore = hasRegisteredBefore,
            lastRegisteredAtMs = lastAt,
            nowMs = System.currentTimeMillis(),
            storedFingerprint = prefs.getString(KEY_REGISTER_FINGERPRINT, null),
            currentFingerprint = RegistrationRefreshPolicy.fingerprint(
                attrs.appVersion, attrs.appBuild, SdkIdentity.from(cfg), userId
            )
        )
        if (reason == null) {
            Log.d(TAG, "Auto-refresh not needed")
            return
        }

        val metadata = storedMetadata(prefs)

        // The server treats a missing userId as "keep existing", so a register
        // with userId=null cannot detach a logged-out user. If the last register
        // carried a userId and the app has since cleared it (and that DELETE may
        // have failed, e.g. offline), detach explicitly before refreshing.
        val detachUser = userId == null && prefs.getString(KEY_REGISTERED_USER_ID, null) != null

        Log.d(TAG, "Auto-refreshing registration ($reason)")
        RiviumPushExecutors.executeMain {
            // Re-check: the app may have called register() while we were deciding.
            if (explicitRegisterRequested) return@executeMain
            if (detachUser && currentUserId == null) {
                client.clearUserId(devId, object : ApiClient.ApiCallback<String> {
                    override fun onSuccess(response: String) { Log.d(TAG, "Stale user detached") }
                    override fun onError(error: String) { Log.w(TAG, "Detach user failed: $error") }
                })
            }
            doRegister(ctx, cfg, client, devId, userId, metadata, silent = true)
        }
    }

    private fun storedMetadata(prefs: android.content.SharedPreferences): Map<String, Any>? {
        return prefs.getString(KEY_REGISTER_METADATA, null)?.let {
            try {
                com.google.gson.Gson().fromJson<Map<String, Any>>(
                    it, object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                )
            } catch (_: Exception) { null }
        }
    }

    private fun transportFieldsJson(fields: Map<String, Any?>): String =
        com.google.gson.GsonBuilder().serializeNulls().create().toJson(fields)

    /**
     * Called by an add-on transport when its register fields changed (e.g. new
     * FCM token). Re-registers silently, off the main thread, only for installs
     * that registered before — a first register() already includes the fields.
     */
    internal fun refreshTransportRegistration(ctx: Context) {
        if (config == null) {
            // init() has not run in this process yet; refresh right after it does.
            pendingTransportRefresh = true
            return
        }
        scheduleTransportRefresh(ctx, 0L)
    }

    private fun scheduleTransportRefresh(ctx: Context, delayMs: Long) {
        try {
            RiviumPushExecutors.scheduleBackground(delayMs) {
                try {
                    runTransportRefresh(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "Transport refresh skipped: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transport refresh not scheduled: ${e.message}")
        }
    }

    @OptIn(RiviumPushInternalApi::class)
    private fun runTransportRefresh(ctx: Context) {
        val cfg = config ?: return
        val client = apiClient ?: return
        val devId = deviceId ?: return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasRegisteredBefore = prefs.getBoolean(KEY_SERVICE_ENABLED, false) &&
            (prefs.getLong(KEY_LAST_REGISTER_AT, 0L) > 0L || prefs.getString(KEY_SUBSCRIPTION_ID, null) != null)
        if (!hasRegisteredBefore) {
            Log.d(TAG, "Transport refresh skipped: device not registered yet")
            return
        }

        val current = transportFieldsJson(RiviumPushTransports.collectRegisterFields(ctx))
        val lastSent = prefs.getString(KEY_TRANSPORT_FIELDS, null) ?: transportFieldsJson(emptyMap())
        if (current == lastSent) {
            Log.d(TAG, "Transport refresh not needed")
            return
        }

        val metadata = storedMetadata(prefs)
        Log.d(TAG, "Refreshing registration (transport fields changed)")
        RiviumPushExecutors.executeMain {
            doRegister(ctx, cfg, client, devId, currentUserId, metadata, silent = true)
        }
    }

    private fun saveServiceState(context: Context, enabled: Boolean) {
        // Save state on background thread to avoid ANR
        RiviumPushExecutors.executeIO {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SERVICE_ENABLED, enabled)
                .apply()
        }
    }

    private fun checkForAppUpdate(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedVersion = prefs.getString(KEY_APP_VERSION, null)
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"

            if (savedVersion != null && savedVersion != currentVersion) {
                Log.d(TAG, "App updated from $savedVersion to $currentVersion")
                callback?.onAppUpdated(savedVersion, currentVersion, true)
            }

            // Save current version on background thread to avoid ANR
            RiviumPushExecutors.executeIO {
                prefs.edit().putString(KEY_APP_VERSION, currentVersion).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check app version: ${e.message}")
        }
    }

    // ==================== Service Notification ====================

    /**
     * Check if the service notification is currently hidden.
     * Returns true if the user has disabled the notification channel.
     */
    @JvmStatic
    fun isServiceNotificationHidden(): Boolean {
        val ctx = context ?: return false
        return !NotificationHelper.isServiceChannelEnabled(ctx)
    }

    /**
     * Open system settings for the service notification channel.
     * The user can toggle the channel off to hide the notification
     * while keeping the push service alive.
     * Only works on Android 8.0+ (API 26+).
     */
    @JvmStatic
    fun openServiceNotificationSettings() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.getServiceChannelId())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            Log.d(TAG, "Opened service notification channel settings")
        } else {
            Log.d(TAG, "Channel settings not available below Android 8.0")
        }
    }

    // ==================== Analytics ====================

    /**
     * Set the analytics handler to receive SDK events.
     * Automatically enables analytics tracking.
     *
     * @param handler The callback to receive analytics events
     */
    @JvmStatic
    fun setAnalyticsHandler(handler: RiviumPushAnalyticsCallback?) {
        RiviumPushAnalytics.setHandler(handler)
    }

    /**
     * Enable analytics tracking.
     * Note: You must also set a handler with setAnalyticsHandler() to receive events.
     */
    @JvmStatic
    fun enableAnalytics() {
        RiviumPushAnalytics.enable()
    }

    /**
     * Disable analytics tracking.
     */
    @JvmStatic
    fun disableAnalytics() {
        RiviumPushAnalytics.disable()
    }

    /**
     * Check if analytics is currently enabled.
     */
    @JvmStatic
    fun isAnalyticsEnabled(): Boolean = RiviumPushAnalytics.isEnabled()

    // ==================== Log Level ====================

    /**
     * Get current log level
     */
    @JvmStatic
    fun getLogLevel(): RiviumPushLogLevel = RiviumPushLogger.logLevel

    // ==================== Badge Management ====================

    private const val KEY_BADGE_COUNT = "badge_count"

    /**
     * Get current badge count
     */
    @JvmStatic
    fun getBadgeCount(): Int {
        val ctx = context ?: return 0
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BADGE_COUNT, 0)
    }

    /**
     * Set badge count
     * Note: On Android, this updates the app icon badge if supported by the launcher.
     */
    @JvmStatic
    fun setBadgeCount(count: Int) {
        val ctx = context ?: return
        val newCount = maxOf(0, count)

        // Save to preferences
        RiviumPushExecutors.executeIO {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BADGE_COUNT, newCount)
                .apply()
        }

        // Update launcher badge if supported
        updateLauncherBadge(ctx, newCount)
        Log.d(TAG, "Badge count set to: $newCount")
    }

    /**
     * Clear badge (set to 0)
     */
    @JvmStatic
    fun clearBadge() {
        setBadgeCount(0)
    }

    /**
     * Increment badge count by a value
     */
    @JvmStatic
    fun incrementBadge(by: Int = 1) {
        setBadgeCount(getBadgeCount() + by)
    }

    /**
     * Decrement badge count by a value (minimum 0)
     */
    @JvmStatic
    fun decrementBadge(by: Int = 1) {
        setBadgeCount(getBadgeCount() - by)
    }

    private fun updateLauncherBadge(context: Context, count: Int) {
        try {
            // Use ShortcutBadger library pattern for broad launcher support
            // Most launchers on Android 8+ support badge counts via notification channels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android 8+, badge is usually controlled by notification channel
                // The NotificationHelper should handle this
                Log.d(TAG, "Badge update: $count (handled by notification channel)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update launcher badge: ${e.message}")
        }
    }

    // ==================== State Getters ====================

    private var networkMonitor: NetworkMonitor? = null

    /**
     * Get current network state
     */
    @JvmStatic
    fun getNetworkState(): NetworkState {
        val ctx = context ?: return NetworkState(
            isAvailable = false,
            networkType = NetworkType.UNKNOWN
        )

        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(ctx)
        }

        val monitor = networkMonitor!!
        return NetworkState(
            isAvailable = monitor.isNetworkAvailable(),
            networkType = NetworkType.fromString(monitor.getNetworkType())
        )
    }

    /**
     * Get current app state (foreground/background)
     */
    @JvmStatic
    fun getAppState(): AppState {
        val isInForeground = try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app state: ${e.message}")
            false
        }

        return AppState(
            isInForeground = isInForeground,
            currentActivity = pendingActivity?.javaClass?.simpleName
        )
    }

    /**
     * Check if the app is currently in foreground
     */
    @JvmStatic
    fun isInForeground(): Boolean = getAppState().isInForeground

    // ==================== In-App Messages ====================

    private var inAppMessageManager: InAppMessageManager? = null
    // Store the activity until InAppMessageManager is created
    private var pendingActivity: Activity? = null

    /**
     * Get the in-app message manager instance
     */
    fun getInAppMessageManager(): InAppMessageManager {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val app = appId ?: throw IllegalStateException("RiviumPush not initialized")
        val dev = deviceId ?: throw IllegalStateException("Device ID not available")

        if (inAppMessageManager == null) {
            inAppMessageManager = InAppMessageManager.getInstance(ctx, client, app, dev)
            // Apply any pending activity that was set before the manager was created
            pendingActivity?.let {
                inAppMessageManager?.setCurrentActivity(it)
                Log.d(TAG, "Applied pending activity to InAppMessageManager")
            }
        }
        return inAppMessageManager!!
    }

    /**
     * Set the current activity for in-app message display and permission requests.
     * Call this in your Activity's onResume()
     */
    fun setCurrentActivity(activity: Activity?) {
        // Always store the activity in case InAppMessageManager is created later
        pendingActivity = activity
        inAppMessageManager?.setCurrentActivity(activity)

        // Request notification permission when activity becomes available
        if (activity != null) {
            requestNotificationPermissionIfNeeded(activity)
            // Pick up any tap that launched (or re-entered) the activity.
            handleNotificationIntent(activity.intent)
        }
    }

    /**
     * Check if notification permission is granted (Android 13+).
     * On older versions, always returns true.
     */
    @JvmStatic
    fun hasNotificationPermission(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionRequested) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission")
                notificationPermissionRequested = true
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * Handle notification permission result.
     * Call this from your Activity's onRequestPermissionsResult().
     *
     * @return true if this was a notification permission request handled by the SDK
     */
    @JvmStatic
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Notification permission ${if (granted) "granted" else "denied"}")
            return true
        }
        return false
    }

    /**
     * Set callback for in-app message events
     */
    fun setInAppMessageCallback(callback: InAppMessageCallback?) {
        getInAppMessageManager().setCallback(callback)
    }

    /**
     * Fetch in-app messages from server
     */
    fun fetchInAppMessages(onComplete: ((List<InAppMessage>) -> Unit)? = null) {
        getInAppMessageManager().fetchMessages(onComplete)
    }

    /**
     * Trigger in-app messages for app open
     * Call this when your app becomes active
     */
    fun triggerInAppOnAppOpen() {
        try {
            getInAppMessageManager().triggerOnAppOpen()
        } catch (e: Exception) {
            Log.w(TAG, "InAppMessageManager not initialized: ${e.message}")
        }
    }

    /**
     * Trigger in-app messages for a custom event
     */
    fun triggerInAppEvent(eventName: String, properties: Map<String, Any>? = null) {
        try {
            getInAppMessageManager().triggerEvent(eventName, properties)
        } catch (e: Exception) {
            Log.w(TAG, "InAppMessageManager not initialized: ${e.message}")
        }
    }

    /**
     * Trigger in-app messages for session start
     */
    fun triggerInAppOnSessionStart() {
        try {
            getInAppMessageManager().triggerOnSessionStart()
        } catch (e: Exception) {
            Log.w(TAG, "InAppMessageManager not initialized: ${e.message}")
        }
    }

    /**
     * Show a specific in-app message by ID
     */
    fun showInAppMessage(messageId: String) {
        try {
            getInAppMessageManager().showMessage(messageId)
        } catch (e: Exception) {
            Log.w(TAG, "InAppMessageManager not initialized: ${e.message}")
        }
    }

    /**
     * Dismiss the currently displayed in-app message
     */
    fun dismissInAppMessage() {
        inAppMessageManager?.dismissCurrentMessage()
    }

    // ==================== Inbox ====================

    private var inboxManager: InboxManager? = null

    /**
     * Get the inbox manager instance
     */
    fun getInboxManager(): InboxManager {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val cfg = config ?: throw IllegalStateException("RiviumPush not initialized")
        val dev = deviceId ?: throw IllegalStateException("Device ID not available")

        if (inboxManager == null) {
            inboxManager = InboxManager.getInstance(ctx, cfg, dev, currentUserId)
        }
        return inboxManager!!
    }

    /**
     * Set callback for inbox events
     */
    fun setInboxCallback(callback: InboxCallback?) {
        getInboxManager().setCallback(callback)
    }

    /**
     * Get inbox messages
     */
    fun getInboxMessages(
        filter: InboxFilter = InboxFilter(),
        onSuccess: (InboxMessagesResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        getInboxManager().getMessages(filter, onSuccess, onError)
    }

    /**
     * Get a single inbox message
     */
    fun getInboxMessage(
        messageId: String,
        onSuccess: (InboxMessage) -> Unit,
        onError: (String) -> Unit
    ) {
        getInboxManager().getMessage(messageId, onSuccess, onError)
    }

    /**
     * Mark an inbox message as read
     */
    fun markInboxMessageAsRead(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().markAsRead(messageId, onSuccess, onError)
    }

    /**
     * Archive an inbox message
     */
    fun archiveInboxMessage(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().archiveMessage(messageId, onSuccess, onError)
    }

    /**
     * Delete an inbox message
     */
    fun deleteInboxMessage(
        messageId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().deleteMessage(messageId, onSuccess, onError)
    }

    /**
     * Mark multiple inbox messages
     */
    fun markMultipleInboxMessages(
        messageIds: List<String>,
        status: InboxMessageStatus,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().markMultiple(messageIds, status, onSuccess, onError)
    }

    /**
     * Mark all inbox messages as read
     */
    fun markAllInboxMessagesAsRead(
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().markAllAsRead(onSuccess, onError)
    }

    /**
     * Get unread inbox count (from cache)
     */
    fun getInboxUnreadCount(): Int {
        return try {
            getInboxManager().getUnreadCount()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Fetch unread inbox count from server
     */
    fun fetchInboxUnreadCount(
        onSuccess: (Int) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        getInboxManager().fetchUnreadCount(onSuccess, onError)
    }

    /**
     * Get cached inbox messages without network call
     */
    fun getCachedInboxMessages(): List<InboxMessage> {
        return try {
            getInboxManager().getCachedMessages()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Clear inbox cache
     */
    fun clearInboxCache() {
        try {
            getInboxManager().clearCache()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear inbox cache: ${e.message}")
        }
    }

    // ==================== A/B Testing ====================

    private var abTestingManager: ABTestingManager? = null

    /**
     * Get the A/B testing manager instance
     */
    fun getABTestingManager(): ABTestingManager {
        val ctx = context ?: throw IllegalStateException("RiviumPush not initialized")
        val client = apiClient ?: throw IllegalStateException("RiviumPush not initialized")
        val dev = deviceId ?: throw IllegalStateException("Device ID not available")

        if (abTestingManager == null) {
            abTestingManager = ABTestingManager.getInstance(ctx, client, dev)
        }
        return abTestingManager!!
    }

    /**
     * Set callback for A/B testing events
     */
    fun setABTestingCallback(callback: ABTestingCallback?) {
        getABTestingManager().setCallback(callback)
    }

    /**
     * Get all active A/B tests for the app
     */
    fun getActiveABTests(
        onSuccess: (List<ABTestSummary>) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().getActiveTests(onSuccess, onError)
    }

    /**
     * Get variant assignment for a specific test
     */
    fun getABTestVariant(
        testId: String,
        forceRefresh: Boolean = false,
        onSuccess: (ABTestVariant) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().getVariant(testId, forceRefresh, onSuccess, onError)
    }

    /**
     * Get cached variant for a test (no network call)
     */
    fun getCachedABTestVariant(testId: String): ABTestVariant? {
        return try {
            getABTestingManager().getCachedVariant(testId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Track A/B test impression (variant shown to user)
     */
    fun trackABTestImpression(
        testId: String,
        variantId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackImpression(testId, variantId, onSuccess, onError)
    }

    /**
     * Track A/B test opened (user viewed content)
     */
    fun trackABTestOpened(
        testId: String,
        variantId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackOpened(testId, variantId, onSuccess, onError)
    }

    /**
     * Track A/B test clicked (user clicked CTA)
     */
    fun trackABTestClicked(
        testId: String,
        variantId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackClicked(testId, variantId, onSuccess, onError)
    }

    /**
     * Convenience method to track display of variant (impression + opened)
     */
    fun trackABTestDisplay(
        variant: ABTestVariant,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackDisplay(variant, onSuccess, onError)
    }

    /**
     * Track A/B test conversion (user completed desired action like purchase, signup, etc.)
     */
    fun trackABTestConverted(
        testId: String,
        variantId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackConverted(testId, variantId, onSuccess, onError)
    }

    /**
     * Track A/B test conversion using cached variant (convenience method)
     */
    fun trackABTestConversion(
        testId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        getABTestingManager().trackConversion(testId, onSuccess, onError)
    }

    /**
     * Check if device is in control group for a test
     */
    fun isInControlGroup(testId: String): Boolean {
        return try {
            getABTestingManager().isInControlGroup(testId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check control group: ${e.message}")
            false
        }
    }

    /**
     * Clear A/B testing cache
     */
    fun clearABTestingCache() {
        try {
            getABTestingManager().clearCache()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear A/B testing cache: ${e.message}")
        }
    }
}
