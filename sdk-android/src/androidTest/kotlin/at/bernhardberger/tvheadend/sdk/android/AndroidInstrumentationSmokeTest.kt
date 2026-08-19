package at.bernhardberger.tvheadend.sdk.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class AndroidInstrumentationSmokeTest {
    @Test
    fun Android_runtime_API_is_available() {
        assertNotNull(Build.VERSION.RELEASE)
    }
}
