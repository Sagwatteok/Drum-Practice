package io.github.sagwatteok.drumpractice.feature.diagnostics

import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.DrumHitEvent

private const val DISPLAY_GROUP_NANOS = 45_000_000L

data class HitSummary(
    val total: Long = 0,
    val counts: Map<Drum, Long> = emptyMap(),
    val latestHits: List<DrumHitEvent> = emptyList(),
) {
    fun add(hit: DrumHitEvent): HitSummary {
        val latestTime = maxOf(hit.receivedAtNanos, latestHits.maxOfOrNull { it.receivedAtNanos } ?: hit.receivedAtNanos)
        val currentGroup = (listOf(hit) + latestHits)
            .filter { latestTime - it.receivedAtNanos <= DISPLAY_GROUP_NANOS }
            .distinctBy { it.drumType to it.noteNumber }
        val drum = hit.drumType
        return copy(
            total = total + 1,
            counts = if (drum == null) counts else counts + (drum to (counts.getOrDefault(drum, 0) + 1)),
            latestHits = currentGroup,
        )
    }
}
