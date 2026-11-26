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
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import io.github.sagwatteok.drumpractice.core.timing.PracticePosition
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransportState(
    val plan: PracticePlan = PracticePlan(60, introLesson),
    val running: Boolean = false,
    val position: PracticePosition? = null,
    val clockSource: ClockSource? = null,
    val output: String? = null,
    val autoMock: Boolean = false,
    val scores: Map<HitSource, PracticeScore> = emptyMap(),
    val message: String = "",
    val inputLoss: InputLoss = InputLoss(),
)

class PracticeTransport internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val events: PracticeEventQueue,
    private val observeUsb: () -> UsbObservation,
    private val sendMock: (List<Drum>, Long) -> Unit,
) {
    private class Run(val id: Long, val plan: PracticePlan) {
        val scorer = PracticeScorer(plan)
        val loss = AttemptInputLoss()
        var anchor: AudioFrameAnchor? = null
        var lastPresentedFrame = 0L
        var nextMockStep = plan.firstPlayingStep
        var usbSession: Int? = null
    }

    internal data class Reading(val id: Long, val sample: MetronomeReading, val observedAt: Long)

    private val player = MetronomePlayer(context)
    private val state = MutableStateFlow(TransportState())
    val snapshot = state.asStateFlow()
    private val lastReading = AtomicReference<Reading?>(null)
    private val intake = AtomicReference<AttemptInputLoss?>(null)
    private var displayedLoss: AttemptInputLoss? = null
    private var active: Run? = null
    private var playback: Job? = null
    private var runId = 0L
    private var preferredDeviceId: Int? = null
    private var usb = UsbObservation(null, Long.MIN_VALUE)

    internal fun capturedReading(): Reading? = lastReading.get()
    internal fun currentIntake(): AttemptInputLoss? = intake.get()

    internal fun recordLoss(attempt: AttemptInputLoss, noteOns: Int) {
        attempt.record(noteOns)
        if (attempt.publishPending.compareAndSet(false, true)) {
            events.post {
                attempt.publishPending.set(false)
                if (displayedLoss === attempt) state.update { it.copy(inputLoss = attempt.snapshot()) }
            }
        }
    }

    fun start(preferredDeviceId: Int?) {
        events.post { startOnWorker(preferredDeviceId) }
    }

    private fun startOnWorker(preferredDeviceId: Int?) {
        this.preferredDeviceId = preferredDeviceId
        val previous = playback
        previous?.cancel()
        val run = Run(++runId, state.value.plan)
        active = run
        displayedLoss = run.loss
        intake.set(run.loss)
        observeConnection(observeUsb())
        state.update {
            it.copy(running = true, position = null, clockSource = null, output = null,
                scores = emptyMap(), message = "오디오 재생 준비 중", inputLoss = InputLoss())
        }
        playback = scope.launch(Dispatchers.Default) {
            try {
                previous?.join()
                player.play(run.plan, preferredDeviceId) { sample ->
                    val reading = Reading(run.id, sample, System.nanoTime())
                    lastReading.getAndUpdate { if (it == null || it.id <= run.id) reading else it }
                    val observation = observeUsb()
                    events.offerProgress {
                        if (active === run) {
                            observeConnection(observation)
                            update(run, reading)
                        }
                    }
                }
                events.post {
                    if (active === run) {
                        capturedReading()?.takeIf { it.id == run.id }?.let { update(run, it) }
                        run.plan.endFrame?.let { run.scorer.advance(it, finished = true) }
                        finish(run, "연습 완료")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                events.post {
                    if (active === run) finish(run, "재생 오류 · ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun stop() {
        events.post {
            val run = active ?: return@post
            playback?.cancel()
            finish(run, "정지됨")
        }
    }

    fun setBpm(bpm: Int) {
        if (bpm in 40..240) events.post { applyPlan(state.value.plan.copy(bpm = bpm)) }
    }

    fun setRepeat(enabled: Boolean) {
        events.post { applyPlan(state.value.plan.copy(repeat = enabled)) }
    }

    fun setAutoMock(enabled: Boolean) {
        events.post { if (active == null) state.update { it.copy(autoMock = enabled) } }
    }

    fun setPlan(plan: PracticePlan) {
        events.post { applyPlan(plan) }
    }

    private fun applyPlan(plan: PracticePlan) {
        if (plan == state.value.plan) return
        state.update { it.copy(plan = plan, position = null) }
        if (active != null) startOnWorker(preferredDeviceId)
    }

    internal fun usbConnectionChanged(observation: UsbObservation) {
        events.post { observeConnection(observation) }
    }

    internal fun hit(source: HitSource, event: DrumHitEvent, inputSession: Int?, reading: Reading?) {
        val run = active ?: return
        val anchor = reading?.takeIf { it.id == run.id }?.sample?.anchor ?: run.anchor ?: return
        if (source == HitSource.USB && (inputSession == null || inputSession != run.usbSession)) return
        run.scorer.hit(source, event.drumType, anchor.frameAt(event.timestampNanos))
    }

    internal fun publishInput() {
        val run = active ?: return
        state.update { it.copy(scores = run.scorer.scores(), inputLoss = run.loss.snapshot()) }
    }

    private fun update(run: Run, update: Reading) {
        val reading = update.sample
        val now = update.observedAt
        val played = reading.anchor.frameAt(now).coerceAtMost(reading.playedFrame)
            .coerceAtLeast(run.lastPresentedFrame)
        run.anchor = reading.anchor
        run.lastPresentedFrame = played
        val step = run.plan.stepAtFrame(played)
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
                output = reading.routeName, scores = run.scorer.scores(), message = "", inputLoss = run.loss.snapshot())
        }
    }

    private fun finish(run: Run, message: String) {
        active = null
        intake.set(null)
        val loss = run.loss.snapshot()
        state.update { it.copy(running = false, position = null, scores = run.scorer.scores(), message = message, inputLoss = loss) }
    }

    internal fun observeConnection(observation: UsbObservation) {
        if (observation.atNanos < usb.atNanos) return
        usb = observation
        val run = active ?: return
        if (observation.session == run.usbSession) return
        run.scorer.disarm(HitSource.USB)
        run.usbSession = observation.session
        val frame = run.anchor?.frameAt(observation.atNanos) ?: 0L
        if (observation.session != null) run.scorer.resume(HitSource.USB, frame)
    }
}
