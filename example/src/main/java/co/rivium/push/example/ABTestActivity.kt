package co.rivium.push.example

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import co.rivium.push.example.databinding.ActivityAbTestBinding
import co.rivium.push.sdk.RiviumPush
import co.rivium.push.sdk.abtesting.ABTestingCallback
import co.rivium.push.sdk.abtesting.ABTestSummary
import co.rivium.push.sdk.abtesting.ABTestVariant

/**
 * A/B Testing Example Activity
 *
 * This activity demonstrates how to use the Rivium Push A/B Testing SDK.
 *
 * SCENARIO: "Welcome Banner A/B Test"
 * =====================================
 * Imagine you're testing different welcome banners for your app:
 *
 * - Variant A (Control): Blue banner with "Welcome to our app!"
 * - Variant B: Green banner with "Start your journey today!"
 * - Variant C: Orange banner with "Discover amazing features!"
 *
 * The SDK will:
 * 1. Fetch the user's assigned variant from the server
 * 2. Cache it locally for 30 minutes
 * 3. Track impressions when the banner is shown
 * 4. Track clicks when the user taps the CTA button
 *
 * TESTING FLOW:
 * 1. Create an A/B test in Rivium Push dashboard with variants
 * 2. Copy the test ID and enter it in this activity
 * 3. Click "Get Variant" to fetch assignment
 * 4. The banner will display based on variant content
 * 5. Analytics are automatically tracked
 */
class ABTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ABTestActivity"
    }

    private lateinit var binding: ActivityAbTestBinding
    private var currentVariant: ABTestVariant? = null
    private val logMessages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAbTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupUI()
        setupABTestingCallback()
        loadActiveTests()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "A/B Testing Demo"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        // Get Active Tests
        binding.btnGetActiveTests.setOnClickListener {
            loadActiveTests()
        }

        // Get Variant Assignment
        binding.btnGetVariant.setOnClickListener {
            getVariantAssignment()
        }

        // Force Refresh Variant
        binding.btnForceRefresh.setOnClickListener {
            getVariantAssignment(forceRefresh = true)
        }

        // Get Cached Variant
        binding.btnGetCached.setOnClickListener {
            getCachedVariant()
        }

        // Track Impression
        binding.btnTrackImpression.setOnClickListener {
            trackImpression()
        }

        // Track Click (CTA button)
        binding.btnBannerCta.setOnClickListener {
            trackClicked()
        }

        // Clear Cache
        binding.btnClearCache.setOnClickListener {
            clearCache()
        }

        // Initially hide banner
        binding.cardBanner.visibility = View.GONE
    }

    private fun setupABTestingCallback() {
        RiviumPush.setABTestingCallback(object : ABTestingCallback {
            override fun onVariantAssigned(variant: ABTestVariant) {
                runOnUiThread {
                    addLog("Callback: Variant assigned - ${variant.variantName}")
                    displayVariant(variant)
                }
            }

            override fun onError(testId: String?, error: String) {
                runOnUiThread {
                    addLog("Callback: Error for test $testId - $error")
                    Toast.makeText(this@ABTestActivity, "Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadActiveTests() {
        addLog("Fetching active A/B tests...")
        binding.progressBar.visibility = View.VISIBLE

        RiviumPush.getActiveABTests(
            onSuccess = { tests ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    displayActiveTests(tests)
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    addLog("Error fetching tests: $error")
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun displayActiveTests(tests: List<ABTestSummary>) {
        if (tests.isEmpty()) {
            addLog("No active A/B tests found")
            binding.tvActiveTests.text = "No active tests\n\nCreate a test in Rivium Push dashboard first!"
            return
        }

        addLog("Found ${tests.size} active test(s)")
        val testInfo = buildString {
            append("Active Tests:\n\n")
            tests.forEachIndexed { index, test ->
                append("${index + 1}. ${test.name}\n")
                append("   ID: ${test.id}\n")
                append("   Variants: ${test.variantCount}\n\n")
            }
            append("Tap a test to select it")
        }
        binding.tvActiveTests.text = testInfo

        // Auto-fill first test ID
        if (tests.isNotEmpty() && binding.etTestId.text.isNullOrEmpty()) {
            binding.etTestId.setText(tests.first().id)
        }

        // Show selection dialog if multiple tests
        if (tests.size > 1) {
            showTestSelectionDialog(tests)
        }
    }

    private fun showTestSelectionDialog(tests: List<ABTestSummary>) {
        val testNames = tests.map { "${it.name} (${it.variantCount} variants)" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select A/B Test")
            .setItems(testNames) { _, which ->
                binding.etTestId.setText(tests[which].id)
                addLog("Selected test: ${tests[which].name}")
            }
            .show()
    }

    private fun getVariantAssignment(forceRefresh: Boolean = false) {
        val testId = binding.etTestId.text.toString().trim()
        if (testId.isEmpty()) {
            Toast.makeText(this, "Enter a test ID", Toast.LENGTH_SHORT).show()
            return
        }

        addLog("Getting variant for test: $testId (forceRefresh=$forceRefresh)")
        binding.progressBar.visibility = View.VISIBLE

        RiviumPush.getABTestVariant(
            testId = testId,
            forceRefresh = forceRefresh,
            onSuccess = { variant ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    addLog("Got variant: ${variant.variantName} (${variant.variantId})")
                    displayVariant(variant)
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    addLog("Error getting variant: $error")
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun getCachedVariant() {
        val testId = binding.etTestId.text.toString().trim()
        if (testId.isEmpty()) {
            Toast.makeText(this, "Enter a test ID", Toast.LENGTH_SHORT).show()
            return
        }

        val variant = RiviumPush.getCachedABTestVariant(testId)
        if (variant != null) {
            addLog("Cached variant found: ${variant.variantName}")
            displayVariant(variant)
        } else {
            addLog("No cached variant for test: $testId")
            Toast.makeText(this, "No cached variant", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayVariant(variant: ABTestVariant) {
        currentVariant = variant
        binding.cardBanner.visibility = View.VISIBLE

        // Display variant info
        binding.tvVariantInfo.text = buildString {
            append("Test ID: ${variant.testId}\n")
            append("Variant ID: ${variant.variantId}\n")
            append("Variant Name: ${variant.variantName}")
        }

        // Apply variant content to banner
        val content = variant.content
        if (content != null) {
            binding.tvBannerTitle.text = content.title ?: "Welcome!"
            binding.tvBannerBody.text = content.body ?: "Check out our features"

            // Apply color based on variant name or custom data
            val bannerColor = when {
                variant.variantName.contains("control", ignoreCase = true) -> "#2196F3" // Blue
                variant.variantName.contains("green", ignoreCase = true) -> "#4CAF50" // Green
                variant.variantName.contains("orange", ignoreCase = true) -> "#FF9800" // Orange
                variant.variantName.contains("A", ignoreCase = true) -> "#2196F3" // Blue
                variant.variantName.contains("B", ignoreCase = true) -> "#4CAF50" // Green
                variant.variantName.contains("C", ignoreCase = true) -> "#FF9800" // Orange
                else -> "#9C27B0" // Purple default
            }

            try {
                binding.cardBanner.setCardBackgroundColor(Color.parseColor(bannerColor))
            } catch (e: Exception) {
                binding.cardBanner.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            }

            // Show custom data if present
            content.data?.let { data ->
                if (data.isNotEmpty()) {
                    addLog("Variant data: $data")
                }
            }
        } else {
            // Default content if no content configured
            binding.tvBannerTitle.text = "Variant: ${variant.variantName}"
            binding.tvBannerBody.text = "This variant has no custom content configured"
            binding.cardBanner.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }

        // Auto-track impression when variant is displayed
        trackImpression()
    }

    private fun trackImpression() {
        val variant = currentVariant
        if (variant == null) {
            Toast.makeText(this, "Get a variant first", Toast.LENGTH_SHORT).show()
            return
        }

        addLog("Tracking impression for variant: ${variant.variantName}")

        RiviumPush.trackABTestImpression(
            testId = variant.testId,
            variantId = variant.variantId,
            onSuccess = {
                runOnUiThread {
                    addLog("Impression tracked successfully")
                    Toast.makeText(this, "Impression tracked!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    addLog("Error tracking impression: $error")
                }
            }
        )
    }

    private fun trackClicked() {
        val variant = currentVariant
        if (variant == null) {
            Toast.makeText(this, "Get a variant first", Toast.LENGTH_SHORT).show()
            return
        }

        addLog("Tracking click for variant: ${variant.variantName}")

        RiviumPush.trackABTestClicked(
            testId = variant.testId,
            variantId = variant.variantId,
            onSuccess = {
                runOnUiThread {
                    addLog("Click tracked successfully")
                    Toast.makeText(this, "Click tracked! CTA was tapped.", Toast.LENGTH_SHORT).show()

                    // Simulate CTA action
                    showCtaActionDialog(variant)
                }
            },
            onError = { error ->
                runOnUiThread {
                    addLog("Error tracking click: $error")
                }
            }
        )
    }

    private fun showCtaActionDialog(variant: ABTestVariant) {
        AlertDialog.Builder(this)
            .setTitle("CTA Clicked!")
            .setMessage(buildString {
                append("You clicked the CTA for variant: ${variant.variantName}\n\n")
                append("In a real app, this would:\n")
                append("- Navigate to a feature screen\n")
                append("- Open a deep link\n")
                append("- Trigger a conversion event\n\n")
                append("The click has been tracked in Rivium Push analytics.")
            })
            .setPositiveButton("Got it!", null)
            .show()
    }

    private fun clearCache() {
        addLog("Clearing A/B test cache...")
        RiviumPush.clearABTestingCache()
        currentVariant = null
        binding.cardBanner.visibility = View.GONE
        binding.tvVariantInfo.text = "No variant loaded"
        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        addLog("Cache cleared")
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        Log.d(TAG, message)
        logMessages.add(0, logEntry)

        // Keep last 50 logs
        if (logMessages.size > 50) {
            logMessages.removeAt(logMessages.size - 1)
        }

        // Update log view
        binding.tvLog.text = logMessages.take(10).joinToString("\n")
    }
}
