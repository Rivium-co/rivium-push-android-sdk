@file:OptIn(RiviumPushInternalApi::class)

package co.rivium.push.sdk

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import co.rivium.push.sdk.inbox.InboxContent
import co.rivium.push.sdk.inbox.InboxMessage
import co.rivium.push.sdk.inbox.InboxMessageStatus
import co.rivium.push.sdk.internal.DeliveryAckTracker
import co.rivium.push.sdk.internal.DisplayedMessageTracker
import org.json.JSONObject

/**
 * Foreground service that maintains PN Protocol connection and handles push notifications.
 */
class RiviumPushService : Service() {
    companion object {
        private const val TAG = "Service"
        const val NOTIFICATION_ID = 1

        // Broadcast action for integrations (VoIP, etc.) - uses system broadcast for background support
        const val ACTION_MESSAGE = "co.rivium.push.MESSAGE"
        const val EXTRA_DATA = "data"

        private var socketManager: PNSocketManager? = null
        private var notificationHelper: NotificationHelper? = null
        private var networkMonitor: NetworkMonitor? = null
        internal var config: RiviumPushConfig? = null
        internal var appId: String? = null
        internal var deviceId: String? = null
        internal var appIdentifier: String? = null
        internal var subscriptionId: String? = null
        internal var callback: RiviumPushCallback? = null

        private var serviceStartCount = 0
        private var socketManagerCreateCount = 0

        /**
         * Check if PN Protocol is currently connected
         */
        fun isConnected(): Boolean {
            return socketManager?.isConnected() == true
        }

        /**
         * Trigger immediate reconnection
         */
        fun reconnectNow() {
            Log.d(TAG, "reconnectNow() called from external")
            socketManager?.reconnectNow()
        }


        private const val PREFS_NAME = "rivium_push_prefs"
        private const val KEY_ACKED_MESSAGE_IDS = "acked_message_ids"
        private const val KEY_DISPLAYED_MESSAGE_IDS = "displayed_message_ids"

        /** Delays before re-trying a failed ack (in addition to HTTP-level retries). */
        internal val ACK_RETRY_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L)

        private var ackTracker: DeliveryAckTracker? = null
        private var displayTracker: DisplayedMessageTracker? = null
        /** Used to show notifications when the push service is not running (add-on transport). */
        private var fallbackNotificationHelper: NotificationHelper? = null
        private var ackClient: ApiClient? = null
        private var ackClientConfig: RiviumPushConfig? = null

        @Synchronized
        private fun tracker(context: Context): DeliveryAckTracker {
            return ackTracker ?: DeliveryAckTracker().also { t ->
                try {
                    t.restore(
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_ACKED_MESSAGE_IDS, null)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore acked message ids: ${e.message}")
                }
                ackTracker = t
            }
        }

        @Synchronized
        private fun displayedTracker(context: Context): DisplayedMessageTracker {
            return displayTracker ?: DisplayedMessageTracker().also { t ->
                try {
                    t.restore(
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(KEY_DISPLAYED_MESSAGE_IDS, null)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore displayed message ids: ${e.message}")
                }
                displayTracker = t
            }
        }

        /**
         * Claim a message for handling. Returns false if the same messageId was
         * already handled — e.g. it arrived over PN Protocol and again over FCM.
         * Messages without an id are always handled.
         */
        private fun claimForDisplay(context: Context, messageId: String?): Boolean {
            if (messageId.isNullOrEmpty()) return true
            return try {
                val tracker = displayedTracker(context)
                if (!tracker.tryClaim(messageId)) return false
                val serialized = tracker.serialize()
                // commit on the IO thread: a process started only to handle an FCM
                // message may be killed soon after, and the id must survive it.
                RiviumPushExecutors.executeIO {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_DISPLAYED_MESSAGE_IDS, serialized)
                        .commit()
                }
                true
            } catch (e: Exception) {
                // Never lose a notification because dedupe failed.
                Log.w(TAG, "Display dedupe failed: ${e.message}")
                true
            }
        }

        @Synchronized
        private fun clientFor(cfg: RiviumPushConfig): ApiClient {
            val existing = ackClient
            if (existing != null && ackClientConfig === cfg) return existing
            return ApiClient(cfg).also {
                ackClient = it
                ackClientConfig = cfg
            }
        }

        /**
         * Config for acks: the live one, or the one persisted by RiviumPush.init()
         * when this process was started (e.g. service restart) before init().
         */
        private fun ackConfig(context: Context): Pair<RiviumPushConfig, String>? {
            val cfg = config ?: RiviumPush.configOrNull()
            val device = deviceId ?: RiviumPush.getDeviceId()
            if (cfg != null && device != null) return cfg to device
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apiKey = prefs.getString("apiKey", null) ?: return null
            val savedDevice = device ?: prefs.getString("device_id", null) ?: return null
            val restored = cfg ?: RiviumPushConfig(
                apiKey = apiKey,
                notificationIcon = prefs.getString("notificationIcon", null),
                showServiceNotification = prefs.getBoolean("showServiceNotification", true),
                wrapperSdkName = prefs.getString("wrapperSdkName", null),
                wrapperSdkVersion = prefs.getString("wrapperSdkVersion", null)
            )
            return restored to savedDevice
        }

        /**
         * Confirm to Rivium Push that a notification arrived on this device.
         *
         * Deduplicated per messageId (PN Protocol may redeliver), retried a few
         * times with backoff on transient failures, and run off the calling
         * thread. No-op when the message carries no id (older backend) or no
         * configuration is available. Never throws: a missed ack costs a
         * delivery statistic, not a notification.
         */
        internal fun reportDelivered(
            context: Context,
            messageId: String?,
            transport: String = RiviumPushTransports.TRANSPORT_PN
        ) {
            val id = messageId?.takeIf { it.isNotEmpty() } ?: return
            try {
                val appContext = context.applicationContext ?: context
                // This process may run without init() (service restart, add-on transport).
                co.rivium.push.sdk.internal.UserTokenSession.attach(appContext)
                val (cfg, device) = ackConfig(appContext) ?: run {
                    Log.w(TAG, "Delivery ack skipped: SDK not configured")
                    return
                }
                val tracker = tracker(appContext)
                if (!tracker.tryClaim(id)) {
                    Log.d(TAG, "Delivery ack already sent for $id")
                    return
                }
                sendAck(appContext, clientFor(cfg), tracker, id, device, transport, attempt = 0)
            } catch (e: Exception) {
                Log.w(TAG, "Delivery ack failed: ${e.message}")
            }
        }

        private fun sendAck(
            context: Context,
            client: ApiClient,
            tracker: DeliveryAckTracker,
            messageId: String,
            device: String,
            transport: String,
            attempt: Int
        ) {
            RiviumPushExecutors.executeNetwork {
                val result = try {
                    client.reportDeliveredSync(messageId, device, transport)
                } catch (e: Exception) {
                    ApiClient.AckResult.RETRYABLE
                }
                when {
                    result == ApiClient.AckResult.SUCCESS -> {
                        tracker.markAcked(messageId)
                        val serialized = tracker.serialize()
                        RiviumPushExecutors.executeIO {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_ACKED_MESSAGE_IDS, serialized)
                                .apply()
                        }
                    }
                    result == ApiClient.AckResult.RETRYABLE && attempt < ACK_RETRY_DELAYS_MS.size -> {
                        val delay = ACK_RETRY_DELAYS_MS[attempt]
                        Log.d(TAG, "Delivery ack for $messageId will retry in ${delay}ms")
                        RiviumPushExecutors.scheduleBackground(delay) {
                            sendAck(context, client, tracker, messageId, device, transport, attempt + 1)
                        }
                    }
                    else -> {
                        Log.w(TAG, "Delivery ack for $messageId gave up ($result)")
                        tracker.release(messageId)
                    }
                }
            }
        }


        /**
         * Handle a message payload from any transport ("pn" or an add-on such as
         * "fcm"). Runs with or without the push service: when the service is not
         * running (process started by FCM) configuration is restored from what
         * RiviumPush.init() persisted.
         *
         * @return false if no configuration is available on this install.
         */
        internal fun handleIncomingPayload(context: Context, payload: String, transport: String): Boolean {
            val appContext = context.applicationContext ?: context
            Log.d(TAG, "Handling $transport message payload: $payload")

            if (ackConfig(appContext) == null) {
                Log.w(TAG, "Ignoring $transport message: RiviumPush was never initialized on this install")
                return false
            }

            // Check message type for routing
            try {
                val json = JSONObject(payload)
                val type = json.optString("type", "")
                if (type == "inbox_update") {
                    handleInboxUpdate(json)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to parse type from payload, continuing with normal flow")
            }

            val message = RiviumPushMessage.fromJson(payload)
            if (message == null) {
                Log.e(TAG, "Failed to parse message from JSON")
                return true
            }
            Log.d(TAG, "Parsed message: title=${message.title}, body=${message.body}, silent=${message.silent}")

            // The same message may arrive over PN Protocol and FCM: only the first
            // copy is shown, broadcast and passed to the callback.
            if (!claimForDisplay(appContext, message.messageId)) {
                Log.d(TAG, "Duplicate message ${message.messageId} via $transport - ignoring")
                return true
            }

            // Confirm delivery first so every path (foreground, background, silent,
            // voip_call) is acked even if display or a callback throws. The transport
            // only tells the server a notification was accepted for sending — this
            // ack is the only signal that it actually reached the device.
            reportDelivered(appContext, message.messageId, transport)

            // Broadcast message for integrations (VoIP, etc.) to intercept
            // Uses system broadcast so manifest-registered receivers work even in background
            broadcastMessage(appContext, payload)

            // Check if this is a VoIP call message — handled by VoIP SDK, skip regular notification
            val isVoipCall = message.data?.get("type") == "voip_call"

            // Show notification if not silent, not voip_call, and (not in foreground OR showNotificationInForeground is true)
            if (!message.silent && !isVoipCall) {
                val isAppInForeground = RiviumPush.getAppState().isInForeground
                val showInForeground = (config ?: RiviumPush.configOrNull())?.showNotificationInForeground ?: true

                if (!isAppInForeground || showInForeground) {
                    Log.d(TAG, "Showing notification (foreground=$isAppInForeground, showInForeground=$showInForeground)")
                    val helper = notificationHelperFor(appContext)
                    if (helper != null) {
                        helper.showNotification(message)
                    } else {
                        Log.w(TAG, "Notification skipped: SDK not configured")
                    }
                } else {
                    Log.d(TAG, "Skipping notification - app in foreground and showNotificationInForeground=false")
                }
            } else if (isVoipCall) {
                Log.d(TAG, "VoIP call message - skipping regular notification, handled by VoIP SDK")
            } else {
                Log.d(TAG, "Silent message - skipping notification")
            }

            // Notify callback (always, even if notification is not shown)
            callback?.onMessageReceived(message)
            return true
        }

        /** The running service's helper, or one built from the live/persisted config. */
        @Synchronized
        private fun notificationHelperFor(context: Context): NotificationHelper? {
            notificationHelper?.let { return it }
            fallbackNotificationHelper?.let { return it }
            val cfg = ackConfig(context)?.first ?: return null
            return NotificationHelper(context, cfg).also { fallbackNotificationHelper = it }
        }

        /**
         * Handle inbox_update messages from pn-protocol.
         * Creates an InboxMessage from the payload and routes it to InboxManager.
         */
        private fun handleInboxUpdate(json: JSONObject) {
            Log.d(TAG, "Handling inbox_update message")
            try {
                val messageId = json.optString("messageId", "")
                val title = json.optString("title", "")
                val body = json.optString("body", "")

                if (messageId.isEmpty()) {
                    Log.e(TAG, "inbox_update missing messageId, ignoring")
                    return
                }

                val inboxMessage = InboxMessage(
                    id = messageId,
                    content = InboxContent(
                        title = title,
                        body = body,
                        imageUrl = if (json.has("imageUrl")) json.getString("imageUrl") else null,
                        deepLink = if (json.has("deepLink")) json.getString("deepLink") else null,
                        data = null
                    ),
                    status = InboxMessageStatus.UNREAD,
                    category = if (json.has("category")) json.getString("category") else null,
                    createdAt = json.optString("createdAt", System.currentTimeMillis().toString())
                )

                RiviumPush.getInboxManager().handleIncomingMessage(inboxMessage)
                Log.d(TAG, "inbox_update routed to InboxManager: $messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle inbox_update: ${e.message}")
            }
        }

        /**
         * Broadcast message using explicit intents for integrations to intercept.
         * Uses explicit component targeting for Android 8.0+ compatibility.
         */
        private fun broadcastMessage(context: Context, payload: String) {
            Log.d(TAG, "Broadcasting message for integrations")

            // Known receivers that handle Rivium Push messages
            // These are discovered at compile time from dependent SDKs
            val knownReceivers = listOf(
                "co.rivium.push.voip.RiviumPushMessageReceiver"  // VoIP SDK receiver
            )

            var sentCount = 0
            for (receiverClass in knownReceivers) {
                try {
                    // Check if the receiver class exists in the app
                    Class.forName(receiverClass)

                    val receiverIntent = Intent(ACTION_MESSAGE).apply {
                        putExtra(EXTRA_DATA, payload)
                        component = ComponentName(context.packageName, receiverClass)
                    }
                    Log.d(TAG, "Sending to receiver: $receiverClass")
                    context.sendBroadcast(receiverIntent)
                    sentCount++
                } catch (e: ClassNotFoundException) {
                    // Receiver not available (SDK not included) - skip silently
                    Log.d(TAG, "Receiver not available: $receiverClass")
                }
            }

            Log.d(TAG, "Message broadcast sent to $sentCount receivers")
        }
    }

    // Reconnect triggers, registered while the service runs. Manifest receivers
    // for SCREEN_ON / USER_PRESENT are not delivered on modern Android.
    private var screenReceiver: BroadcastReceiver? = null
    private var foregroundObserver: LifecycleEventObserver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "====== SERVICE CREATED ======")
        Log.d(TAG, "Service instance created")
        Log.d(TAG, "=============================")
        registerWakeTriggers()
    }

    private fun registerWakeTriggers() {
        if (screenReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val reason = when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> "screen on"
                        Intent.ACTION_USER_PRESENT -> "user present"
                        else -> return
                    }
                    socketManager?.onWake(reason)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                // System broadcasts are still delivered to a non-exported receiver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                screenReceiver = receiver
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register screen receiver: ${e.message}")
            }
        }

        if (foregroundObserver == null) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    socketManager?.onWake("app foreground")
                }
            }
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                foregroundObserver = observer
            } catch (e: Exception) {
                Log.w(TAG, "Failed to observe app foreground: ${e.message}")
            }
        }
    }

    private fun unregisterWakeTriggers() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister screen receiver: ${e.message}")
            }
        }
        screenReceiver = null

        foregroundObserver?.let {
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove foreground observer: ${e.message}")
            }
        }
        foregroundObserver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceStartCount++
        Log.d(TAG, "======================================")
        Log.d(TAG, "SERVICE onStartCommand CALLED")
        Log.d(TAG, "Service start count: $serviceStartCount")
        Log.d(TAG, "StartId: $startId")
        Log.d(TAG, "Existing socketManager: ${socketManager != null}")
        Log.d(TAG, "======================================")

        val safeConfig = Companion.config
        val safeAppId = Companion.appId
        val safeDeviceId = Companion.deviceId

        if (safeConfig == null || safeAppId == null || safeDeviceId == null) {
            Log.e(TAG, "Service started without configuration")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground
        notificationHelper = NotificationHelper(this, safeConfig)
        val notification = notificationHelper!!.createServiceNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34): specialUse is the correct FGS type for a
            // long-running push connection. Manifest declares specialUse +
            // SPECIAL_USE_FGS_SUBTYPE reason Google Play reviews on submission.
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android <14: specialUse type doesn't exist on the OS, so the
            // manifest attribute is ignored. Call startForeground with no type
            // flag — passing a type here would fail the "subset" check.
            startForeground(NOTIFICATION_ID, notification)
        }

        // Synchronized to prevent race conditions when onStartCommand is called
        // multiple times (e.g., BootReceiver + app registration simultaneously)
        synchronized(Companion) {
            val existingManager = socketManager
            val connState = existingManager?.getConnectionState()

            // Check if the socket manager was created with different connection parameters.
            // This happens when registration completes and updates appId from apiKey.take(16)
            // to the real server-provided projectId — the existing socket is subscribed to
            // the wrong pn-protocol topic and must be replaced.
            val currentAppId = existingManager?.appId
            val currentDeviceId = existingManager?.deviceId
            val currentAppIdentifier = existingManager?.appIdentifier
            val currentSubscriptionId = existingManager?.subscriptionId
            val parametersChanged = existingManager != null && (
                currentAppId != safeAppId ||
                currentDeviceId != safeDeviceId ||
                currentAppIdentifier != (Companion.appIdentifier ?: "_default") ||
                currentSubscriptionId != Companion.subscriptionId
            )

            if (parametersChanged) {
                Log.d(TAG, "====== CONNECTION PARAMETERS CHANGED - RECREATING SOCKET MANAGER ======")
                Log.d(TAG, "AppId: $currentAppId -> $safeAppId")
                Log.d(TAG, "DeviceId: $currentDeviceId -> $safeDeviceId")
                Log.d(TAG, "AppIdentifier: $currentAppIdentifier -> ${Companion.appIdentifier ?: "_default"}")
                Log.d(TAG, "=======================================================================")
                existingManager?.disconnect()
                socketManager = null
            }

            when {
                // Already connected or connecting with correct params — nothing to do
                !parametersChanged && (
                    connState == PNSocketManager.ConnectionState.CONNECTED ||
                    connState == PNSocketManager.ConnectionState.CONNECTING
                ) -> {
                    Log.d(TAG, "Socket Manager already $connState with correct params - reusing")
                    // Just update callbacks in case they changed
                    setupCallbacks()
                    return@synchronized
                }
                // Exists but disconnected/reconnecting — nudge reconnection
                socketManager != null -> {
                    Log.d(TAG, "Socket Manager exists but $connState - reconnecting")
                    setupCallbacks()
                    socketManager?.reconnectNow()
                    return@synchronized
                }
                // First time or params changed — create new socketManager
                else -> {
                    socketManagerCreateCount++
                    Log.d(TAG, "====== CREATING NEW SOCKET MANAGER ======")
                    Log.d(TAG, "SocketManager create count: $socketManagerCreateCount")
                    Log.d(TAG, "AppId: $safeAppId")
                    Log.d(TAG, "DeviceId: $safeDeviceId")
                    Log.d(TAG, "AppIdentifier: ${Companion.appIdentifier ?: "_default"}")
                    Log.d(TAG, "=========================================")

                    socketManager = PNSocketManager(
                        this, safeConfig, safeAppId, safeDeviceId,
                        Companion.appIdentifier ?: "_default",
                        Companion.subscriptionId,
                    )
                    setupCallbacks()
                    setupNetworkMonitor()
                    socketManager?.connect()
                }
            }
        }

        return START_STICKY
    }

    private fun setupCallbacks() {
        socketManager?.setCallback(object : PNSocketManager.PNSocketManagerCallback {
            override fun onConnected() {
                Log.d(TAG, "PN Protocol connected")
                callback?.onConnectionStateChanged(true)
            }

            override fun onDisconnected() {
                Log.d(TAG, "PN Protocol disconnected")
                callback?.onConnectionStateChanged(false)
            }

            override fun onMessageReceived(channel: String, message: String) {
                handleMessage(message)
            }

            override fun onError(error: String) {
                Log.d(TAG, "PN Protocol error: $error")
                callback?.onError(error)
            }
        })

        // Set up detailed error callback for structured errors
        socketManager?.setErrorCallback(object : PNSocketManager.PNSocketErrorCallback {
            override fun onError(error: RiviumPushError) {
                Log.d(TAG, "PN Protocol detailed error: $error")
                callback?.onDetailedError(error)
            }

            override fun onConnectionStateChanged(
                state: PNSocketManager.ConnectionState,
                retryAttempt: Int,
                nextRetryMs: Long?
            ) {
                Log.d(TAG, "Connection state changed: $state, retry: $retryAttempt, nextRetry: ${nextRetryMs}ms")
                when (state) {
                    PNSocketManager.ConnectionState.RECONNECTING -> {
                        callback?.onReconnecting(retryAttempt, nextRetryMs ?: 0L)
                    }
                    PNSocketManager.ConnectionState.CONNECTED -> {
                        callback?.onConnectionStateChanged(true)
                    }
                    PNSocketManager.ConnectionState.DISCONNECTED -> {
                        callback?.onConnectionStateChanged(false)
                    }
                    else -> {}
                }
            }
        })
    }

    private fun setupNetworkMonitor() {
        if (networkMonitor == null) {
            Log.d(TAG, "====== SETTING UP NETWORK MONITOR ======")
            networkMonitor = NetworkMonitor(this)
            networkMonitor?.setCallback(object : NetworkMonitor.NetworkCallback {
                override fun onNetworkAvailable() {
                    Log.d(TAG, "Network became available")
                    val networkType = networkMonitor?.getNetworkType() ?: "unknown"
                    callback?.onNetworkStateChanged(true, networkType)

                    // Connect now (backoff reset) instead of waiting for the
                    // next scheduled retry. No-op while connected/connecting.
                    socketManager?.onNetworkAvailable()
                }

                override fun onNetworkChanged() {
                    val networkType = networkMonitor?.getNetworkType() ?: "unknown"
                    Log.d(TAG, "Network changed ($networkType)")
                    callback?.onNetworkStateChanged(true, networkType)
                    socketManager?.onNetworkChanged()
                }

                override fun onNetworkLost() {
                    Log.d(TAG, "Network lost")
                    callback?.onNetworkStateChanged(false, "none")
                }
            })
            networkMonitor?.startMonitoring()
            Log.d(TAG, "=========================================")
        } else {
            Log.d(TAG, "Network monitor already exists - reusing")
        }
    }

    private fun handleMessage(payload: String) {
        handleIncomingPayload(this, payload, RiviumPushTransports.TRANSPORT_PN)
    }

    override fun onDestroy() {
        Log.d(TAG, "====== SERVICE DESTROYED ======")
        Log.d(TAG, "Total service starts: $serviceStartCount")
        Log.d(TAG, "Total socketManager creates: $socketManagerCreateCount")
        Log.d(TAG, "===============================")

        unregisterWakeTriggers()

        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null

        socketManager?.disconnect()
        socketManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
