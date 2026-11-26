package io.github.sagwatteok.drumpractice.feature.diagnostics

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.MidiConnection
import io.github.sagwatteok.drumpractice.core.midi.MidiInput
import io.github.sagwatteok.drumpractice.core.midi.MidiKind
import io.github.sagwatteok.drumpractice.core.midi.MidiMessage
import io.github.sagwatteok.drumpractice.core.midi.MidiPacketReceiver
import io.github.sagwatteok.drumpractice.core.midi.MidiPort
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.core.scoring.Judgment
import io.github.sagwatteok.drumpractice.feature.practice.InputLoss
import io.github.sagwatteok.drumpractice.feature.practice.TransportState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockMappingTest {
    @Test fun overloadKeepsAcceptedChordsAndMarksOnlyTheAffectedAttempt() {
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { worker ->
            withPractice(worker) { practice, input ->
                input.attach(1)
                onMain {
                    practice.rhythm.setBpm(240)
                    practice.startRhythm(null)
                }
                val playing = awaitLesson(practice) { it.position?.let { p -> p.bar == 0 && p.stepInBar == 0 } == true }
                val target = playing.plan.frameAtStep(playing.plan.firstPlayingStep)
                val anchor = checkNotNull(practice.rhythm.capturedReading()).sample.anchor
                val time = anchor.nanoTimeAt(target)

                val frozen = CountDownLatch(1)
                val release = CountDownLatch(1)
                executor.execute {
                    frozen.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                assertTrue(frozen.await(5, TimeUnit.SECONDS))
                val loss: InputLoss
                try {
                    repeat(320) { index ->
                        input.chord(1, time + index * 1_000L)
                    }
                    val rejected = practice.inputLoss.value
                    assertTrue(rejected.packets > 0)
                    assertEquals(rejected.packets * 2, rejected.noteOns)
                    input.controlChange(1, time)
                    loss = practice.inputLoss.value
                    assertEquals(rejected.packets + 1, loss.packets)
                    assertEquals(rejected.noteOns, loss.noteOns)
                    // Packets preceding the disconnect still belong to the original attempt.
                    input.disconnect()
                    input.chord(1, time)
                    assertEquals(loss.add(2), practice.inputLoss.value)
                    Thread.sleep(200L)
                } finally {
                    release.countDown()
                }

                val accepted = 320 - (loss.packets - 1).toInt()
                assertTrue(accepted > 100)
                val received = await(practice) { it.usbHits.total == accepted * 2L }
                assertEquals(100, received.logs.size)
                assertEquals((accepted - 1 downTo accepted - 100).map { time + it * 1_000L },
                    received.logs.map { it.messages.first().timestampNanos })
                val affected = awaitLesson(practice) { it.inputLoss == loss }
                assertEquals(2, affected.scores.getValue(HitSource.USB).count(Judgment.PERFECT))
                onMain { practice.rhythm.stop() }
                awaitLesson(practice) { !it.running && it.inputLoss == loss }

                onMain { practice.clearSession() }
                await(practice) { it.usbHits.total == 0L && it.logs.isEmpty() }
                assertEquals(InputLoss(), practice.inputLoss.value)
                assertEquals(loss, practice.rhythm.snapshot.value.inputLoss)
                input.attach(2)
                onMain {
                    input.note(2, 38)
                    // The owner must make progress even while the Android main thread is occupied.
                    await(practice) { it.usbHits.total == 1L }
                }
                onMain { practice.startRhythm(null) }
                awaitLesson(practice) { it.running && it.inputLoss == InputLoss() }
                onMain { practice.rhythm.stop() }
                awaitLesson(practice) { !it.running }
            }
        }
    }

    @Test fun rapidTempoChangesAndStopRestartUseTheLastPlan() = withPractice { practice, _ ->
        onMain { practice.startRhythm(null) }
        awaitLesson(practice) { it.position != null }
        onMain {
            practice.rhythm.setBpm(50)
            practice.rhythm.setBpm(55)
            practice.rhythm.stop()
            practice.startRhythm(null)
        }
        val restarted = awaitLesson(practice) { it.plan.bpm == 55 && it.position != null }
        assertTrue(restarted.running)
        assertNull(restarted.position?.bar)
        onMain { practice.rhythm.stop() }
        awaitLesson(practice) { !it.running }
    }

    @Test fun changingTempoKeepsTheSelectedMeasureAndMockScoresOnlyItsOwnChords() = withPractice { practice, _ ->
        val transport = practice.rhythm
        onMain {
            transport.setPlan(transport.snapshot.value.plan.copy(bars = 1..1, firstBar = 1))
            transport.setAutoMock(true)
            practice.startRhythm(null)
        }
        try {
            awaitLesson(practice) { it.plan.bpm == 60 && it.position?.beat == 1 }
            onMain { transport.setBpm(50) }
            val newCountIn = awaitLesson(practice) {
                it.plan.bpm == 50 && it.position.let { position -> position != null && position.bar == null }
            }
            assertEquals(1..1, newCountIn.plan.bars)
            assertEquals(1, newCountIn.plan.firstBar)
            val downbeat = awaitLesson(practice) {
                it.position?.let { position -> position.bar == 1 && position.stepInBar == 0 } == true &&
                    (it.scores[HitSource.MOCK]?.count(Judgment.PERFECT) ?: 0) >= 2
            }
            assertEquals(1, downbeat.position?.beat)
            assertEquals(0, downbeat.scores.getValue(HitSource.USB).points)
            awaitLesson(practice) {
                it.position?.let { position -> position.bar == 1 && position.stepInBar >= 6 } == true
            }
            val repeated = awaitLesson(practice) {
                it.position?.let { position -> position.bar == 1 && position.stepInBar == 0 } == true
            }
            assertEquals(50, repeated.plan.bpm)
            assertEquals(1, repeated.position?.beat)
            assertEquals(0, repeated.scores.getValue(HitSource.USB).count(Judgment.MISS))
        } finally {
            onMain { transport.stop() }
        }
    }

    @Test fun learnRequiresActivePortAndDoesNotSurviveSessionChanges() = withPractice { practice, input ->
        onMain {
            practice.learn(Drum.KICK)
            assertNull(practice.practice.value.learning)
            practice.saveMapping(Drum.KICK, 35)
        }
        await(practice) { it.mapping[Drum.KICK] == 35 }

        input.attach(1)
        val beforeLearn = System.nanoTime()
        onMain {
            practice.learn(Drum.KICK)
            assertEquals(Drum.KICK, practice.practice.value.learning?.drum)
            practice.mockHit(Drum.SNARE)
            assertEquals(Drum.KICK, practice.practice.value.learning?.drum)
            input.note(1, 60, beforeLearn)
        }
        await(practice) { it.usbHits.total == 1L }
        assertEquals(Drum.KICK, practice.practice.value.learning?.drum)

        input.disconnect()
        await(practice) { it.learning == null && it.noteStatus.contains("취소") }
        input.attach(2)
        onMain {
            practice.learn(Drum.KICK)
            input.note(1, 61)
        }
        await(practice) { it.usbHits.total == 2L }
        assertEquals(Drum.KICK, practice.practice.value.learning?.drum)
        onMain { input.note(2, 62) }
        await(practice) { it.learning == null && it.mapping[Drum.KICK] == 62 }
    }

    @Test fun displacedAndRemovedNotesDoNotProduceMockHits() = withPractice { practice, _ ->
        onMain { practice.saveMapping(Drum.KICK, 38) }
        val updated = await(practice) { it.mapping[Drum.KICK] == 38 }
        assertNull(updated.mapping[Drum.SNARE])
        onMain {
            practice.mockHit(Drum.SNARE)
            practice.mockHit(Drum.KICK)
        }
        val mapped = await(practice) { it.mockHits.total == 1L }
        assertEquals(1L, mapped.mockHits.counts[Drum.KICK])
        assertEquals(0L, mapped.usbHits.total)
        onMain { practice.saveMapping(Drum.KICK, null) }
        await(practice) { Drum.KICK !in it.mapping }
        onMain {
            practice.mockHit(Drum.KICK)
            assertEquals(1L, practice.practice.value.mockHits.total)
            practice.setMockConnected(false)
            practice.mockHit(Drum.RIDE)
            assertEquals(1L, practice.practice.value.mockHits.total)
            assertFalse(practice.practice.value.mockConnected)
            practice.setMockConnected(true)
            practice.saveMapping(Drum.KICK, 36)
        }
        await(practice) { it.mapping[Drum.KICK] == 36 }
        onMain {
            practice.clearSession()
            practice.startMock(120, MockPattern.CHORD)
        }
        val pads = setOf(Drum.KICK, Drum.CLOSED_HI_HAT)
        val chord = await(practice) { it.activePads == pads }
        assertEquals(pads, chord.mockHits.latestHits.map { it.drumType }.toSet())
        assertEquals(0L, chord.usbHits.total)
        onMain { practice.stopMock() }
        val settled = await(practice) { it.activePads.isEmpty() }
        assertEquals(pads, settled.mockHits.latestHits.map { it.drumType }.toSet())
    }

    private fun withPractice(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        test: (PracticeViewModel, TestMidiInput) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val mappings = File(app.cacheDir, "mock-mapping-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { mappings }
        val models = ViewModelStore()
        lateinit var practice: PracticeViewModel
        lateinit var input: TestMidiInput
        onMain {
            val factory = viewModelFactory {
                initializer {
                    PracticeViewModel(app, dataStore, dispatcher) { _, receive ->
                        TestMidiInput(receive).also { input = it }
                    }
                }
            }
            practice = ViewModelProvider(models, factory)[PracticeViewModel::class.java]
        }
        try {
            await(practice) { it.mappingReady }
            test(practice, input)
        } finally {
            onMain { models.clear() }
            scope.cancel()
            mappings.delete()
        }
    }

    private fun await(practice: PracticeViewModel, predicate: (PracticeState) -> Boolean) =
        runBlocking { withTimeout(5_000) { practice.practice.first(predicate) } }

    private fun awaitLesson(practice: PracticeViewModel, predicate: (TransportState) -> Boolean) =
        runBlocking { withTimeout(9_000) { practice.rhythm.snapshot.first(predicate) } }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class TestMidiInput(private val receive: MidiPacketReceiver) : MidiInput {
        private val port = MidiPort(1, 0, "Test MIDI", "test-midi")
        override val supported = true
        override val connection = MutableStateFlow(MidiConnection())

        fun attach(session: Int) {
            connection.value = MidiConnection(active = port, activeSession = session)
        }

        fun note(session: Int, note: Int, received: Long = System.nanoTime()) {
            val message = MidiMessage(MidiKind.NOTE_ON, 10, note, 100, received, received, received)
            receive(byteArrayOf(0x99.toByte(), note.toByte(), 100), listOf(message), session)
        }

        fun chord(session: Int, timestamp: Long) {
            val messages = listOf(36, 42).map { note ->
                MidiMessage(MidiKind.NOTE_ON, 10, note, 100, timestamp, System.nanoTime(), timestamp)
            }
            receive(byteArrayOf(0x99.toByte(), 36, 100, 42, 100), messages, session)
        }

        fun controlChange(session: Int, timestamp: Long) {
            val cc = MidiMessage(MidiKind.CONTROL_CHANGE, 10, 4, 100, timestamp, System.nanoTime(), timestamp)
            receive(byteArrayOf(0xb9.toByte(), 4, 100), listOf(cc), session)
        }

        override fun rescan() = Unit
        override fun connect(port: MidiPort) = Unit
        override fun disconnect() { connection.value = MidiConnection() }
        override fun close() = Unit
    }
}
