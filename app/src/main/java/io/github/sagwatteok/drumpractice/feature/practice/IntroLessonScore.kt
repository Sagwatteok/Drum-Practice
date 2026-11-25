package io.github.sagwatteok.drumpractice.feature.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BAR
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BEAT

@Composable
fun IntroLessonGuide() {
    Text("4/4 · 닫힌 하이햇 8분음표 · 킥 1·3박 · 스네어 2·4박")
}

@Composable
fun IntroLessonScore(state: TransportState, showContext: Boolean) {
    if (showContext) {
        IntroLessonGuide()
        Text(repeatDescription(state), style = MaterialTheme.typography.bodyMedium)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bars = state.plan.pattern.bars.indices
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                bars.forEach { bar -> LessonMeasure(state, bar, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bars.forEach { bar -> LessonMeasure(state, bar) }
            }
        }
    }
}

@Composable
private fun LessonMeasure(state: TransportState, index: Int, modifier: Modifier = Modifier) {
    val activeStep = state.position?.takeIf { it.bar == index }?.stepInBar
    val activeBeat = activeStep?.div(STEPS_PER_BEAT)
    val looped = state.plan.repeat && index in state.plan.bars
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val line = colors.outline
    val beatBackground = colors.primaryContainer
    val cursor = colors.primary
    val notes = state.plan.pattern.bars[index]
    val outline = when {
        activeStep != null -> cursor
        looped -> colors.secondary
        else -> colors.outlineVariant
    }
    val barLabel = "${index + 1}마디"
    val description = notes.mapIndexed { step, drums ->
        val beat = step / STEPS_PER_BEAT + 1
        val subdivision = if (step % STEPS_PER_BEAT == 0) "${beat}박" else "${beat}박의 앤드"
        "$subdivision ${drums.joinToString("과 ") { it.label }}"
    }.joinToString(", ")

    Column(
        modifier.fillMaxWidth().border(1.dp, outline, RoundedCornerShape(10.dp)).padding(8.dp),
    ) {
        Text(barLabel, style = MaterialTheme.typography.labelLarge)
        Canvas(
            Modifier.fillMaxWidth().height(82.dp).semantics {
                contentDescription = "$barLabel 악보. $description"
            },
        ) {
            val cell = size.width / STEPS_PER_BAR
            val top = size.height * 0.35f
            val gap = size.height * 0.11f
            val bottom = top + gap * 4
            val hihatY = top - gap * 0.55f
            val snareY = top + gap * 1.5f
            val kickY = top + gap * 3.5f
            val beamY = size.height * 0.07f
            val stem = 1.4.dp.toPx()
            val cross = minOf(3.8.dp.toPx(), cell * 0.21f)
            val headWidth = minOf(10.dp.toPx(), cell * 0.48f)
            val headHeight = minOf(6.dp.toPx(), gap * 0.7f)
            fun x(step: Int) = cell * (step + 0.5f)

            if (activeBeat != null) {
                drawRect(beatBackground, Offset(cell * activeBeat * STEPS_PER_BEAT, 0f),
                    Size(cell * STEPS_PER_BEAT, size.height))
            }
            if (activeStep != null) {
                val atX = cell * activeStep + 1.dp.toPx()
                drawLine(cursor, Offset(atX, 0f), Offset(atX, size.height), strokeWidth = 2.dp.toPx())
            }
            repeat(5) { staffLine ->
                val y = top + gap * staffLine
                drawLine(line, Offset.Zero.copy(y = y), Offset(size.width, y), 0.75.dp.toPx())
            }
            drawLine(ink, Offset(0f, top), Offset(0f, bottom), stem)
            drawLine(ink, Offset(size.width, top), Offset(size.width, bottom), stem)
            notes.forEachIndexed { step, drums ->
                val noteX = x(step)
                if (Drum.CLOSED_HI_HAT in drums) {
                    drawLine(ink, Offset(noteX - cross, hihatY - cross), Offset(noteX + cross, hihatY + cross), stem)
                    drawLine(ink, Offset(noteX - cross, hihatY + cross), Offset(noteX + cross, hihatY - cross), stem)
                    drawLine(ink, Offset(noteX + cross, hihatY), Offset(noteX + cross, beamY), stem)
                    if (step % STEPS_PER_BEAT == 0 && Drum.CLOSED_HI_HAT in notes[step + 1]) {
                        drawLine(ink, Offset(noteX + cross, beamY), Offset(x(step + 1) + cross, beamY), 3.dp.toPx())
                    }
                }
                val drumY = when {
                    Drum.SNARE in drums -> snareY
                    Drum.KICK in drums -> kickY
                    else -> null
                }
                if (drumY != null) {
                    drawOval(ink, Offset(noteX - headWidth / 2, drumY - headHeight / 2),
                        Size(headWidth, headHeight))
                    drawLine(ink, Offset(noteX - headWidth / 2, drumY),
                        Offset(noteX - headWidth / 2, drumY + gap * 1.45f), stem)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            repeat(STEPS_PER_BAR) { step ->
                Text(
                    if (step % STEPS_PER_BEAT == 0) (step / STEPS_PER_BEAT + 1).toString() else "&",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = if (activeBeat == step / STEPS_PER_BEAT) cursor else ink,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
