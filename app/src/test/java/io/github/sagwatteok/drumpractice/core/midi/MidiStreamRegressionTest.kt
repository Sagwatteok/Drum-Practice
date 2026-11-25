package io.github.sagwatteok.drumpractice.core.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiStreamRegressionTest {
    private val parser = MidiParser()
    private val messages = mutableListOf<MidiMessage>()

    @Test
    fun systemCommonClearsRunningStatusAndFragmentedSysExDoesNotBecomeDrumHits() {
        accept(0x99, 36, 93, 0xf2, 42, 60, 38, 45)
        accept(0xf0, 0x43, 42, 45, 0xf8)
        accept(38, 127, 0xf7, 36, 95, 0xb9, 4, 127, 0x89, 36, 64)

        assertEquals(
            listOf(
                MidiMessage(MidiKind.NOTE_ON, 10, 36, 93, 100, 110, 100),
                MidiMessage(MidiKind.CONTROL_CHANGE, 10, 4, 127, 100, 110, 100),
                MidiMessage(MidiKind.NOTE_OFF, 10, 36, 64, 100, 110, 100),
            ),
            messages,
        )
    }

    private fun accept(vararg bytes: Int) {
        parser.accept(ByteArray(bytes.size) { bytes[it].toByte() }, 100, 110) {
            messages.add(it)
        }
    }
}
