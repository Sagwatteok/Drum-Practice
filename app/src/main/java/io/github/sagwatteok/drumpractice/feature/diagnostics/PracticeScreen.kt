package io.github.sagwatteok.drumpractice.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.MidiKind

private val primaryPads = listOf(Drum.KICK, Drum.SNARE, Drum.CLOSED_HI_HAT)
private val otherPads = Drum.entries - primaryPads.toSet()

@Composable
fun PracticeScreen(model: PracticeViewModel) {
    val state by model.practice.collectAsStateWithLifecycle()
    val midi by model.connection.collectAsStateWithLifecycle()
    val audio by model.audio.snapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val listState = key(tab) { rememberLazyListState() }
    var preferredOutput by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by rememberSaveable { mutableStateOf<Drum?>(null) }
    var bpmText by rememberSaveable { mutableStateOf("120") }
    var pattern by rememberSaveable { mutableStateOf(MockPattern.ON_BEAT) }

    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Text("Drum Practice", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineMedium)
            Text(midi.active?.name?.let { "MIDI 연결 · $it" } ?: midi.status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("연결 진단", "자유 연주", "매핑").forEachIndexed { index, title ->
                    FilterChip(tab == index, { tab = index }, label = { Text(title) })
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
                when (tab) {
                    0 -> {
                        item {
                            Panel("USB MIDI") {
                                Text("USB 호스트 ${support(model.hostSupported)} · Android MIDI ${support(model.midiSupported)}")
                                Text(state.usbDevices.joinToString("\n").ifBlank { "USB 장치 없음" })
                                midi.ports.forEach { port ->
                                    OutlinedButton({ model.connect(port) }, enabled = port != midi.active) { Text(port.name) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(model::refresh) { Text("다시 검색") }
                                    if (midi.hasSelection) {
                                        OutlinedButton(model::disconnect) { Text("연결 해제") }
                                    }
                                }
                            }
                        }
                        item {
                            Panel("오디오 출력") {
                                Text("사용 가능한 출력")
                                Text(audio.outputs.joinToString("\n").ifBlank { "출력 장치 없음" })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(preferredOutput == null, { preferredOutput = null }, label = { Text("시스템 기본") })
                                    audio.usbOutputs.forEach {
                                        FilterChip(preferredOutput == it.id, { preferredOutput = it.id },
                                            label = { Text(it.label) })
                                    }
                                }
                                Text(audio.status)
                                Text("실제 출력 · ${audio.actualRoute ?: "재생 중에 확인"}")
                                Text("USB 오디오 출력 중 감지한 실제 Note On · ${state.simultaneousCount}회")
                                if (audio.playing) {
                                    OutlinedButton({ model.audio.stop() }) { Text("테스트음 중지") }
                                } else {
                                    Button({ model.audio.play(preferredOutput) }) { Text("12초 테스트음 재생") }
                                }
                                Text("헤드폰은 모듈 PHONES/OUTPUT에 꽂고 테스트음을 들으며 드럼을 치세요.")
                                Text("USB 오디오를 사용할 수 없으면 MIDI는 USB에 두고 별도 아날로그 출력을 AUX IN에 연결하세요.")
                            }
                        }
                        item {
                            Panel("MIDI 진단 로그") {
                                Text("MIDI 시각 → 앱 수신 · ${describe(state.usbTimestampSamplesMs, state)}")
                                Text("앱 수신 → 처리 · ${describe(state.usbProcessingSamplesMs, state)}")
                                Text("최근 100개 패킷 기준. 로그의 시각은 단조 증가 시간(ns)입니다.")
                                OutlinedButton(model::clearSession) { Text("세션 초기화") }
                            }
                        }
                        items(state.logs) { row ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${if (row.mock) "Mock" else "USB"} · ${row.hex}", fontFamily = FontFamily.Monospace)
                                    row.messages.forEach { message ->
                                        val number = if (message.kind == MidiKind.CONTROL_CHANGE) "CC" else "Note"
                                        Text("${message.kind} · Ch ${message.channel} · $number ${message.number} · 값 ${message.value}")
                                        Text("원본 ${message.sourceTimestampNanos} · 사용 ${message.timestampNanos}\n수신 ${message.receivedAtNanos} · 처리 ${row.handledAtNanos}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    1 -> {
                        item {
                            Panel("드럼 세트") {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    primaryPads.forEach { drum ->
                                        DrumPad(drum, state, model::mockHit, Modifier.weight(1f))
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    otherPads.forEach { drum -> DrumPad(drum, state, model::mockHit) }
                                }
                                Text("패드를 누르면 Mock 타격을 보냅니다.")
                            }
                        }
                        item {
                            Panel("타격 요약") {
                                HitDisplay("USB", state.usbHits)
                                HorizontalDivider()
                                HitDisplay("Mock", state.mockHits)
                                DrumHitCounts(state.usbHits, state.mockHits)
                                OutlinedButton(model::clearSession) { Text("세션 초기화") }
                            }
                        }
                        item {
                            Panel("Mock 패턴") {
                                OutlinedTextField(bpmText, { bpmText = it.filter(Char::isDigit).take(3) },
                                    label = { Text("BPM (40–240)") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MockPattern.entries.forEach {
                                        FilterChip(pattern == it, { pattern = it }, label = { Text(it.label) })
                                    }
                                }
                                val bpm = bpmText.toIntOrNull()?.takeIf { it in 40..240 }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        { bpm?.let { model.startMock(it, pattern) } },
                                        enabled = bpm != null && state.mappingReady && state.mockConnected && !state.mockRunning,
                                    ) { Text("패턴 시작") }
                                    if (state.mockRunning) {
                                        OutlinedButton({ model.stopMock() }) { Text("중지") }
                                    }
                                    OutlinedButton({ model.setMockConnected(!state.mockConnected) }) {
                                        Text(if (state.mockConnected) "Mock 연결 해제" else "Mock 다시 연결")
                                    }
                                }
                                Text(state.mockStatus)
                            }
                        }
                    }

                    2 -> {
                        item {
                            Panel("MIDI Learn") {
                                Text("기본값은 General MIDI 예시입니다.")
                                if (midi.active == null) {
                                    Text("MIDI Learn을 사용하려면 USB MIDI 포트를 먼저 연결하세요. Note 번호는 직접 수정할 수 있습니다.")
                                    OutlinedButton({ tab = 0; model.refresh() }) { Text("연결 진단 열기") }
                                } else {
                                    Text("Learn을 누른 뒤 모듈 패드를 한 번 치세요.")
                                }
                                Text("한 Note 번호는 한 드럼에 배정됩니다.")
                                if (state.noteStatus.isNotBlank()) Text(state.noteStatus)
                                if (state.learning != null) {
                                    OutlinedButton({ model.cancelLearn() }) { Text("Learn 취소") }
                                }
                            }
                        }
                        items(Drum.entries) { drum ->
                            Panel(drum.label) {
                                Text(state.mapping[drum]?.let { "Note $it" } ?: "미지정")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ model.learn(drum) }, enabled = state.mappingReady && midi.active != null) { Text("Learn") }
                                    OutlinedButton({ editing = drum }, enabled = state.mappingReady) { Text("수정") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { drum ->
        var value by rememberSaveable(drum) { mutableStateOf(state.mapping[drum]?.toString().orEmpty()) }
        val note = value.toIntOrNull()?.takeIf { it in 0..127 }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${drum.label} MIDI Note") },
            text = {
                OutlinedTextField(value, { value = it.filter(Char::isDigit).take(3) },
                    label = { Text("0–127 · 비우면 매핑 해제") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton({ model.saveMapping(drum, note); editing = null }, enabled = value.isEmpty() || note != null) { Text("저장") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("취소") } },
        )
    }
}

private fun support(available: Boolean) = if (available) "지원" else "미지원"

@Composable
private fun DrumPad(drum: Drum, state: PracticeState, onHit: (Drum) -> Unit, modifier: Modifier = Modifier) {
    val mapped = drum in state.mapping
    val padModifier = if (state.mappingReady && !mapped) {
        modifier.semantics { stateDescription = "미지정" }
    } else modifier
    FilterChip(
        selected = drum in state.activePads,
        onClick = { onHit(drum) },
        enabled = state.mockConnected && state.mappingReady && mapped,
        modifier = padModifier,
        label = { Text(if (drum == Drum.CLOSED_HI_HAT) "닫힌 하이햇" else drum.label) },
    )
}

@Composable
private fun DrumHitCounts(usb: HitSummary, mock: HitSummary) {
    if (usb.counts.isEmpty() && mock.counts.isEmpty()) return
    HorizontalDivider()
    Text("패드별 횟수", style = MaterialTheme.typography.titleMedium)
    Drum.entries.forEach { drum ->
        val usbCount = usb.counts.getOrDefault(drum, 0)
        val mockCount = mock.counts.getOrDefault(drum, 0)
        if (usbCount != 0L || mockCount != 0L) {
            Text("${drum.label} · USB $usbCount / Mock $mockCount")
        }
    }
}

@Composable
private fun HitDisplay(source: String, summary: HitSummary) {
    Text("$source · ${summary.total}회", style = MaterialTheme.typography.titleMedium)
    if (summary.latestHits.isEmpty()) Text("타격 대기 중")
    summary.latestHits.forEach { hit ->
        Text(hit.drumType?.label ?: "매핑되지 않은 드럼", style = MaterialTheme.typography.headlineSmall)
        Text("Note ${hit.noteNumber} · Velocity ${hit.velocity}")
        LinearProgressIndicator(progress = { hit.velocity / 127f }, modifier = Modifier.fillMaxWidth())
    }
}

private fun describe(samples: List<Double>, state: PracticeState) =
    if (samples.isEmpty()) "측정값 없음"
    else "평균 %.2f ms · 표준편차 %.2f ms (n=%d)".format(samples.average(), state.spread(samples), samples.size)

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
