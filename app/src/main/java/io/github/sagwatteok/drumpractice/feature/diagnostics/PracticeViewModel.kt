package io.github.sagwatteok.drumpractice.feature.diagnostics

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sagwatteok.drumpractice.core.audio.AudioProbe
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.DrumHitEvent
import io.github.sagwatteok.drumpractice.core.midi.MidiInput
import io.github.sagwatteok.drumpractice.core.midi.MidiKind
import io.github.sagwatteok.drumpractice.core.midi.MidiMessage
import io.github.sagwatteok.drumpractice.core.midi.MidiPacketReceiver
import io.github.sagwatteok.drumpractice.core.midi.MidiParser
import io.github.sagwatteok.drumpractice.core.midi.MidiPort
import io.github.sagwatteok.drumpractice.core.midi.UsbMidiInput
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.feature.practice.PracticeTransport
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random

private val Application.mappingStore by preferencesDataStore("midi_mapping")
private const val UNMAPPED = -1
private const val PAD_HIGHLIGHT_MILLIS = 220L

enum class MockPattern(val label: String) {
    ON_BEAT("정박"), EARLY("이른 타격"), LATE("늦은 타격"), MISSED("누락"),
    WRONG("다른 드럼"), CHORD("동시 타격"), JITTER("임의 편차"),
}

data class MidiLog(
    val hex: String,
    val messages: List<MidiMessage>,
    val mock: Boolean,
    val usbAudioRouted: Boolean,
    val handledAtNanos: Long,
    val inputSession: Int? = null,
)

data class MidiLearnRequest(val drum: Drum, val inputSession: Int, val startedAtNanos: Long)

data class PracticeState(
    val mapping: Map<Drum, Int> = Drum.entries.mapNotNull { drum -> drum.defaultNote?.let { drum to it } }.toMap(),
    val mappingReady: Boolean = false,
    val learning: MidiLearnRequest? = null,
    val noteStatus: String = "",
    val logs: List<MidiLog> = emptyList(),
    val usbHits: HitSummary = HitSummary(),
    val mockHits: HitSummary = HitSummary(),
    val activePads: Set<Drum> = emptySet(),
    val simultaneousCount: Long = 0,
    val usbDevices: List<String> = emptyList(),
    val mockRunning: Boolean = false,
    val mockConnected: Boolean = true,
    val mockStatus: String = "",
) {
    val usbTimestampSamplesMs: List<Double>
        get() = logs.filterNot { it.mock }.flatMap { row ->
            row.messages.filter { it.sourceTimestampNanos == it.timestampNanos && it.sourceTimestampNanos > 0 }
                .map { (it.receivedAtNanos - it.timestampNanos) / 1_000_000.0 }
        }

    val usbProcessingSamplesMs: List<Double>
        get() = logs.filterNot { it.mock }.flatMap { row ->
            row.messages.map { (row.handledAtNanos - it.receivedAtNanos) / 1_000_000.0 }
        }

    fun spread(samples: List<Double>): Double {
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
    }
}

class PracticeViewModel internal constructor(
    private val app: Application,
    private val mappingStore: DataStore<Preferences>,
    createInput: (Context, MidiPacketReceiver) -> MidiInput = ::UsbMidiInput,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, app.mappingStore)
    private val state = MutableStateFlow(PracticeState())
    val practice = state.asStateFlow()
    val audio = AudioProbe(app)
    private val packets = Channel<MidiLog>(256, BufferOverflow.DROP_OLDEST)
    private val mockParser = MidiParser()
    private var mockJob: Job? = null
    private var highlightJob: Job? = null
    private val input = createInput(app) { bytes, messages, session ->
        packets.trySend(packet(bytes, messages, mock = false, usbAudioRouted = audio.snapshot.value.usbRouted)
            .copy(inputSession = session))
    }
    val connection = input.connection
    val rhythm = PracticeTransport(app, viewModelScope, { connection.value.activeSession }, ::mockNotes)
    val midiSupported = input.supported
    val hostSupported = app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    init {
        viewModelScope.launch {
            mappingStore.data.catch {
                if (it is IOException) state.update { current -> current.copy(noteStatus = "MIDI 매핑 읽기 실패") }
                else throw it
            }.collect { prefs ->
                val mapping = Drum.entries.mapNotNull { drum ->
                    (prefs[key(drum)] ?: drum.defaultNote)?.takeIf { it in 0..127 }?.let { drum to it }
                }.toMap()
                state.update { it.copy(mapping = mapping, mappingReady = true) }
            }
        }
        viewModelScope.launch { for (row in packets) process(row) }
        viewModelScope.launch {
            connection.collect { midi ->
                rhythm.usbConnectionChanged()
                refreshUsbDevices()
                if (state.value.learning?.let { it.inputSession != midi.activeSession } == true) {
                    cancelLearn("MIDI 연결이 바뀌어 Learn 대기가 취소되었습니다")
                }
            }
        }
        viewModelScope.launch { audio.snapshot.collect { refreshUsbDevices() } }
        refresh()
    }

    fun refresh() {
        input.rescan()
        audio.refresh()
        refreshUsbDevices()
    }

    private fun refreshUsbDevices() {
        val usb = app.getSystemService(UsbManager::class.java)
        state.update { current ->
            current.copy(usbDevices = usb?.deviceList.orEmpty().values.map {
                "%s · VID %04X / PID %04X".format(it.productName ?: "USB 장치", it.vendorId, it.productId)
            }.sorted())
        }
    }

    fun connect(port: MidiPort) {
        cancelLearn("MIDI 연결이 바뀌어 Learn 대기가 취소되었습니다")
        input.connect(port)
    }

    fun disconnect() {
        cancelLearn("MIDI 연결이 해제되어 Learn 대기가 취소되었습니다")
        input.disconnect()
    }

    fun learn(drum: Drum) {
        val midi = connection.value
        val session = midi.activeSession
        if (midi.active == null || session == null || !state.value.mappingReady) return
        val request = MidiLearnRequest(drum, session, System.nanoTime())
        state.update { it.copy(learning = request, noteStatus = "${drum.label} 타격 대기 중") }
    }

    fun cancelLearn(status: String = "") {
        state.update {
            if (it.learning == null) it else it.copy(learning = null, noteStatus = status)
        }
    }

    fun saveMapping(drum: Drum, note: Int?) {
        if (!state.value.mappingReady || (note != null && note !in 0..127)) return
        viewModelScope.launch {
            try {
                mappingStore.edit { prefs ->
                    if (note != null) {
                        Drum.entries.filter { it != drum && (prefs[key(it)] ?: it.defaultNote) == note }
                            .forEach { prefs[key(it)] = UNMAPPED }
                    }
                    prefs[key(drum)] = note ?: UNMAPPED
                }
                state.update { it.copy(noteStatus = "${drum.label} 매핑 저장됨") }
            } catch (_: IOException) {
                state.update { it.copy(noteStatus = "매핑 저장 실패 · 다시 시도하세요") }
            }
        }
    }

    fun mockHit(drum: Drum) = mockNotes(listOf(drum), System.nanoTime())

    fun startRhythm(preferredDeviceId: Int?) {
        audio.stop()
        stopMock()
        rhythm.start(preferredDeviceId)
    }

    fun playAudioProbe(preferredDeviceId: Int?) {
        rhythm.stop()
        audio.play(preferredDeviceId)
    }

    fun startMock(bpm: Int, pattern: MockPattern) {
        if (!state.value.mockConnected || !state.value.mappingReady) return
        stopMock()
        state.update { it.copy(mockRunning = true, mockStatus = "${pattern.label} · $bpm BPM") }
        mockJob = viewModelScope.launch {
            val period = 60_000_000_000L / bpm.coerceIn(40, 240)
            val start = System.nanoTime() + 100_000_000
            repeat(16) { index ->
                val offsetMs = when (pattern) {
                    MockPattern.EARLY -> -60
                    MockPattern.LATE -> 60
                    MockPattern.JITTER -> Random.nextInt(-75, 76)
                    else -> 0
                }
                val at = start + index * period + offsetMs * 1_000_000L
                delay(((at - System.nanoTime() + 999_999) / 1_000_000).coerceAtLeast(0))
                if (pattern != MockPattern.MISSED || index % 4 != 3) {
                    val drums = when (pattern) {
                        MockPattern.WRONG -> listOf(Drum.RIDE)
                        MockPattern.CHORD -> listOf(Drum.KICK, Drum.CLOSED_HI_HAT)
                        else -> listOf(if (index % 2 == 0) Drum.KICK else Drum.SNARE)
                    }
                    mockNotes(drums, at)
                }
            }
            state.update { it.copy(mockRunning = false, mockStatus = "Mock 패턴 완료") }
        }
    }

    fun stopMock(status: String = "Mock 입력 중지") {
        mockJob?.cancel()
        mockJob = null
        state.update { it.copy(mockRunning = false, mockStatus = status) }
    }

    fun setMockConnected(connected: Boolean) {
        stopMock(if (connected) "Mock 연결됨" else "Mock 연결 해제됨")
        state.update { it.copy(mockConnected = connected) }
    }

    private fun mockNotes(drums: List<Drum>, at: Long) {
        val current = state.value
        if (!current.mockConnected || !current.mappingReady) return
        val bytes = drums.mapNotNull(current.mapping::get)
            .flatMap { listOf(0x99.toByte(), it.toByte(), 100.toByte()) }.toByteArray()
        if (bytes.isEmpty()) return
        val messages = mutableListOf<MidiMessage>()
        mockParser.accept(bytes, at, System.nanoTime(), messages::add)
        process(packet(bytes, messages, mock = true, usbAudioRouted = false))
    }

    private fun process(row: MidiLog) {
        val handled = row.copy(handledAtNanos = System.nanoTime())
        val hits = mutableListOf<DrumHitEvent>()
        for (message in row.messages) {
            if (message.kind != MidiKind.NOTE_ON) continue
            val current = state.value
            val learned = current.learning?.takeIf {
                it.inputSession == row.inputSession && it.inputSession == connection.value.activeSession &&
                    message.receivedAtNanos >= it.startedAtNanos
            }?.drum
            if (learned != null) {
                cancelLearn()
                saveMapping(learned, message.number)
            }
            val drum = learned ?: current.mapping.entries.firstOrNull { it.value == message.number }?.key
            hits += DrumHitEvent(message.number, message.value, message.channel, message.timestampNanos,
                drum, drum?.articulation, message.receivedAtNanos)
        }
        state.update {
            val usbHits = if (row.mock) it.usbHits else hits.fold(it.usbHits, HitSummary::add)
            val mockHits = if (row.mock) hits.fold(it.mockHits, HitSummary::add) else it.mockHits
            val latest = if (row.mock) mockHits.latestHits else usbHits.latestHits
            it.copy(
                logs = (listOf(handled) + it.logs).take(100),
                usbHits = usbHits,
                mockHits = mockHits,
                activePads = if (hits.isEmpty()) it.activePads else latest.mapNotNull { hit -> hit.drumType }.toSet(),
                simultaneousCount = it.simultaneousCount + if (row.usbAudioRouted) hits.size else 0,
            )
        }
        if (hits.isNotEmpty()) {
            val source = if (row.mock) HitSource.MOCK else HitSource.USB
            hits.forEach { rhythm.hit(source, it, row.inputSession) }
            highlightJob?.cancel()
            highlightJob = viewModelScope.launch {
                delay(PAD_HIGHLIGHT_MILLIS)
                state.update { it.copy(activePads = emptySet()) }
            }
        }
    }

    fun clearSession() {
        highlightJob?.cancel()
        state.update {
            it.copy(logs = emptyList(), usbHits = HitSummary(), mockHits = HitSummary(), activePads = emptySet(),
                simultaneousCount = 0)
        }
    }

    override fun onCleared() {
        input.close()
        packets.close()
        audio.close()
    }

    private fun key(drum: Drum) = intPreferencesKey(drum.name)

    private fun packet(bytes: ByteArray, messages: List<MidiMessage>, mock: Boolean, usbAudioRouted: Boolean) =
        MidiLog(bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }, messages, mock, usbAudioRouted, 0)
}
