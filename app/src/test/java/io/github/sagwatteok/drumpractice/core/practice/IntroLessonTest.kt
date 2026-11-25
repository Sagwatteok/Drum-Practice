package io.github.sagwatteok.drumpractice.core.practice

import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.core.scoring.Judgment
import io.github.sagwatteok.drumpractice.core.scoring.PracticeScorer
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import io.github.sagwatteok.drumpractice.feature.practice.introLesson
import org.junit.Assert.assertEquals
import org.junit.Test

class IntroLessonTest {
    private val plan = PracticePlan(60, introLesson)
    private val kickChord = setOf(Drum.KICK, Drum.CLOSED_HI_HAT)
    private val snareChord = setOf(Drum.SNARE, Drum.CLOSED_HI_HAT)

    @Test fun everyEighthHasClosedHiHatWithKickAndSnareOnTheirBeats() {
        assertEquals("닫힌 하이햇", Drum.CLOSED_HI_HAT.label)
        val expected = listOf(
            kickChord, setOf(Drum.CLOSED_HI_HAT), snareChord, setOf(Drum.CLOSED_HI_HAT),
            kickChord, setOf(Drum.CLOSED_HI_HAT), snareChord, setOf(Drum.CLOSED_HI_HAT),
        )
        assertEquals(listOf(expected, expected), introLesson.bars)
        assertEquals(192_000L, plan.frameAtStep(8))
    }

    @Test fun bothChordsScoreEveryInstrumentWithoutSharingSources() {
        val scorer = PracticeScorer(plan)
        val first = plan.frameAtStep(8)
        val secondBeat = plan.frameAtStep(10)
        for (drum in kickChord) {
            assertEquals(Judgment.PERFECT, scorer.hit(HitSource.USB, drum, first)?.judgment)
        }
        for (drum in snareChord) {
            assertEquals(Judgment.PERFECT, scorer.hit(HitSource.MOCK, drum, secondBeat)?.judgment)
        }
        assertEquals(200, scorer.scores().getValue(HitSource.USB).points)
        assertEquals(200, scorer.scores().getValue(HitSource.MOCK).points)
        assertEquals(Drum.CLOSED_HI_HAT, scorer.scores().getValue(HitSource.USB).last?.drum)
        assertEquals(Drum.CLOSED_HI_HAT, scorer.scores().getValue(HitSource.MOCK).last?.drum)
    }
}
