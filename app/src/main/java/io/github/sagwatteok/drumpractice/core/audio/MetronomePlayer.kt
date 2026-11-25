package io.github.sagwatteok.drumpractice.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import io.github.sagwatteok.drumpractice.core.timing.AudioFrameAnchor
import io.github.sagwatteok.drumpractice.core.timing.ClockSource
import io.github.sagwatteok.drumpractice.core.timing.PRACTICE_SAMPLE_RATE
import io.github.sagwatteok.drumpractice.core.timing.PracticePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val CHUNK_FRAMES = PRACTICE_SAMPLE_RATE / 100
private const val POLL_NANOS = 20_000_000L
private const val TIMESTAMP_MAX_AGE_NANOS = 120_000_000L
private const val STARTUP_TIMEOUT_NANOS = 3_000_000_000L

data class MetronomeReading(val anchor: AudioFrameAnchor, val playedFrame: Long, val routeName: String?)

class MetronomePlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val streamLock = Mutex()

    /** Stream frames off the UI thread. The callback must not block the audio writer. */
    suspend fun play(plan: PracticePlan, preferredDeviceId: Int?, onReading: (MetronomeReading) -> Unit) =
        streamLock.withLock { playStream(plan, preferredDeviceId, onReading) }

    private suspend fun playStream(
        plan: PracticePlan,
        preferredDeviceId: Int?,
        onReading: (MetronomeReading) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val preferred = preferredDeviceId?.let { id ->
                checkNotNull(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == id }) {
                    "선택한 오디오 장치가 연결되어 있지 않습니다"
                }
            }
            val track = buildTrack()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 초기화 실패" }
                check(preferred == null || track.setPreferredDevice(preferred)) { "오디오 출력 경로 선택 실패" }
                val clicks = PracticeClicks(plan)
                val buffer = ShortArray(CHUNK_FRAMES)
                val timestamp = AudioTimestamp()
                val endFrame = plan.endFrame
                var written = 0L

                val initialFrames = minOf(track.bufferSizeInFrames, CHUNK_FRAMES)
                clicks.render(0, buffer, initialFrames)
                written += write(track, buffer, 0, initialFrames)
                track.play()
                val started = System.nanoTime()

                var pendingCount = 0
                var pendingOffset = 0
                var lastPoll = started - POLL_NANOS
                var lastRawHead = 0L
                var lastProgress = started
                var wrappedFrames = 0L
                var previousRouteId: Int? = null
                while (isActive) {
                    val now = System.nanoTime()
                    if (now - lastPoll >= POLL_NANOS) {
                        lastPoll = now
                        val route = track.routedDevice
                        if (route != null && (previousRouteId != null || preferred != null)) {
                            check(route.id == (preferred?.id ?: previousRouteId)) {
                                "오디오 출력 경로가 바뀌었습니다. 장치를 확인한 뒤 다시 시작하세요"
                            }
                        }
                        if (route != null) previousRouteId = route.id

                        val rawHead = track.playbackHeadPosition.toUInt().toLong()
                        if (lastRawHead - rawHead > (1L shl 31)) wrappedFrames += 1L shl 32
                        if (rawHead != lastRawHead) lastProgress = now
                        lastRawHead = rawHead
                        val head = (wrappedFrames + rawHead).coerceAtMost(written)
                        if (head > 0) {
                            val anchor = if (track.getTimestamp(timestamp) &&
                                timestamp.framePosition in 0..written &&
                                timestamp.nanoTime > 0 &&
                                now - timestamp.nanoTime in 0..TIMESTAMP_MAX_AGE_NANOS) {
                                AudioFrameAnchor(timestamp.framePosition, timestamp.nanoTime, written, ClockSource.AUDIO_TIMESTAMP)
                            } else {
                                AudioFrameAnchor(head, now, written, ClockSource.PLAYBACK_HEAD)
                            }
                            onReading(MetronomeReading(anchor, head, route?.productName?.toString()))
                        }
                        if (endFrame != null && head >= endFrame) break
                        check(now - lastProgress < STARTUP_TIMEOUT_NANOS) { "AudioTrack 재생 프레임을 읽을 수 없습니다" }
                    }

                    if (pendingOffset == pendingCount) {
                        pendingCount = minOf(CHUNK_FRAMES.toLong(), (endFrame?.minus(written) ?: CHUNK_FRAMES.toLong())).toInt()
                        pendingOffset = 0
                        if (pendingCount > 0) clicks.render(written, buffer, pendingCount)
                    }
                    val count = if (pendingCount > pendingOffset)
                        write(track, buffer, pendingOffset, pendingCount - pendingOffset) else 0
                    written += count
                    pendingOffset += count
                    if (count == 0) delay(3)
                }
            } finally {
                runCatching { track.pause() }
                track.release()
            }
        }

    private fun write(track: AudioTrack, buffer: ShortArray, offset: Int, count: Int): Int {
        val written = track.write(buffer, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "메트로놈 오디오 쓰기 실패 ($written)" }
        return written
    }

    private fun buildTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            PRACTICE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "48 kHz PCM 출력을 초기화할 수 없습니다" }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(PRACTICE_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, CHUNK_FRAMES * Short.SIZE_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
