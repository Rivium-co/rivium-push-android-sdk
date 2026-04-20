package co.rivium.push.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import co.rivium.push.example.databinding.ActivityMainBinding
import co.rivium.push.sdk.RiviumPush
import co.rivium.push.sdk.RiviumPushCallback
import co.rivium.push.sdk.RiviumPushCallbackAdapter
import co.rivium.push.sdk.RiviumPushError
import co.rivium.push.sdk.RiviumPushMessage
import co.rivium.push.sdk.inbox.InboxCallback
import co.rivium.push.sdk.inbox.InboxMessage
import co.rivium.push.sdk.inbox.InboxMessageStatus

/**
 * Main Activity demonstrating all Rivium Push SDK features.
 *
 * Features demonstrated:
 * - Device registration with user ID and metadata
 * - Connection state monitoring
 * - Topic subscription/unsubscription
 * - Message receiving and handling
 * - Deep link handling from notifications
 * - Initial message handling (app launched from notification)
 * - In-app messaging triggers
 * - Inbox access
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val logMessages = mutableListOf<String>()

    // Permission request launcher for Android 13+
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            addLog("Notification permission granted")
            registerDevice()
        } else {
            addLog("Notification permission denied")
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupUI()
        setupRiviumPushCallback()
        checkPermissionAndRegister()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        // Required for in-app messaging - tells SDK which activity is visible
        RiviumPush.setCurrentActivity(this)
        // Trigger in-app messages on app open
        RiviumPush.triggerInAppOnAppOpen()
        // Update UI
        updateConnectionStatus()
        updateDeviceInfo()
    }

    override fun onPause() {
        super.onPause()
        RiviumPush.setCurrentActivity(null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogViewerActivity::class.java).apply {
                    putStringArrayListExtra("logs", ArrayList(logMessages))
                })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupUI() {
        // Registration
        binding.btnRegister.setOnClickListener { registerDevice() }
        binding.btnUnregister.setOnClickListener { unregisterDevice() }

        // Topics
        binding.btnSubscribeTopic.setOnClickListener { subscribeTopic() }
        binding.btnUnsubscribeTopic.setOnClickListener { unsubscribeTopic() }

        // User ID
        binding.btnSetUserId.setOnClickListener { setUserId() }
        binding.btnClearUserId.setOnClickListener { clearUserId() }

        // Features
        binding.btnInbox.setOnClickListener {
            startActivity(Intent(this, InboxActivity::class.java))
        }
        binding.btnInAppMessages.setOnClickListener {
            startActivity(Intent(this, InAppActivity::class.java))
        }
        binding.btnABTesting.setOnClickListener {
            startActivity(Intent(this, ABTestActivity::class.java))
        }
        binding.btnTriggerEvent.setOnClickListener { triggerCustomEvent() }

        // Status
        binding.btnRefreshStatus.setOnClickListener {
            updateConnectionStatus()
            updateDeviceInfo()
        }
    }

    private fun setupRiviumPushCallback() {
        RiviumPush.setCallback(object : RiviumPushCallbackAdapter() {

            override fun onRegistered(deviceId: String) {
                runOnUiThread {
                    addLog("Registered with deviceId: $deviceId")
                    updateDeviceInfo()
                    Toast.makeText(this@MainActivity, "Registered!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMessageReceived(message: RiviumPushMessage) {
                runOnUiThread {
                    addLog("Message received: ${message.title}")
                    showMessageReceivedDialog(message)
                }
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    addLog("Connection state: ${if (connected) "Connected" else "Disconnected"}")
                    updateConnectionStatus()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    addLog("Error: $error")
                    Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onDetailedError(error: RiviumPushError) {
                runOnUiThread {
                    addLog("Detailed error [${error.code}]: ${error.message}")
                }
            }

            override fun onReconnecting(attempt: Int, nextRetryMs: Long) {
                runOnUiThread {
                    addLog("Reconnecting... attempt $attempt, next retry in ${nextRetryMs}ms")
                }
            }

            override fun onNetworkStateChanged(isAvailable: Boolean, networkType: String) {
                runOnUiThread {
                    addLog("Network: ${if (isAvailable) "Available" else "Unavailable"} ($networkType)")
                }
            }

            override fun onAppStateChanged(isInForeground: Boolean) {
                runOnUiThread {
                    addLog("App state: ${if (isInForeground) "Foreground" else "Background"}")
                }
            }
        })

        // Set inbox callback to update badge on new inbox messages
        RiviumPush.getInboxManager().setCallback(object : InboxCallback {
            override fun onMessageReceived(message: InboxMessage) {
                runOnUiThread {
                    addLog("Inbox message received: ${message.content.title}")
                    updateInboxBadge()
                }
            }

            override fun onMessageStatusChanged(messageId: String, status: InboxMessageStatus) {
                runOnUiThread {
                    updateInboxBadge()
                }
            }
        })
    }

    private fun checkPermissionAndRegister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    addLog("Notification permission already granted")
                    registerDevice()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    showPermissionRationaleDialog()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No runtime permission needed for Android < 13
            registerDevice()
        }
    }

    private fun registerDevice() {
        val userId = binding.etUserId.text.toString().takeIf { it.isNotBlank() }
        val metadata = mapOf(
            "appVersion" to BuildConfig.VERSION_NAME,
            "platform" to "android",
            "sdkExample" to true
        )

        addLog("Registering device... userId=$userId")
        RiviumPush.register(userId, metadata)
    }

    private fun unregisterDevice() {
        addLog("Unregistering device...")
        RiviumPush.unregister()
        updateDeviceInfo()
        Toast.makeText(this, "Unregistered", Toast.LENGTH_SHORT).show()
    }

    private fun subscribeTopic() {
        val topic = binding.etTopic.text.toString().trim()
        if (topic.isBlank()) {
            Toast.makeText(this, "Enter a topic name", Toast.LENGTH_SHORT).show()
            return
        }
        addLog("Subscribing to topic: $topic")
        RiviumPush.subscribeTopic(topic)
        Toast.makeText(this, "Subscribed to: $topic", Toast.LENGTH_SHORT).show()
    }

    private fun unsubscribeTopic() {
        val topic = binding.etTopic.text.toString().trim()
        if (topic.isBlank()) {
            Toast.makeText(this, "Enter a topic name", Toast.LENGTH_SHORT).show()
            return
        }
        addLog("Unsubscribing from topic: $topic")
        RiviumPush.unsubscribeTopic(topic)
        Toast.makeText(this, "Unsubscribed from: $topic", Toast.LENGTH_SHORT).show()
    }

    private fun setUserId() {
        val userId = binding.etUserId.text.toString().trim()
        if (userId.isBlank()) {
            Toast.makeText(this, "Enter a user ID", Toast.LENGTH_SHORT).show()
            return
        }
        addLog("Setting user ID: $userId")
        RiviumPush.setUserId(userId)
        Toast.makeText(this, "User ID set: $userId", Toast.LENGTH_SHORT).show()
    }

    private fun clearUserId() {
        addLog("Clearing user ID")
        RiviumPush.clearUserId()
        binding.etUserId.text?.clear()
        Toast.makeText(this, "User ID cleared", Toast.LENGTH_SHORT).show()
    }

    private fun triggerCustomEvent() {
        val eventName = "button_clicked"
        val properties = mapOf(
            "screen" to "main",
            "timestamp" to System.currentTimeMillis()
        )
        addLog("Triggering event: $eventName")
        RiviumPush.triggerInAppEvent(eventName, properties)
        Toast.makeText(this, "Event triggered: $eventName", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus() {
        val isConnected = RiviumPush.isConnected()
        binding.tvConnectionStatus.text = if (isConnected) "Connected" else "Disconnected"
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun updateDeviceInfo() {
        val deviceId = RiviumPush.getDeviceId()
        binding.tvDeviceId.text = deviceId ?: "Not registered"
        updateInboxBadge()
    }

    private fun updateInboxBadge() {
        // Try local cached count first (instant)
        val cachedCount = RiviumPush.getInboxManager().getUnreadCount()
        binding.btnInbox.text = if (cachedCount > 0) "Inbox ($cachedCount)" else "Inbox"

        // Also fetch from server to sync
        RiviumPush.fetchInboxUnreadCount(
            onSuccess = { count ->
                runOnUiThread {
                    binding.btnInbox.text = if (count > 0) "Inbox ($count)" else "Inbox"
                }
            },
            onError = { /* Ignore errors for badge count */ }
        )
    }

    private fun handleIntent(intent: Intent) {
        // Check if app was launched from a notification
        val initialMessage = RiviumPush.getInitialMessage()
        if (initialMessage != null) {
            addLog("App launched from notification: ${initialMessage.title}")
            showMessageReceivedDialog(initialMessage)
            RiviumPush.clearInitialMessage()
        }

        // Check for clicked action button
        val clickedAction = RiviumPush.getClickedAction()
        if (clickedAction != null) {
            addLog("Action clicked: ${clickedAction["actionId"]}")
            Toast.makeText(this, "Action: ${clickedAction["actionId"]}", Toast.LENGTH_SHORT).show()
        }

        // Handle deep links
        intent.data?.let { uri ->
            addLog("Deep link: $uri")
            when (uri.path) {
                "/inbox" -> startActivity(Intent(this, InboxActivity::class.java))
                "/settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> Toast.makeText(this, "Deep link: $uri", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMessageReceivedDialog(message: RiviumPushMessage) {
        // Check if activity is valid before showing dialog
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Activity not valid, skipping dialog for message: ${message.title}")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(message.title)
            .setMessage(buildString {
                append(message.body)
                message.data?.let { data ->
                    if (data.isNotEmpty()) {
                        append("\n\nData: $data")
                    }
                }
            })
            .setPositiveButton("OK", null)
            .setNeutralButton("Details") { _, _ ->
                startActivity(Intent(this, MessageDetailActivity::class.java).apply {
                    putExtra("title", message.title)
                    putExtra("body", message.body)
                    putExtra("data", message.data?.toString())
                    putExtra("imageUrl", message.imageUrl)
                    putExtra("deepLink", message.deepLink)
                })
            }
            .show()
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Permission")
            .setMessage("This app needs notification permission to receive push notifications. Please grant the permission.")
            .setPositiveButton("Grant") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Without notification permission, you won't receive push notifications. You can enable it in app settings.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        Log.d(TAG, message)
        logMessages.add(0, logEntry)
        if (logMessages.size > 100) {
            logMessages.removeAt(logMessages.size - 1)
        }

        // Update log preview
        binding.tvLogPreview.text = logMessages.take(3).joinToString("\n")
    }
}
