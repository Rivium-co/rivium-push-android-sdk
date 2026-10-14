package co.rivium.push.sdk.internal

import org.robolectric.RuntimeEnvironment
import co.rivium.push.sdk.RiviumPushErrorCode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** X-Rivium-SDK-Report: counts only, kept until a 2xx answer, nothing at all when switched off. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SdkDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        SdkDiagnostics.resetForTests()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
        SdkDiagnostics.resetForTests()
    }

    @Test fun `counts by the public error code and formats the header`() {
        SdkDiagnostics.attach(context, enabled = true)
        SdkDiagnostics.count(RiviumPushErrorCode.REGISTRATION_FAILED)
        SdkDiagnostics.count(RiviumPushErrorCode.CONNECTION_LOST)
        SdkDiagnostics.count(RiviumPushErrorCode.CONNECTION_LOST)
        assertEquals(mapOf(1400 to 1, 1002 to 2), SdkDiagnostics.pending())
        assertEquals("1002=2 1400=1", SdkDiagnostics.headerValue(SdkDiagnostics.pending()))
        assertNull(SdkDiagnostics.headerValue(emptyMap()))
    }

    @Test fun `counts made before attach are kept`() {
        SdkDiagnostics.count(1000)
        SdkDiagnostics.attach(context, enabled = true)
        SdkDiagnostics.count(1000)
        assertEquals(mapOf(1000 to 2), SdkDiagnostics.pending())
    }

    @Test fun `is bounded - 16 codes, 9999 per code`() {
        SdkDiagnostics.attach(context, enabled = true)
        for (code in 2000 until 2020) SdkDiagnostics.count(code)
        assertEquals(SdkDiagnostics.MAX_CODES, SdkDiagnostics.pending().size)
        repeat(10_050) { SdkDiagnostics.count(2000) }
        assertEquals(SdkDiagnostics.MAX_COUNT, SdkDiagnostics.pending()[2000])
    }

    @Test fun `switched off - nothing counted, kept or sent`() {
        SdkDiagnostics.attach(context, enabled = true)
        SdkDiagnostics.count(1400)
        SdkDiagnostics.attach(context, enabled = false)
        SdkDiagnostics.count(1400)
        assertTrue(SdkDiagnostics.pending().isEmpty())
        val client = OkHttpClient.Builder().addInterceptor(SdkDiagnostics.interceptor()).build()
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader(SdkDiagnostics.HEADER))
    }

    @Test fun `sent with the next request, cleared only after a 2xx, later counts stay`() {
        SdkDiagnostics.attach(context, enabled = true)
        SdkDiagnostics.count(1400)
        SdkDiagnostics.count(1400)
        val client = OkHttpClient.Builder().addInterceptor(SdkDiagnostics.interceptor()).build()
        val call = { client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close() }

        server.enqueue(MockResponse().setResponseCode(500))
        call()
        assertEquals("1400=2", server.takeRequest().getHeader(SdkDiagnostics.HEADER))
        assertEquals(mapOf(1400 to 2), SdkDiagnostics.pending())

        server.enqueue(MockResponse().setResponseCode(200))
        call()
        assertEquals("1400=2", server.takeRequest().getHeader(SdkDiagnostics.HEADER))
        assertTrue(SdkDiagnostics.pending().isEmpty())

        // Nothing pending: no header at all.
        server.enqueue(MockResponse().setResponseCode(200))
        call()
        assertNull(server.takeRequest().getHeader(SdkDiagnostics.HEADER))

        // A count added while a report is in flight is not lost.
        SdkDiagnostics.count(1400)
        val snapshot = SdkDiagnostics.pending()
        SdkDiagnostics.count(1400)
        SdkDiagnostics.acknowledge(snapshot)
        assertEquals(mapOf(1400 to 1), SdkDiagnostics.pending())
    }

    @Test fun `a crash is the SDK's only when Rivium Push code threw it`() {
        fun thrownAt(vararg classes: String) = RuntimeException("x").apply {
            stackTrace = classes.map { StackTraceElement(it, "m", "F.kt", 1) }.toTypedArray()
        }
        assertTrue(SdkDiagnostics.isOwnCrash(thrownAt("java.util.HashMap", "co.rivium.push.sdk.PNSocketManager", "com.example.App")))
        assertTrue(SdkDiagnostics.isOwnCrash(thrownAt("co.rivium.protocol.PNSocket")))
        // The app's callback threw while the SDK was calling it: the app's crash.
        assertFalse(SdkDiagnostics.isOwnCrash(thrownAt("com.example.MyListener", "co.rivium.push.sdk.RiviumPush")))
        assertFalse(SdkDiagnostics.isOwnCrash(thrownAt("android.os.Handler", "java.lang.Thread")))
        // A wrapped exception is judged by its root cause.
        val wrapped = RuntimeException("outer", thrownAt("co.rivium.push.sdk.ApiClient")).apply {
            stackTrace = arrayOf(StackTraceElement("com.example.App", "m", "F.kt", 1))
        }
        assertTrue(SdkDiagnostics.isOwnCrash(wrapped))
    }
}
