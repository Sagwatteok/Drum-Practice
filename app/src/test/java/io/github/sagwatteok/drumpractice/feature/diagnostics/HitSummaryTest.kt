package io.github.sagwatteok.drumpractice.feature.diagnostics

import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.DrumHitEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HitSummaryTest {
    @Test fun nearbyPadsRemainVisibleAndLaterGroupReplacesThem() {
        val chord = HitSummary()
            .add(hit(Drum.KICK, 100, 50))
            .add(hit(Drum.CLOSED_HI_HAT, 112, 80))

        assertEquals(listOf(Drum.CLOSED_HI_HAT, Drum.KICK), chord.latestHits.map { it.drumType })
        assertEquals(listOf(80, 50), chord.latestHits.map { it.velocity })
        assertEquals(2L, chord.total)

        val next = chord.add(hit(Drum.SNARE, 160, 100))
        assertEquals(listOf(Drum.SNARE), next.latestHits.map { it.drumType })
        assertEquals(mapOf(Drum.KICK to 1L, Drum.CLOSED_HI_HAT to 1L, Drum.SNARE to 1L), next.counts)
    }

    @Test fun repeatedPadCountsEveryHitAndShowsItsLatestVelocity() {
        val result = HitSummary().add(hit(Drum.KICK, 100, 50)).add(hit(Drum.KICK, 120, 100))
        assertEquals(2L, result.counts[Drum.KICK])
        assertEquals(listOf(100), result.latestHits.map { it.velocity })
    }

    private fun hit(drum: Drum, millis: Long, velocity: Int) = DrumHitEvent(
        checkNotNull(drum.defaultNote), velocity, 10, millis * 1_000_000, drum,
        drum.articulation, millis * 1_000_000,
    )
}
