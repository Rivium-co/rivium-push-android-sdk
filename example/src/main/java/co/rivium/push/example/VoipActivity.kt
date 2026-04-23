package co.rivium.push.example

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.widget.Switch
import android.view.Gravity
import android.view.View
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import co.rivium.push.voip.RiviumPushVoip
import co.rivium.push.voip.VoipConfig
import co.rivium.push.voip.CallData
import co.rivium.push.voip.VoipCallback
import java.text.SimpleDateFormat
import java.util.*

class VoipActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var logTextView: TextView
    private val logs = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "VoIP"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scrollView.addView(root)
        setContentView(scrollView)

        // Status Card
        val statusCard = makeCard()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
            setBackgroundColor(Color.GRAY)
        }
        statusLabel = TextView(this).apply {
            text = "VoIP Not Initialized"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusLabel)
        statusCard.addView(statusRow)
        root.addView(statusCard)

        // VoIP Toggle Card
        val toggleCard = makeCard()
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggleLabel = TextView(this).apply {
            text = "Enable VoIP"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prefs = getSharedPreferences("voip_prefs", MODE_PRIVATE)
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean("voip_enabled", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("voip_enabled", isChecked).apply()
                if (isChecked) {
                    initializeVoip()
                } else {
                    RiviumPushVoip.setCallback(null)
                    addLog("VoIP disabled")
                    updateStatus()
                }
            }
        }
        toggleRow.addView(toggleLabel)
        toggleRow.addView(toggle)
        toggleCard.addView(toggleRow)

        val toggleDesc = TextView(this).apply {
            text = "Initialize VoIP SDK for incoming call handling. Required for apps with real calling features (Jitsi, WebRTC)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
        }
        toggleCard.addView(toggleDesc)
        root.addView(toggleCard)

        // Actions
        root.addView(makeSectionLabel("Actions"))

        val actionsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        actionsRow1.addView(makeButton("Simulate Call", Color.parseColor("#22C55E")) {
            simulateIncomingCall()
        })
        actionsRow1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 0) })
        actionsRow1.addView(makeButton("Check Status", Color.parseColor("#14B8A6")) {
            checkStatus()
        })
        root.addView(actionsRow1)

        val actionsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        actionsRow2.addView(makeButton("End Call", Color.parseColor("#EF4444")) {
            endActiveCall()
        })
        actionsRow2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 0) })
        actionsRow2.addView(makeButton("Re-Init", Color.parseColor("#F59E0B")) {
            initializeVoip()
        })
        root.addView(actionsRow2)

        // Info Card
        val infoCard = makeCard()
        infoCard.addView(TextView(this).apply {
            text = "How VoIP Works"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val infos = listOf(
            "Standard Push" to "Regular notifications via FCM/APNs. Works for most apps.",
            "VoIP Push" to "Full-screen incoming call UI. Wakes app from killed state. Requires real calling feature.",
            "CallData" to "Carries caller name, ID, avatar, call type (audio/video), and custom payload."
        )
        for ((title, desc) in infos) {
            infoCard.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#6366F1"))
            })
            infoCard.addView(TextView(this).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(8))
            })
        }
        root.addView(infoCard)

        // Log
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        logHeader.addView(makeSectionLabel("Call Log").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logHeader.addView(Button(this).apply {
            text = "Clear"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { logs.clear(); logTextView.text = "" }
        })
        root.addView(logHeader)

        logTextView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(150)
        }
        root.addView(logTextView)

        addLog("VoIP screen opened")
        updateStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initializeVoip() {
        val config = VoipConfig(
            appName = "RiviumPush Example",
            timeoutSeconds = 30
        )
        RiviumPushVoip.initialize(this, config)
        RiviumPushVoip.setCallback(object : VoipCallback {
            override fun onCallAccepted(callData: CallData) {
                addLog("Call accepted: ${callData.callerName}")
                Toast.makeText(this@VoipActivity, "Call accepted: ${callData.callerName}", Toast.LENGTH_SHORT).show()
            }

            override fun onCallDeclined(callData: CallData) {
                addLog("Call declined: ${callData.callerName}")
            }

            override fun onCallTimeout(callData: CallData) {
                addLog("Call timed out: ${callData.callerName}")
            }

            override fun onError(error: String) {
                addLog("Error: $error")
            }
        })
        addLog("VoIP initialized")
        updateStatus()
    }

    private fun simulateIncomingCall() {
        if (RiviumPushVoip.getConfig() == null) {
            Toast.makeText(this, "Initialize VoIP first", Toast.LENGTH_SHORT).show()
            return
        }

        val callData = CallData(
            callId = UUID.randomUUID().toString(),
            callerName = "Test Caller",
            callerId = "user_test_123",
            callerAvatar = null,
            callType = "audio"
        )
        RiviumPushVoip.showIncomingCall(callData)
        addLog("Simulated incoming call: ${callData.callId}")
    }

    private fun endActiveCall() {
        val call = RiviumPushVoip.activeCall
        if (call != null) {
            RiviumPushVoip.endCall(call.callId)
            addLog("Ended call: ${call.callId}")
        } else {
            Toast.makeText(this, "No active call", Toast.LENGTH_SHORT).show()
            addLog("No active call to end")
        }
    }

    private fun checkStatus() {
        val config = RiviumPushVoip.getConfig()
        val activeCall = RiviumPushVoip.activeCall
        val msg = buildString {
            appendLine("Initialized: ${config != null}")
            if (config != null) {
                appendLine("App Name: ${config.appName}")
                appendLine("Call Timeout: ${config.timeoutSeconds}s")
            }
            appendLine("Active Call: ${activeCall?.callerName ?: "None"}")
            appendLine("Has Callback: ${RiviumPushVoip.hasCallback()}")
        }
        AlertDialog.Builder(this)
            .setTitle("VoIP Status")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
        addLog("Status checked")
    }

    private fun updateStatus() {
        val config = RiviumPushVoip.getConfig()
        if (config != null) {
            statusDot.setBackgroundColor(Color.parseColor("#22C55E"))
            statusLabel.text = "VoIP Active (${config.appName})"
        } else {
            statusDot.setBackgroundColor(Color.GRAY)
            statusLabel.text = "VoIP Not Initialized"
        }
    }

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$time] $text")
        if (logs.size > 50) logs.removeFirst()
        logTextView.text = logs.joinToString("\n")
    }

    // Helpers
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun makeButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(color)
            setBackgroundColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }
}
