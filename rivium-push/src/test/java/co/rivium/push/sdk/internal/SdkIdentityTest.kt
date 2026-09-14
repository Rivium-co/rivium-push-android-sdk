package co.rivium.push.sdk.internal

import co.rivium.push.sdk.BuildConfig
import co.rivium.push.sdk.RiviumPush
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SdkIdentityTest {

    @Test fun `native identity uses build version`() {
        assertEquals("android", SdkIdentity.native.name)
        assertEquals(BuildConfig.SDK_VERSION, RiviumPush.SDK_VERSION)
        assertEquals("android/${RiviumPush.SDK_VERSION}", SdkIdentity.resolve(null, null).headerValue)
    }

    @Test fun `valid wrapper identity wins`() {
        assertEquals(SdkIdentity("flutter", "0.1.16"), SdkIdentity.resolve("flutter", "0.1.16"))
        assertEquals("react-native/1.0.0+2", SdkIdentity.resolve("react-native", "1.0.0+2").headerValue)
    }

    @Test fun `invalid or partial wrapper identity falls back to native`() {
        assertEquals(SdkIdentity.native, SdkIdentity.resolve("flutter", null))
        assertEquals(SdkIdentity.native, SdkIdentity.resolve("flut ter", "1.0"))
        assertEquals(SdkIdentity.native, SdkIdentity.resolve("x".repeat(33), "1.0"))
    }
}
