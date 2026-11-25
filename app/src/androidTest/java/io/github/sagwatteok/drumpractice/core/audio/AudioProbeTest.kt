package io.github.sagwatteok.drumpractice.core.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioProbeTest {
    @Test fun staticTrackPlaysAndReportsActualRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var probe: AudioProbe
        instrumentation.runOnMainSync {
            probe = AudioProbe(instrumentation.targetContext)
            probe.play(null)
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 4_000
            while (probe.snapshot.value.actualRoute == null && probe.snapshot.value.playing &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            val playing = probe.snapshot.value
            assertTrue(playing.status, playing.playing)
            assertNotNull(playing.status, playing.actualRoute)
            instrumentation.runOnMainSync { probe.stop() }
            assertFalse(probe.snapshot.value.playing)
        } finally {
            instrumentation.runOnMainSync { probe.close() }
        }
    }
}
