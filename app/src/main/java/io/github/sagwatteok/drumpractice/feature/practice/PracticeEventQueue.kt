package io.github.sagwatteok.drumpractice.feature.practice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val EVENT_CAPACITY = 256

/** A single consumer owns practice state. Submissions never run their action on the caller. */
internal class PracticeEventQueue(
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    capacity: Int = EVENT_CAPACITY,
) {
    private val queue = Channel<() -> Unit>(capacity)

    init {
        scope.launch(dispatcher) {
            for (action in queue) action()
        }
    }

    /** UI/lifecycle controls are reliable and suspend only their sender, never the MIDI callback. */
    fun post(action: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { queue.send(action) }
    }

    fun offerMidi(action: () -> Unit, onSaturated: () -> Unit) {
        val result = queue.trySend(action)
        if (result.isFailure && !result.isClosed) onSaturated()
    }

    /** Clock updates are cumulative. A skipped sample is superseded by a later sample. */
    fun offerProgress(action: () -> Unit) {
        queue.trySend(action)
    }

    fun close() = queue.cancel()
}

data class InputLoss(val packets: Long = 0, val noteOns: Long = 0) {
    fun add(noteOns: Int) = InputLoss(packets + 1, this.noteOns + noteOns)
}

/** Scoped to one attempt, including callbacks already submitting when that attempt ends. */
internal class AttemptInputLoss {
    private val state = AtomicReference(InputLoss())
    val publishPending = AtomicBoolean(false)

    fun record(noteOns: Int) {
        state.getAndUpdate { it.add(noteOns) }
    }

    fun snapshot(): InputLoss = state.get()
}

internal data class UsbObservation(val session: Int?, val atNanos: Long)
