package io.github.sagwatteok.drumpractice.core.scoring

import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.timing.PRACTICE_SAMPLE_RATE
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import kotlin.math.abs

private const val FRAMES_PER_MILLISECOND = PRACTICE_SAMPLE_RATE / 1_000
private const val WINDOW_FRAMES = 120L * FRAMES_PER_MILLISECOND
private const val PERFECT_FRAMES = 25L * FRAMES_PER_MILLISECOND
private const val DELIVERY_GRACE_FRAMES = 40L * FRAMES_PER_MILLISECOND

enum class HitSource(val label: String) { USB("USB"), MOCK("Mock") }
enum class Judgment(val label: String) {
    PERFECT("Perfect"), GREAT("Great"), GOOD("Good"), BAD("Bad"),
    MISS("Miss"), WRONG_DRUM("다른 드럼"), EXTRA("추가 타격"),
}
data class JudgedHit(val judgment: Judgment, val drum: Drum?, val offsetMs: Double? = null)
data class PracticeScore(val counts: Map<Judgment, Int> = emptyMap(), val last: JudgedHit? = null) {
    val points: Int get() = count(Judgment.PERFECT) * 100 + count(Judgment.GREAT) * 75 +
        count(Judgment.GOOD) * 50 + count(Judgment.BAD) * 25

    fun count(judgment: Judgment) = counts.getOrDefault(judgment, 0)
}

class PracticeScorer(private val plan: PracticePlan) {
    private data class Target(val step: Long, val drum: Drum)
    private class Ledger {
        var nextStep: Long? = null
        var acceptsFrom = 0L
        val matched = mutableSetOf<Target>()
        var score = PracticeScore()
    }

    private val ledgers = HitSource.entries.associateWith { Ledger() }

    fun scores(): Map<HitSource, PracticeScore> = ledgers.mapValues { it.value.score }

    fun arm(source: HitSource, frame: Long) {
        val ledger = ledgers.getValue(source)
        if (ledger.nextStep == null) {
            ledger.nextStep = plan.stepAtFrame(frame - WINDOW_FRAMES).coerceAtLeast(plan.firstPlayingStep)
            ledger.acceptsFrom = plan.frameAtStep(plan.firstPlayingStep) - WINDOW_FRAMES
        }
    }

    fun resume(source: HitSource, frame: Long) {
        val step = plan.stepAtFrame(frame)
        val first = if (plan.frameAtStep(step) < frame) step + 1 else step
        ledgers.getValue(source).apply {
            nextStep = maxOf(first, plan.firstPlayingStep)
            acceptsFrom = maxOf(frame, plan.frameAtStep(plan.firstPlayingStep) - WINDOW_FRAMES)
        }
    }

    fun disarm(source: HitSource) {
        ledgers.getValue(source).apply {
            nextStep = null
            matched.clear()
        }
    }

    fun hit(source: HitSource, drum: Drum?, frame: Long): JudgedHit? {
        if (frame < plan.frameAtStep(plan.firstPlayingStep) - WINDOW_FRAMES ||
            plan.endFrame?.let { frame >= it } == true) return null

        arm(source, frame)
        val ledger = ledgers.getValue(source)
        val nextStep = checkNotNull(ledger.nextStep)
        if (frame < ledger.acceptsFrom) return null
        val first = maxOf(plan.stepAtFrame(frame - WINDOW_FRAMES), nextStep)
        val last = plan.stepAtFrame(frame + WINDOW_FRAMES)
        val nearby = (first..last).filter { abs(frame - plan.frameAtStep(it)) <= WINDOW_FRAMES }
        val matchingTargets = if (drum == null) emptyList() else nearby
            .filter { drum in plan.expectedAt(it) }.map { Target(it, drum) }
        val eligible = matchingTargets.filterNot { it in ledger.matched }
        // At most one target can be Perfect. Otherwise, preserve later targets for later hits.
        val target = eligible.firstOrNull { abs(frame - plan.frameAtStep(it.step)) <= PERFECT_FRAMES }
            ?: eligible.firstOrNull()
        if (target != null) {
            val offset = frame - plan.frameAtStep(target.step)
            ledger.matched += target
            val magnitude = abs(offset)
            val judgment = when {
                magnitude <= PERFECT_FRAMES -> Judgment.PERFECT
                magnitude <= 50 * FRAMES_PER_MILLISECOND -> Judgment.GREAT
                magnitude <= 80 * FRAMES_PER_MILLISECOND -> Judgment.GOOD
                else -> Judgment.BAD
            }
            return record(ledger, JudgedHit(judgment, target.drum, offset.toDouble() / FRAMES_PER_MILLISECOND))
        }
        val hasUnplayed = nearby.any { step -> plan.expectedAt(step).any { Target(step, it) !in ledger.matched } }
        val judgment = if (matchingTargets.isEmpty() && hasUnplayed) Judgment.WRONG_DRUM else Judgment.EXTRA
        return record(ledger, JudgedHit(judgment, drum))
    }

    fun advance(playedFrame: Long, finished: Boolean = false) {
        val settledTime = if (finished) playedFrame else playedFrame - DELIVERY_GRACE_FRAMES
        for (ledger in ledgers.values) {
            var step = ledger.nextStep ?: continue
            while (plan.endStep?.let { step >= it } != true && plan.frameAtStep(step) + WINDOW_FRAMES < settledTime) {
                for (drum in plan.expectedAt(step)) {
                    if (!ledger.matched.remove(Target(step, drum))) record(ledger, JudgedHit(Judgment.MISS, drum))
                }
                step++
            }
            ledger.nextStep = step
        }
    }

    private fun record(ledger: Ledger, hit: JudgedHit): JudgedHit {
        val counts = ledger.score.counts + (hit.judgment to (ledger.score.count(hit.judgment) + 1))
        ledger.score = PracticeScore(counts, hit)
        return hit
    }
}
