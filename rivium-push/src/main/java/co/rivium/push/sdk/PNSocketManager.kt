package co.rivium.push.sdk

import android.content.Context
import co.rivium.protocol.*
import co.rivium.push.sdk.internal.EndpointSelector
import co.rivium.push.sdk.internal.MqttEndpoints
import co.rivium.push.sdk.internal.PrefsEndpointStore
import co.rivium.push.sdk.internal.ReconnectDebouncer

/**
 * Manager that wraps PNSocket for the Rivium Push SDK.
 * Replaces the old MqttManager with the new PN Protocol.
 */
class PNSocketManager(
    private val context: Context,
    private val config: RiviumPushConfig,
    internal val appId: String,
    internal val deviceId: String,
    internal val appIdentifier: String = "_default",
    internal val subscriptionId: String? = null,
) {
    companion object {
        private const val TAG = "PNSocket"
        private const val PREFS_NAME = "rivium_push_prefs"

        /** Keepalive (seconds). Short enough to survive aggressive NAT/carrier timeouts. */
        internal const val HEARTBEAT_INTERVAL_S = 30
        internal const val CONNECTION_TIMEOUT_S = 30
        internal const val RECONNECT_DELAY_MS = 1_000L
        /** Backoff cap; the server keeps undelivered messages, so reconnect fast. */
        internal const val MAX_RECONNECT_DELAY_MS = 60_000L
        /** 0 = never give up while the SDK is started. */
        internal const val MAX_RECONNECT_ATTEMPTS = 0

        /** Screen on + user present + foreground usually fire together. */
        private const val WAKE_DEBOUNCE_MS = 2_000L
        private const val NETWORK_DEBOUNCE_MS = 1_000L
    }

    @Volatile
    private var socket: PNSocket? = null

    private val networkMonitor = NetworkMonitor(context)
    private val endpointSelector = EndpointSelector(
        store = PrefsEndpointStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        defaultEndpoint = {
            config.pnHost.takeIf { it.isNotEmpty() }?.let { PNEndpoint(it, config.pnPort, config.pnSecure) }
        },
        networkKey = { currentNetworkKey() }
    )
    private val wakeDebouncer = ReconnectDebouncer(WAKE_DEBOUNCE_MS)
    private val networkAvailableDebouncer = ReconnectDebouncer(NETWORK_DEBOUNCE_MS)
    private val networkChangedDebouncer = ReconnectDebouncer(NETWORK_DEBOUNCE_MS)
    private var callback: PNSocketManagerCallback? = null
    private var errorCallback: PNSocketErrorCallback? = null
    private var hasSubscribedOnce: Boolean = false

    // Connection state (mirrors old MqttManager for compatibility)
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    interface PNSocketManagerCallback {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(channel: String, message: String)
        fun onError(error: String)
    }

    interface PNSocketErrorCallback {
        fun onError(error: RiviumPushError)
        fun onConnectionStateChanged(state: ConnectionState, retryAttempt: Int, nextRetryMs: Long?)
    }

    fun setCallback(callback: PNSocketManagerCallback) {
        this.callback = callback
    }

    fun setErrorCallback(callback: PNSocketErrorCallback) {
        this.errorCallback = callback
    }

    fun getConnectionState(): ConnectionState {
        return when (socket?.state()) {
            PNState.DISCONNECTED -> ConnectionState.DISCONNECTED
            PNState.CONNECTING -> ConnectionState.CONNECTING
            PNState.CONNECTED -> ConnectionState.CONNECTED
            PNState.RECONNECTING -> ConnectionState.RECONNECTING
            PNState.DISCONNECTING -> ConnectionState.DISCONNECTED
            null -> ConnectionState.DISCONNECTED
        }
    }

    fun connect() {
        Log.d(TAG, "connect() called")

        // Close any existing socket to prevent orphaned pn-protocol connections
        // that cause EMQX session takeover loops (connect → discard → reconnect → repeat)
        if (socket != null) {
            Log.d(TAG, "Closing existing socket before reconnect")
            socket?.close()
            socket = null
            hasSubscribedOnce = false
        }

        // Build PNConfig from RiviumPushConfig
        val pnConfigBuilder = PNConfig.builder()
            .gateway(config.pnHost)
            .port(config.pnPort)
            .clientId("rp_${appId}_${deviceId}_${context.packageName.hashCode().toUInt().toString(16)}")
            .heartbeatInterval(HEARTBEAT_INTERVAL_S)
            .connectionTimeout(CONNECTION_TIMEOUT_S)
            .freshStart(true)
            .autoReconnect(true)
            .maxReconnectAttempts(MAX_RECONNECT_ATTEMPTS)
            .reconnectDelay(RECONNECT_DELAY_MS)
            .maxReconnectDelay(MAX_RECONNECT_DELAY_MS)
            .secure(config.pnSecure)  // Use TLS/SSL from server config

        // Set JWT token auth if available (per-device authentication)
        if (config.pnToken != null) {
            Log.d(TAG, "Using JWT token for PN Protocol authentication")
            pnConfigBuilder.auth(PNAuth.basic("jwt", config.pnToken!!))
        } else {
            Log.w(TAG, "No PN token available - connection may fail")
        }

        val pnConfig = pnConfigBuilder.build()

        // Create a dedicated PNSocket instance (no singleton)
        socket = PNSocket(pnConfig)

        // Server-provided endpoints (if any) with failover; default endpoint last
        socket?.setEndpointProvider { endpointSelector.endpoints() }
        socket?.addEndpointListener { endpoint ->
            Log.d(TAG, "Connected via $endpoint")
            try {
                endpointSelector.onConnected(endpoint)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remember endpoint: ${e.message}")
            }
        }

        // Add connection listener
        socket?.addConnectionListener(object : PNConnectionListener {
            override fun onStateChanged(state: PNState) {
                Log.d(TAG, "State changed: $state")
                val connState = when (state) {
                    PNState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    PNState.CONNECTING -> ConnectionState.CONNECTING
                    PNState.CONNECTED -> ConnectionState.CONNECTED
                    PNState.RECONNECTING -> ConnectionState.RECONNECTING
                    PNState.DISCONNECTING -> ConnectionState.DISCONNECTED
                }
                RiviumPushExecutors.executeMain {
                    errorCallback?.onConnectionStateChanged(connState, 0, null)
                }
            }

            override fun onConnected() {
                Log.d(TAG, "Connected!")

                // Subscribe to channels only on the first connection.
                // On reconnects, PNSocket.resubscribeChannels() handles
                // re-subscribing from its activeChannels set automatically.
                if (!hasSubscribedOnce) {
                    subscribeToChannels()
                    hasSubscribedOnce = true
                } else {
                    Log.d(TAG, "Reconnected - PNSocket handles resubscription automatically")
                }

                RiviumPushExecutors.executeMain {
                    callback?.onConnected()
                }
            }

            override fun onDisconnected(reason: String?) {
                Log.d(TAG, "Disconnected: $reason")
                RiviumPushExecutors.executeMain {
                    callback?.onDisconnected()
                }
            }

            override fun onReconnecting(attempt: Int, nextRetryMs: Long) {
                Log.d(TAG, "Reconnecting: attempt=$attempt, nextRetry=${nextRetryMs}ms")
                RiviumPushExecutors.executeMain {
                    errorCallback?.onConnectionStateChanged(ConnectionState.RECONNECTING, attempt, nextRetryMs)
                }
            }
        })

        // Add error listener
        socket?.addErrorListener { error ->
            Log.e(TAG, "Error: ${error.message}")
            val riviumPushError = RiviumPushError(
                errorCode = when (error.code) {
                    PNError.Code.CONNECTION_FAILED -> RiviumPushErrorCode.CONNECTION_FAILED
                    PNError.Code.CONNECTION_LOST -> RiviumPushErrorCode.CONNECTION_LOST
                    PNError.Code.AUTH_FAILED -> RiviumPushErrorCode.AUTHENTICATION_FAILED
                    else -> RiviumPushErrorCode.UNKNOWN_ERROR
                },
                details = error.message,
                cause = error.cause
            )
            RiviumPushExecutors.executeMain {
                errorCallback?.onError(riviumPushError)
                callback?.onError(error.message)
            }
        }

        // Open connection
        socket?.open()
    }

    private fun subscribeToChannels() {
        // Per-install subscription topic — primary delivery channel for every
        // device-targeted message after the subscriptionId migration.
        val subscriptionChannel = subscriptionId?.let { "rivium_push/$appId/sub/$it" }
        val broadcastChannel = "rivium_push/$appId/broadcast"

        // DEPRECATED: legacy device-scoped topic. The backend stopped publishing
        // here after the subscriptionId migration. Kept subscribed only to keep
        // older test builds / out-of-tree backends working; will be removed in a
        // future SDK release.
        @Suppress("DEPRECATION")
        val deviceChannel = "rivium_push/$appId/$deviceId/$appIdentifier"

        Log.d(TAG, "Subscribing to channels: ${subscriptionChannel ?: "<no sub yet>"}, $broadcastChannel, $deviceChannel (deprecated)")

        if (subscriptionChannel != null) {
            socket?.stream(subscriptionChannel, PNDeliveryMode.RELIABLE) { message ->
                Log.d(TAG, "Message received on $subscriptionChannel")
                handleMessage(message)
            }
        }

        socket?.stream(broadcastChannel, PNDeliveryMode.RELIABLE) { message ->
            Log.d(TAG, "Message received on $broadcastChannel")
            handleMessage(message)
        }

        // Legacy stream — DEPRECATED, see comment above.
        socket?.stream(deviceChannel, PNDeliveryMode.RELIABLE) { message ->
            Log.d(TAG, "Message received on (deprecated) $deviceChannel")
            handleMessage(message)
        }
    }

    private fun handleMessage(message: PNMessage) {
        val payload = message.payloadAsString()
        Log.d(TAG, "Handling message: $payload")
        RiviumPushExecutors.executeMain {
            callback?.onMessageReceived(message.channel, payload)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        socket?.close()
        socket = null
        hasSubscribedOnce = false
    }

    fun isConnected(): Boolean {
        return socket?.isConnected() == true
    }

    fun reconnectNow() {
        Log.d(TAG, "reconnectNow() called")
        // Use reconnectImmediately() to cancel any pending retry and reconnect
        // without destroying the socket (preserves activeChannels for resubscription)
        socket?.reconnectImmediately()
    }

    /**
     * Screen on, user present or app in foreground. If waiting to retry, connect
     * now with the backoff reset; if connected, check the connection is still alive.
     */
    fun onWake(reason: String) {
        if (!wakeDebouncer.tryAcquire()) {
            Log.d(TAG, "Wake trigger ($reason) debounced")
            return
        }
        val s = socket ?: return
        when (s.state()) {
            PNState.CONNECTED -> {
                Log.d(TAG, "Wake trigger ($reason) - probing connection")
                s.probe()
            }
            PNState.CONNECTING, PNState.DISCONNECTING -> {
                Log.d(TAG, "Wake trigger ($reason) - connect already in progress")
            }
            else -> {
                Log.d(TAG, "Wake trigger ($reason) - reconnecting now")
                s.reconnectImmediately()
            }
        }
    }

    /** A network became available after having none: connect now, backoff reset. */
    fun onNetworkAvailable() {
        if (!networkAvailableDebouncer.tryAcquire()) {
            Log.d(TAG, "Network available debounced")
            return
        }
        val s = socket ?: return
        if (s.state() == PNState.CONNECTED || s.state() == PNState.CONNECTING) {
            Log.d(TAG, "Network available - already ${s.state()}")
            return
        }
        Log.d(TAG, "Network available - reconnecting now")
        s.reconnectImmediately()
    }

    /**
     * The default network switched (e.g. Wi-Fi <-> cellular, VPN on/off). The
     * current socket is bound to the old network, so replace it right away.
     */
    fun onNetworkChanged() {
        if (!networkChangedDebouncer.tryAcquire()) {
            Log.d(TAG, "Network change debounced")
            return
        }
        val s = socket ?: return
        Log.d(TAG, "Default network changed - reconnecting on the new network")
        s.reconnectImmediately(force = true)
    }

    private fun currentNetworkKey(): String = try {
        MqttEndpoints.networkKey(networkMonitor.getEndpointNetworkType())
    } catch (e: Exception) {
        MqttEndpoints.NETWORK_OTHER
    }
}
