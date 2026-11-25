package io.github.sagwatteok.drumpractice.feature.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.core.scoring.Judgment
import io.github.sagwatteok.drumpractice.core.scoring.PracticeScore

@Composable
fun MetronomeControls(state: TransportState, transport: PracticeTransport, start: () -> Unit) {
    var bpmText by rememberSaveable(state.plan.bpm) { mutableStateOf(state.plan.bpm.toString()) }
    val newBpm = bpmText.toIntOrNull()?.takeIf { it in 40..240 }
    Text("템포 · ${state.plan.bpm} BPM", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = bpmText,
            onValueChange = { bpmText = it.filter(Char::isDigit).take(3) },
            label = { Text("BPM (40–240)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(0.48f),
        )
        OutlinedButton(
            onClick = { newBpm?.let(transport::setBpm) },
            enabled = newBpm != null && newBpm != state.plan.bpm,
        ) { Text("BPM 적용") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.plan.repeat, onClick = { transport.setRepeat(!state.plan.repeat) },
            label = { Text("반복") })
        FilterChip(selected = state.autoMock, onClick = { transport.setAutoMock(!state.autoMock) },
            enabled = !state.running, label = { Text("Mock 자동 타격") })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(start, enabled = !state.running) { Text("재생") }
        OutlinedButton(transport::stop, enabled = state.running) { Text("정지") }
    }
    val position = state.position
    if (position != null) {
        Text(if (position.bar == null) "카운트인 · ${position.beat}/4박"
            else "${position.bar + 1}마디 · ${position.beat}/4박", style = MaterialTheme.typography.headlineSmall)
    }
    if (state.message.isNotBlank()) Text(state.message)
    state.clockSource?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
    state.output?.let { Text("오디오 출력 · $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun PracticeMockPads(drums: List<Drum>, enabled: Boolean, onHit: (Drum) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        drums.forEach { drum ->
            OutlinedButton({ onHit(drum) }, enabled = enabled) { Text(drum.label) }
        }
    }
}

@Composable
fun PracticeScores(scores: Map<HitSource, PracticeScore>) {
    for (source in HitSource.entries) {
        val score = scores[source] ?: PracticeScore()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${source.label} 점수 · ${score.points}", style = MaterialTheme.typography.titleMedium)
            Text(listOf(Judgment.PERFECT, Judgment.GREAT, Judgment.GOOD, Judgment.BAD, Judgment.MISS)
                .joinToString(" · ") { "${it.label} ${score.count(it)}" })
            if (score.count(Judgment.WRONG_DRUM) > 0 || score.count(Judgment.EXTRA) > 0) {
                Text("다른 드럼 ${score.count(Judgment.WRONG_DRUM)} · 추가 타격 ${score.count(Judgment.EXTRA)}")
            }
            score.last?.let { last ->
                val parts = listOfNotNull(
                    last.drum?.label,
                    last.judgment.label,
                    last.offsetMs?.let { offset ->
                        val direction = if (offset < 0) "Early" else if (offset > 0) "Late" else "정박"
                        if (offset == 0.0) direction else "$direction %.1f ms".format(kotlin.math.abs(offset))
                    },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
