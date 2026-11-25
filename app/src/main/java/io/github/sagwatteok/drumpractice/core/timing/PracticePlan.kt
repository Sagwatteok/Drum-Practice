package io.github.sagwatteok.drumpractice.core.timing

import io.github.sagwatteok.drumpractice.core.midi.Drum

const val PRACTICE_SAMPLE_RATE = 48_000
const val STEPS_PER_BAR = 8
const val STEPS_PER_BEAT = 2
private const val NANOS_PER_SECOND = 1_000_000_000.0

data class PracticePattern(val title: String, val bars: List<List<Set<Drum>>>) {
    init {
        require(bars.isNotEmpty() && bars.all { it.size == STEPS_PER_BAR })
    }
}

data class PracticePlan(
    val bpm: Int,
    val pattern: PracticePattern,
    val bars: IntRange = pattern.bars.indices,
    val firstBar: Int = bars.first,
    val repeat: Boolean = true,
) {
    init {
        require(bpm in 40..240)
        require(!bars.isEmpty() && bars.first >= 0 && bars.last in pattern.bars.indices)
        require(firstBar in bars)
    }

    private val stepsPerMinute = bpm.toLong() * STEPS_PER_BEAT
    private val framesPerMinute = PRACTICE_SAMPLE_RATE * 60L

    val firstPlayingStep = STEPS_PER_BAR.toLong()
    val endStep: Long? = if (repeat) null else firstPlayingStep + (bars.last - firstBar + 1L) * STEPS_PER_BAR
    val endFrame: Long? get() = endStep?.let(::frameAtStep)

    fun frameAtStep(step: Long): Long = Math.floorDiv(step * framesPerMinute, stepsPerMinute)

    fun stepAtFrame(frame: Long): Long =
        Math.floorDiv((frame + 1) * stepsPerMinute - 1, framesPerMinute)

    fun positionAtStep(step: Long): PracticePosition? {
        if (step < 0 || endStep?.let { step >= it } == true) return null
        val stepInBar = (step % STEPS_PER_BAR).toInt()
        if (step < firstPlayingStep) return PracticePosition(null, stepInBar)
        val elapsedBars = (step - firstPlayingStep) / STEPS_PER_BAR
        val barsBeforeWrap = bars.last - firstBar + 1
        val bar = if (elapsedBars < barsBeforeWrap) firstBar + elapsedBars.toInt()
        else bars.first + ((elapsedBars - barsBeforeWrap) % (bars.last - bars.first + 1)).toInt()
        return PracticePosition(bar, stepInBar)
    }

    fun expectedAt(step: Long): Set<Drum> {
        val position = positionAtStep(step) ?: return emptySet()
        val bar = position.bar ?: return emptySet()
        return pattern.bars[bar][position.stepInBar]
    }
}

data class PracticePosition(val bar: Int?, val stepInBar: Int) {
    val beat: Int get() = stepInBar / STEPS_PER_BEAT + 1
}

enum class ClockSource(val label: String) {
    AUDIO_TIMESTAMP("AudioTrack 타임스탬프"),
    PLAYBACK_HEAD("AudioTrack 재생 헤드"),
}

/** Audio frame and timestamp refer to the same sample on the monotonic timebase. */
data class AudioFrameAnchor(
    val frame: Long,
    val timestampNanos: Long,
    val writtenThrough: Long,
    val source: ClockSource,
) {
    fun frameAt(eventNanos: Long): Long {
        val offset = (eventNanos - timestampNanos) * (PRACTICE_SAMPLE_RATE / NANOS_PER_SECOND)
        return (frame + kotlin.math.floor(offset).toLong()).coerceAtMost(writtenThrough)
    }

    fun nanoTimeAt(targetFrame: Long): Long =
        timestampNanos + kotlin.math.ceil((targetFrame - frame) * (NANOS_PER_SECOND / PRACTICE_SAMPLE_RATE)).toLong()
}
