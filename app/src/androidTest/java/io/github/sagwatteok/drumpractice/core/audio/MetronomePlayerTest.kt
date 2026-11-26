package io.github.sagwatteok.drumpractice.core.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sagwatteok.drumpractice.core.timing.PracticePattern
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetronomePlayerTest {
    @Test fun aReplacementTrackWaitsForTheWriterEvenAfterAnIntermediateRestartIsCancelled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = MetronomePlayer(context)
        val plan = PracticePlan(60, PracticePattern("test", listOf(List(8) { emptySet() })))
        val entered = Channel<Unit>(Channel.CONFLATED)
        val release = CountDownLatch(1)
        val secondReads = Channel<MetronomeReading>(Channel.CONFLATED)
        val first = launch {
            player.play(plan, null) {
                entered.trySend(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withTimeout(5_000) { entered.receive() }
            first.cancel()
            val intermediate = launch(start = CoroutineStart.UNDISPATCHED) {
                player.play(plan.copy(bpm = 50), null) { error("The waiting track must not start") }
            }
            intermediate.cancelAndJoin()
            val replacement = launch { player.play(plan.copy(bpm = 55), null) { secondReads.trySend(it) } }
            try {
                assertNull(withTimeoutOrNull(250) { secondReads.receive() })
                release.countDown()
                assertTrue(withTimeout(5_000) { secondReads.receive() }.playedFrame > 0)
            } finally {
                release.countDown()
                replacement.cancelAndJoin()
            }
        } finally {
            release.countDown()
            first.cancelAndJoin()
        }
    }

    @Test fun streamReportsAdvancingFramesWithAnActualOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = MetronomePlayer(context)
        val plan = PracticePlan(240, PracticePattern("test", listOf(List(8) { emptySet() })), repeat = false)
        val readings = Channel<MetronomeReading>(Channel.UNLIMITED)
        val playback = launch {
            player.play(plan, null) { readings.trySend(it) }
            readings.close()
        }
        try {
            withTimeout(9_000) {
                var previous = 0L
                var differentFrameCount = 0
                for (reading in readings) {
                    assertTrue(reading.playedFrame >= previous)
                    assertTrue(reading.anchor.frame <= reading.anchor.writtenThrough)
                    assertTrue(reading.anchor.timestampNanos <= System.nanoTime())
                    if (reading.playedFrame > previous) differentFrameCount++
                    if (reading.playedFrame >= 48_000) assertNotNull(reading.routeName)
                    previous = reading.playedFrame
                }
                assertTrue(differentFrameCount > 1)
                assertTrue(previous == plan.endFrame)
            }
        } finally {
            playback.cancelAndJoin()
        }
    }
}
