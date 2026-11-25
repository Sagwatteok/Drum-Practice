package io.github.sagwatteok.drumpractice.core.midi

enum class MidiKind { NOTE_ON, NOTE_OFF, CONTROL_CHANGE }

data class MidiMessage(
    val kind: MidiKind,
    val channel: Int,
    val number: Int,
    val value: Int,
    val timestampNanos: Long,
    val receivedAtNanos: Long,
    val sourceTimestampNanos: Long,
)

/** One parser per input port. Call from one thread or serialize calls at the source. */
class MidiParser {
    private var status = 0
    private val data = IntArray(2)
    private var size = 0
    private var inSysEx = false

    fun accept(
        bytes: ByteArray,
        timestampNanos: Long,
        receivedAtNanos: Long,
        emit: (MidiMessage) -> Unit,
    ) {
        val eventTime = if (timestampNanos in 1..receivedAtNanos) timestampNanos else receivedAtNanos
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            // Real-time bytes can appear between any two bytes without disturbing running status.
            if (value >= 0xf8) continue
            if (value >= 0x80) {
                inSysEx = value == 0xf0
                status = value.takeIf { it < 0xf0 } ?: 0
                size = 0
                continue
            }
            if (inSysEx || status == 0) continue
            data[size++] = value
            val command = status and 0xf0
            val length = if (command == 0xc0 || command == 0xd0) 1 else 2
            if (size != length) continue
            size = 0
            val kind = when (command) {
                0x80 -> MidiKind.NOTE_OFF
                0x90 -> if (data[1] == 0) MidiKind.NOTE_OFF else MidiKind.NOTE_ON
                0xb0 -> MidiKind.CONTROL_CHANGE
                else -> continue
            }
            emit(MidiMessage(kind, (status and 0xf) + 1, data[0], data[1], eventTime,
                receivedAtNanos, timestampNanos))
        }
    }
}
