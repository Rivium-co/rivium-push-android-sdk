package co.rivium.push.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Monitors network connectivity changes and notifies listeners.
 * Used to trigger pn-protocol reconnection when network becomes available.
 */
class NetworkMonitor(private val context: Context) {
    companion object {
        private const val TAG = "Network"
    }

    interface NetworkCallback {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }

    private var callback: NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    private var lastNetworkState: Boolean? = null

    fun setCallback(callback: NetworkCallback) {
        this.callback = callback
    }

    /**
     * Check if network is currently available.
     */
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * Start monitoring network connectivity changes.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring network")
            return
        }

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        // Initialize last known state
        lastNetworkState = isNetworkAvailable()
        Log.d(TAG, "Starting network monitoring. Initial state: ${if (lastNetworkState == true) "connected" else "disconnected"}")

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "====== NETWORK AVAILABLE ======")
                Log.d(TAG, "Network: $network")
                Log.d(TAG, "Previous state: $lastNetworkState")
                Log.d(TAG, "===============================")

                // Only trigger callback if state actually changed
                if (lastNetworkState != true) {
                    lastNetworkState = true
                    callback?.onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "====== NETWORK LOST ======")
                Log.d(TAG, "Network: $network")
                Log.d(TAG, "==========================")

                // Check if we still have any active network
                val stillConnected = isNetworkAvailable()
                Log.d(TAG, "Still have network: $stillConnected")

                if (!stillConnected && lastNetworkState != false) {
                    lastNetworkState = false
                    callback?.onNetworkLost()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                Log.d(TAG, "Network capabilities changed: internet=$hasInternet, validated=$isValidated")

                // Network is truly available when both internet capability and validation pass
                val isConnected = hasInternet && isValidated
                if (isConnected && lastNetworkState != true) {
                    lastNetworkState = true
                    callback?.onNetworkAvailable()
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            isMonitoring = true
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * Stop monitoring network connectivity changes.
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }

        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }

        networkCallback = null
        isMonitoring = false
        Log.d(TAG, "Network monitoring stopped")
    }

    /**
     * Get current network type.
     */
    fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return "none"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "unknown"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                ConnectivityManager.TYPE_VPN -> "vpn"
                else -> "other"
            }
        }
    }
}
