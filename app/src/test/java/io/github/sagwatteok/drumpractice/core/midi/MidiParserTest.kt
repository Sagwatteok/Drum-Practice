package io.github.sagwatteok.drumpractice.core.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private val parser = MidiParser()
    private val messages = mutableListOf<MidiMessage>()

    @Test fun readsSplitAndPackedMessagesWithRunningStatus() {
        accept(0x99, 36)
        accept(0xf8, 112, 38, 0, 0xb9, 4, 100, 0x89, 36, 32)
        assertEquals(listOf(MidiKind.NOTE_ON, MidiKind.NOTE_OFF, MidiKind.CONTROL_CHANGE, MidiKind.NOTE_OFF),
            messages.map { it.kind })
        assertEquals(listOf(36, 38, 4, 36), messages.map { it.number })
        assertEquals(listOf(112, 0, 100, 32), messages.map { it.value })
        assertEquals(List(4) { 10 }, messages.map { it.channel })
    }

    @Test fun doesNotTreatSystemOrUnsupportedChannelMessagesAsHits() {
        accept(0x90, 38, 0xf0, 38, 100, 0xf7, 36, 94, 0xc1, 25, 26,
            0xd1, 55, 0xe1, 2, 3, 0x90, 42, 65)
        assertEquals(listOf(42), messages.map { it.number })
    }

    @Test fun usesOnlyPositiveNonFutureSourceTimestamps() {
        for (source in listOf(75L, 0L, -1L, 101L)) {
            parser.accept(byteArrayOf(0x90.toByte(), 36, 104), source, 100, messages::add)
        }
        assertEquals(listOf(75L, 100L, 100L, 100L), messages.map { it.timestampNanos })
        assertEquals(listOf(75L, 0L, -1L, 101L), messages.map { it.sourceTimestampNanos })
        assertEquals(List(4) { 100L }, messages.map { it.receivedAtNanos })
    }

    private fun accept(vararg bytes: Int) {
        parser.accept(bytes.map(Int::toByte).toByteArray(), 50, 100, messages::add)
    }
}
