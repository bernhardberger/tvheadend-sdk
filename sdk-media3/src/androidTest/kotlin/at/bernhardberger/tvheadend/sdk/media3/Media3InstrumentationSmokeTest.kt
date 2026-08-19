package at.bernhardberger.tvheadend.sdk.media3

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class Media3InstrumentationSmokeTest {
    @Test
    fun Android_runtime_API_is_available() {
        assertNotNull(Build.VERSION.RELEASE)
    }
}
