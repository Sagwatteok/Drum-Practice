package io.github.sagwatteok.drumpractice.feature.practice

import android.content.Context
import io.github.sagwatteok.drumpractice.core.audio.MetronomePlayer
import io.github.sagwatteok.drumpractice.core.audio.MetronomeReading
import io.github.sagwatteok.drumpractice.core.midi.Drum
import io.github.sagwatteok.drumpractice.core.midi.DrumHitEvent
import io.github.sagwatteok.drumpractice.core.scoring.HitSource
import io.github.sagwatteok.drumpractice.core.scoring.PracticeScore
import io.github.sagwatteok.drumpractice.core.scoring.PracticeScorer
import io.github.sagwatteok.drumpractice.core.timing.AudioFrameAnchor
import io.github.sagwatteok.drumpractice.core.timing.ClockSource
import io.github.sagwatteok.drumpractice.core.timing.PracticePattern
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import io.github.sagwatteok.drumpractice.core.timing.PracticePosition
import io.github.sagwatteok.drumpractice.core.timing.STEPS_PER_BAR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val quarterKick = PracticePattern("킥 4분음표", listOf(List(STEPS_PER_BAR) { step ->
    if (step % 2 == 0) setOf(Drum.KICK) else emptySet()
}))

data class TransportState(
    val plan: PracticePlan = PracticePlan(60, quarterKick),
    val running: Boolean = false,
    val position: PracticePosition? = null,
    val clockSource: ClockSource? = null,
    val output: String? = null,
    val autoMock: Boolean = false,
    val scores: Map<HitSource, PracticeScore> = emptyMap(),
    val message: String = "",
)

class PracticeTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val usbSession: () -> Int?,
    private val sendMock: (List<Drum>, Long) -> Unit,
) {
    private class Run(val id: Long, val plan: PracticePlan) {
        val scorer = PracticeScorer(plan)
        var anchor: AudioFrameAnchor? = null
        var lastPresentedFrame = 0L
        var nextMockStep = plan.firstPlayingStep
        var usbSession: Int? = null
    }

    private data class Reading(val id: Long, val sample: MetronomeReading)

    private val player = MetronomePlayer(context)
    private val state = MutableStateFlow(TransportState())
    val snapshot = state.asStateFlow()
    private val readings = MutableStateFlow<Reading?>(null)
    private var active: Run? = null
    private var playback: Job? = null
    private var runId = 0L
    private var preferredDeviceId: Int? = null

    init {
        scope.launch {
            readings.collect { reading ->
                val run = active
                if (reading != null && run?.id == reading.id) update(run, reading.sample)
            }
        }
    }

    fun start(preferredDeviceId: Int?) {
        this.preferredDeviceId = preferredDeviceId
        playback?.cancel()
        val run = Run(++runId, state.value.plan)
        active = run
        state.update {
            it.copy(running = true, position = null, clockSource = null, output = null,
                scores = emptyMap(), message = "오디오 재생 준비 중")
        }
        playback = scope.launch {
            try {
                player.play(run.plan, preferredDeviceId) { sample ->
                    readings.update { if (it == null || it.id <= run.id) Reading(run.id, sample) else it }
                }
                if (active === run) {
                    readings.value?.takeIf { it.id == run.id }?.let { update(run, it.sample) }
                    run.plan.endFrame?.let { run.scorer.advance(it, finished = true) }
                    finish(run, "연습 완료")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (active === run) finish(run, "재생 오류 · ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        val run = active ?: return
        playback?.cancel()
        finish(run, "정지됨")
    }

    fun setBpm(bpm: Int) {
        if (bpm in 40..240) setPlan(state.value.plan.copy(bpm = bpm))
    }

    fun setRepeat(enabled: Boolean) = setPlan(state.value.plan.copy(repeat = enabled))

    fun setAutoMock(enabled: Boolean) {
        if (active == null) state.update { it.copy(autoMock = enabled) }
    }

    fun setPlan(plan: PracticePlan) {
        if (plan == state.value.plan) return
        state.update { it.copy(plan = plan, position = null) }
        if (active != null) start(preferredDeviceId)
    }

    fun usbConnectionChanged() {
        val run = active ?: return
        val nowFrame = run.anchor?.frameAt(System.nanoTime()) ?: 0L
        syncUsb(run, nowFrame)
    }

    fun hit(source: HitSource, event: DrumHitEvent, inputSession: Int?) {
        val run = active ?: return
        val anchor = run.anchor ?: return
        if (source == HitSource.USB) {
            if (inputSession == null || inputSession != usbSession()) return
            syncUsb(run, anchor.frameAt(event.timestampNanos))
        }
        val hit = run.scorer.hit(source, event.drumType, anchor.frameAt(event.timestampNanos))
        if (hit != null) state.update { it.copy(scores = run.scorer.scores()) }
    }

    private fun update(run: Run, reading: MetronomeReading) {
        val now = System.nanoTime()
        val played = reading.anchor.frameAt(now).coerceAtMost(reading.playedFrame)
            .coerceAtLeast(run.lastPresentedFrame)
        run.anchor = reading.anchor
        run.lastPresentedFrame = played
        val step = run.plan.stepAtFrame(played)
        syncUsb(run, played)
        if (state.value.autoMock) {
            run.scorer.arm(HitSource.MOCK, played)
            while (run.nextMockStep <= step) {
                val targetStep = run.nextMockStep++
                val drums = run.plan.expectedAt(targetStep)
                if (drums.isNotEmpty()) {
                    val at = reading.anchor.nanoTimeAt(run.plan.frameAtStep(targetStep))
                    sendMock(drums.toList(), at.coerceAtMost(now))
                }
            }
        }
        run.scorer.advance(played)
        state.update {
            it.copy(position = run.plan.positionAtStep(step), clockSource = reading.anchor.source,
                output = reading.routeName, scores = run.scorer.scores(), message = "")
        }
    }

    private fun finish(run: Run, message: String) {
        active = null
        state.update { it.copy(running = false, position = null, scores = run.scorer.scores(), message = message) }
    }

    private fun syncUsb(run: Run, frame: Long) {
        val session = usbSession()
        if (session == run.usbSession) return
        run.scorer.disarm(HitSource.USB)
        run.usbSession = session
        if (session != null) run.scorer.resume(HitSource.USB, frame)
    }
}
