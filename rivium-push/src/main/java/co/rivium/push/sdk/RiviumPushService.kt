package co.rivium.push.sdk

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import co.rivium.push.sdk.inbox.InboxContent
import co.rivium.push.sdk.inbox.InboxMessage
import co.rivium.push.sdk.inbox.InboxMessageStatus
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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "====== SERVICE CREATED ======")
        Log.d(TAG, "Service instance created")
        Log.d(TAG, "=============================")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
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
            val parametersChanged = existingManager != null && (
                currentAppId != safeAppId ||
                currentDeviceId != safeDeviceId ||
                currentAppIdentifier != (Companion.appIdentifier ?: "_default")
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
                        Companion.appIdentifier ?: "_default"
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

                    // Only trigger reconnection if fully disconnected.
                    // If state is RECONNECTING, PNSocket's auto-retry will
                    // pick up the network naturally — no need to interfere.
                    val connState = socketManager?.getConnectionState()
                    if (connState == PNSocketManager.ConnectionState.DISCONNECTED) {
                        Log.d(TAG, "Fully disconnected, triggering reconnect now")
                        socketManager?.reconnectNow()
                    } else {
                        Log.d(TAG, "Connection state is $connState - letting PNSocket handle reconnection")
                    }
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
        Log.d(TAG, "Handling message payload: $payload")

        // Check message type for routing
        try {
            val json = JSONObject(payload)
            val type = json.optString("type", "")
            if (type == "inbox_update") {
                handleInboxUpdate(json)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse type from payload, continuing with normal flow")
        }

        val message = RiviumPushMessage.fromJson(payload)
        if (message != null) {
            Log.d(TAG, "Parsed message: title=${message.title}, body=${message.body}, silent=${message.silent}")

            // Broadcast message for integrations (VoIP, etc.) to intercept
            // Uses system broadcast so manifest-registered receivers work even in background
            broadcastMessage(payload)

            // Show notification if not silent and (not in foreground OR showNotificationInForeground is true)
            if (!message.silent) {
                val isAppInForeground = RiviumPush.getAppState().isInForeground
                val showInForeground = config?.showNotificationInForeground ?: true

                if (!isAppInForeground || showInForeground) {
                    Log.d(TAG, "Showing notification (foreground=$isAppInForeground, showInForeground=$showInForeground)")
                    notificationHelper?.showNotification(message)
                } else {
                    Log.d(TAG, "Skipping notification - app in foreground and showNotificationInForeground=false")
                }
            } else {
                Log.d(TAG, "Silent message - skipping notification")
            }

            // Notify callback (always, even if notification is not shown)
            callback?.onMessageReceived(message)
        } else {
            Log.e(TAG, "Failed to parse message from JSON")
        }
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
    private fun broadcastMessage(payload: String) {
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
                    component = ComponentName(packageName, receiverClass)
                }
                Log.d(TAG, "Sending to receiver: $receiverClass")
                sendBroadcast(receiverIntent)
                sentCount++
            } catch (e: ClassNotFoundException) {
                // Receiver not available (SDK not included) - skip silently
                Log.d(TAG, "Receiver not available: $receiverClass")
            }
        }

        Log.d(TAG, "Message broadcast sent to $sentCount receivers")
    }

    override fun onDestroy() {
        Log.d(TAG, "====== SERVICE DESTROYED ======")
        Log.d(TAG, "Total service starts: $serviceStartCount")
        Log.d(TAG, "Total socketManager creates: $socketManagerCreateCount")
        Log.d(TAG, "===============================")

        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        networkMonitor = null

        socketManager?.disconnect()
        socketManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
