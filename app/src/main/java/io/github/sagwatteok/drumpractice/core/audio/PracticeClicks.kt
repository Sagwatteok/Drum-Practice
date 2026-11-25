package io.github.sagwatteok.drumpractice.core.audio

import io.github.sagwatteok.drumpractice.core.timing.PRACTICE_SAMPLE_RATE
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BAR
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BEAT
import kotlin.math.PI
import kotlin.math.sin

class PracticeClicks(private val plan: PracticePlan) {
    private val regular = click(880.0, 3_000)
    private val accent = click(1_320.0, 4_200)

    fun render(firstFrame: Long, target: ShortArray, frameCount: Int) {
        for (index in 0 until frameCount) {
            val frame = firstFrame + index
            val step = plan.stepAtFrame(frame)
            val samples = if (step % STEPS_PER_BAR == 0L) accent else regular
            val tickOffset = frame - plan.frameAtStep(step)
            target[index] = if (step % STEPS_PER_BEAT == 0L && tickOffset in 0L until samples.size.toLong()) {
                samples[tickOffset.toInt()]
            } else 0
        }
    }

    private fun click(hz: Double, amplitude: Int): ShortArray = ShortArray(PRACTICE_SAMPLE_RATE / 30) { frame ->
        val duration = PRACTICE_SAMPLE_RATE / 30.0
        (sin(2 * PI * hz * frame / PRACTICE_SAMPLE_RATE) * amplitude * (1 - frame / duration)).toInt().toShort()
    }
}
