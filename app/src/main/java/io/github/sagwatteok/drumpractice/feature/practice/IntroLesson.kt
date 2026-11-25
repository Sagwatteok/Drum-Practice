package io.github.sagwatteok.drumpractice.feature.practice

import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.timing.PracticePattern
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BAR

private val basicBeat = List(STEPS_PER_BAR) { step ->
    when (step) {
        0, 4 -> setOf(Drum.KICK, Drum.CLOSED_HI_HAT)
        2, 6 -> setOf(Drum.SNARE, Drum.CLOSED_HI_HAT)
        else -> setOf(Drum.CLOSED_HI_HAT)
    }
}

val introLesson = PracticePattern("입문 레슨 · 기본 8비트", listOf(basicBeat, basicBeat))
val introPads = listOf(Drum.KICK, Drum.SNARE, Drum.CLOSED_HI_HAT)

fun barRangeLabel(bars: IntRange): String =
    if (bars.first == bars.last) "${bars.first + 1}마디" else "${bars.first + 1}–${bars.last + 1}마디"
