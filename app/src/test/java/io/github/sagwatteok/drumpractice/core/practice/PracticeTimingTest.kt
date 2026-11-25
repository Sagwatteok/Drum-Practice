package io.github.sagwatteok.drumpractice.core.practice

import io.github.sagwatteok.drumpractice.core.audio.PracticeClicks
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.core.scoring.Judgment
import io.github.sagwatteok.drumpractice.core.scoring.PracticeScorer
import io.github.sagwatteok.drumpractice.core.timing.AudioFrameAnchor
import io.github.sagwatteok.drumpractice.core.timing.ClockSource
import io.github.sagwatteok.drumpractice.core.timing.PracticePattern
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeTimingTest {
    private val chord = setOf(Drum.KICK, Drum.CLOSED_HI_HAT)
    private val bar = List<Set<Drum>>(8) { if (it % 2 == 0) chord else emptySet() }
    private val plan = PracticePlan(60, PracticePattern("test", listOf(bar, bar)))

    @Test fun twoLateAndOnTimeRepeatsDoNotCompeteForOneTarget() {
        val fast = PracticePlan(240, PracticePattern("test", listOf(List(8) { setOf(Drum.KICK) })))
        val scorer = PracticeScorer(fast)
        val first = fast.frameAtStep(8)
        val second = fast.frameAtStep(9)
        assertEquals(6_000L, second - first)
        assertEquals(Judgment.GOOD, scorer.hit(HitSource.USB, Drum.KICK, first + 3_840)?.judgment)
        assertEquals(Judgment.PERFECT, scorer.hit(HitSource.USB, Drum.KICK, second)?.judgment)
        scorer.advance(second + 8_000)
        val result = scorer.scores().getValue(HitSource.USB)
        assertEquals(150, result.points)
        assertEquals(0, result.count(Judgment.EXTRA))
        assertEquals(0, result.count(Judgment.MISS))
    }

    @Test fun usbDisconnectFreezesRollupAndReconnectStartsAtNextTarget() {
        val scorer = PracticeScorer(plan)
        scorer.resume(HitSource.USB, 0)
        val first = plan.frameAtStep(8)
        scorer.hit(HitSource.USB, Drum.KICK, first)
        scorer.hit(HitSource.USB, Drum.CLOSED_HI_HAT, first)
        scorer.advance(first + 8_000)
        val beforeDisconnect = scorer.scores().getValue(HitSource.USB)
        scorer.disarm(HitSource.USB)
        val later = plan.frameAtStep(16)
        scorer.advance(later + 8_000)
        assertEquals(beforeDisconnect, scorer.scores().getValue(HitSource.USB))

        scorer.resume(HitSource.USB, later + 10_000)
        scorer.advance(plan.frameAtStep(18) + 8_000)
        val resumed = scorer.scores().getValue(HitSource.USB)
        assertEquals(beforeDisconnect.points, resumed.points)
        assertEquals(2, resumed.count(Judgment.MISS))
    }

    @Test fun skippedFirstRepeatDoesNotConsumeAnEarlyPerfectForTheSecond() {
        val fast = PracticePlan(240, PracticePattern("test", listOf(List(8) { setOf(Drum.KICK) })))
        val scorer = PracticeScorer(fast)
        val second = fast.frameAtStep(9)
        scorer.resume(HitSource.USB, 0)
        val hit = scorer.hit(HitSource.USB, Drum.KICK, second - 480)
        assertEquals(Judgment.PERFECT, hit?.judgment)
        assertEquals(-10.0, hit?.offsetMs ?: 0.0, 0.01)
        scorer.advance(second + 8_000)
        val result = scorer.scores().getValue(HitSource.USB)
        assertEquals(100, result.points)
        assertEquals(1, result.count(Judgment.MISS))
        assertEquals(0, result.count(Judgment.EXTRA))
    }

    @Test fun aRealHitBetweenJudgmentWindowsCountsAsExtraAfterSettlement() {
        val scorer = PracticeScorer(plan)
        val first = plan.frameAtStep(8)
        scorer.resume(HitSource.USB, 0)
        assertEquals(Judgment.PERFECT, scorer.hit(HitSource.USB, Drum.KICK, first)?.judgment)
        scorer.advance(first + 8_400)
        assertEquals(Judgment.EXTRA, scorer.hit(HitSource.USB, Drum.KICK, first + 9_600)?.judgment)
        assertEquals(1, scorer.scores().getValue(HitSource.USB).count(Judgment.EXTRA))
        scorer.disarm(HitSource.USB)
        scorer.resume(HitSource.USB, first + 12_000)
        assertNull(scorer.hit(HitSource.USB, Drum.KICK, first + 9_600))
    }

    @Test fun stepsAndClicksUseOneFrameAxisWithoutRoundingDrift() {
        val oddTempo = plan.copy(bpm = 137)
        for (step in listOf(0L, 1L, 2L, 8L, 23L, 99_999L)) {
            val start = oddTempo.frameAtStep(step)
            assertEquals(step, oddTempo.stepAtFrame(start))
            assertEquals(step - 1, oddTempo.stepAtFrame(start - 1))
        }
        val samples = ShortArray(100)
        val renderer = PracticeClicks(plan)
        renderer.render(plan.frameAtStep(1), samples, samples.size)
        assertTrue(samples.all { it == 0.toShort() })
        renderer.render(plan.frameAtStep(2), samples, samples.size)
        assertTrue(samples.any { it != 0.toShort() })
        val regular = samples.maxOrNull()!!
        renderer.render(plan.frameAtStep(8), samples, samples.size)
        assertTrue(samples.maxOrNull()!! > regular)
    }

    @Test fun frameAnchorConvertsEventTimeAndClampsToWrittenAudio() {
        val anchor = AudioFrameAnchor(48_000, 2_000_000_000, 60_000, ClockSource.AUDIO_TIMESTAMP)
        assertEquals(47_999L, anchor.frameAt(anchor.nanoTimeAt(47_999)))
        assertEquals(48_001L, anchor.frameAt(anchor.nanoTimeAt(48_001)))
        assertEquals(52_800L, anchor.frameAt(2_100_000_000))
        assertEquals(60_000L, anchor.frameAt(3_000_000_000))
    }

    @Test fun chordsAndSourcesAreIndependentAndAHitCannotBeReused() {
        val score = PracticeScorer(plan)
        val first = plan.frameAtStep(8)
        assertNull(score.hit(HitSource.USB, Drum.KICK, first - 48_000))
        assertNull(score.hit(HitSource.USB, Drum.KICK, first - 1))
        assertEquals(Judgment.PERFECT, score.hit(HitSource.USB, Drum.KICK, first)?.judgment)
        assertEquals(Judgment.GREAT, score.hit(HitSource.USB, Drum.CLOSED_HI_HAT, first + 1_920)?.judgment)
        assertEquals(Judgment.EXTRA, score.hit(HitSource.USB, Drum.KICK, first)?.judgment)
        assertEquals(Judgment.WRONG_DRUM, score.hit(HitSource.MOCK, Drum.SNARE, first)?.judgment)
        assertEquals(Judgment.PERFECT, score.hit(HitSource.MOCK, Drum.KICK, first)?.judgment)
        score.advance(first + 8_000)
        val result = score.scores()
        assertEquals(175, result.getValue(HitSource.USB).points)
        assertEquals(100, result.getValue(HitSource.MOCK).points)
        assertEquals(0, result.getValue(HitSource.USB).count(Judgment.MISS))
        assertEquals(1, result.getValue(HitSource.MOCK).count(Judgment.MISS))
    }

    @Test fun finiteAndRepeatingPlaybackKeepSelectedFirstMeasure() {
        val selected = plan.copy(firstBar = 1)
        assertNull(selected.positionAtStep(0)?.bar)
        assertEquals(4, selected.positionAtStep(6)?.beat)
        assertEquals(1, selected.positionAtStep(8)?.bar)
        assertEquals(0, selected.positionAtStep(16)?.bar)
        assertEquals(1, selected.positionAtStep(16)?.beat)
        val slow = selected.copy(bpm = 50, bars = 1..1)
        assertEquals(230_400L, slow.frameAtStep(8))
        assertEquals(1, slow.positionAtStep(16)?.bar)
        assertEquals(1, slow.positionAtStep(16)?.beat)
        val once = slow.copy(repeat = false)
        assertEquals(460_800L, once.endFrame)
        assertTrue(once.expectedAt(16).isEmpty())
        assertNull(once.positionAtStep(16))
    }
}
