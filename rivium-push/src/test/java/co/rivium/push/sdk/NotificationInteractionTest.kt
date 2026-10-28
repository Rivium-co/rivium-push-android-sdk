package co.rivium.push.sdk

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Action buttons open an activity (Android 12+ blocks notification
 * trampolines), and taps / action taps / swipe-aways are reported as
 * opened / clicked / dismissed receipts, once per message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NotificationInteractionTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RiviumPushConfig.SERVER_URL = server.url("/").toString().removeSuffix("/")
        context = RuntimeEnvironment.getApplication()
        // What RiviumPush.init() persists, so receipts work in a process started by a tap.
        context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE).edit()
            .putString("apiKey", "rv_live_test")
            .putString("device_id", "device-1")
            .commit()
    }

    @After
    fun tearDown() {
        server.shutdown()
        RiviumPushConfig.resetDefaults()
    }

    private fun uniqueId() = "msg-" + UUID.randomUUID().toString()

    private fun message(id: String) = RiviumPushMessage(
        title = "Title",
        body = "Body",
        messageId = id,
        actions = listOf(NotificationAction(id = "reply", title = "Reply", action = "myapp://reply/1"))
    )

    @Test
    fun `action buttons open an activity and dismissal goes to the receiver`() {
        val id = uniqueId()
        NotificationHelper(context, RiviumPushConfig(apiKey = "rv_live_test")).showNotification(message(id))
        shadowOf(Looper.getMainLooper()).idle()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.last()

        val actionIntent = posted.actions.single().actionIntent
        val shadowAction = shadowOf(actionIntent)
        assertTrue("action button must open an activity, not a receiver", shadowAction.isActivityIntent)
        val saved = shadowAction.savedIntent
        assertEquals(NotificationActionActivity::class.java.name, saved.component?.className)
        assertEquals(NotificationHelper.ACTION_BUTTON_CLICKED, saved.action)
        assertEquals("reply", saved.getStringExtra(NotificationHelper.EXTRA_ACTION_ID))
        assertEquals(id, saved.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID))
        assertTrue(saved.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val shadowDelete = shadowOf(posted.deleteIntent)
        assertTrue(shadowDelete.isBroadcastIntent)
        assertEquals(NotificationActionReceiver.ACTION_NOTIFICATION_DISMISSED, shadowDelete.savedIntent.action)
        assertEquals(id, shadowDelete.savedIntent.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID))

        // Body taps still launch the app directly.
        assertTrue(shadowOf(posted.contentIntent).isActivityIntent)
    }

    @Test
    fun `action tap opens the deep link, keeps getClickedAction and reports clicked once`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val id = uniqueId()
        val tap = Intent(NotificationHelper.ACTION_BUTTON_CLICKED).apply {
            putExtra(NotificationHelper.EXTRA_ACTION_ID, "reply")
            putExtra(NotificationHelper.EXTRA_MESSAGE_ID, id)
            putExtra(NotificationHelper.EXTRA_DEEP_LINK, "myapp://reply/1")
        }
        val launched = mutableListOf<Intent>()

        NotificationActionReceiver.handleTap(context, tap) { launched.add(it) }
        // A second tap on the same message (e.g. a re-posted copy) is not reported again.
        NotificationActionReceiver.handleTap(context, tap) { launched.add(it) }

        assertEquals(2, launched.size)
        assertEquals(Intent.ACTION_VIEW, launched[0].action)
        assertEquals("myapp://reply/1", launched[0].dataString)
        val prefs = context.getSharedPreferences("rivium_push_prefs", Context.MODE_PRIVATE)
        assertEquals("reply", prefs.getString("clicked_action_id", null))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/receipts/clicked", request.path)
        assertEquals("rv_live_test", request.getHeader("x-api-key"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(id, body.getString("messageId"))
        assertEquals("device-1", body.getString("deviceId"))
        assertEquals("reply", body.getString("actionId"))
        assertNull("clicked must be sent once per message", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `swipe-away is reported as dismissed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val id = uniqueId()
        NotificationActionReceiver().onReceive(
            context,
            Intent(NotificationActionReceiver.ACTION_NOTIFICATION_DISMISSED)
                .putExtra(NotificationHelper.EXTRA_MESSAGE_ID, id)
        )
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/receipts/dismissed", request.path)
        assertEquals(id, JSONObject(request.body.readUtf8()).getString("messageId"))
    }

    @Test
    fun `opened receipt is sent once per message and skipped without an id`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val id = uniqueId()
        RiviumPushService.reportInteraction(context, ApiClient.Interaction.OPENED, null)
        RiviumPushService.reportInteraction(context, ApiClient.Interaction.OPENED, "")
        RiviumPushService.reportInteraction(context, ApiClient.Interaction.OPENED, id)
        RiviumPushService.reportInteraction(context, ApiClient.Interaction.OPENED, id)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/receipts/opened", request.path)
        assertEquals(id, JSONObject(request.body.readUtf8()).getString("messageId"))
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }
}
