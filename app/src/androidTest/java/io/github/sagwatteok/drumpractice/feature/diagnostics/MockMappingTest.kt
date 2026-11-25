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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockMappingTest {
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
            assertEquals(0L, practice.practice.value.mockHits.total)
            practice.mockHit(Drum.KICK)
            assertEquals(1L, practice.practice.value.mockHits.counts[Drum.KICK])
            assertEquals(0L, practice.practice.value.usbHits.total)
            practice.saveMapping(Drum.KICK, null)
        }
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

    private fun withPractice(test: (PracticeViewModel, TestMidiInput) -> Unit) {
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
                    PracticeViewModel(app, dataStore) { _, receive ->
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

        override fun rescan() = Unit
        override fun connect(port: MidiPort) = Unit
        override fun disconnect() { connection.value = MidiConnection() }
        override fun close() = Unit
    }
}
