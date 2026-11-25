package io.github.sagwatteok.drumpractice.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProbeOutput(val id: Int, val label: String)

data class ProbeState(
    val outputs: List<String> = emptyList(),
    val usbOutputs: List<ProbeOutput> = emptyList(),
    val actualRoute: String? = null,
    val usbRouted: Boolean = false,
    val playing: Boolean = false,
    val status: String = "대기 중",
)

class AudioProbe(context: Context) : Closeable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val state = MutableStateFlow(ProbeState())
    val snapshot = state.asStateFlow()
    private var track: AudioTrack? = null
    private val routeListener = AudioRouting.OnRoutingChangedListener { updateRoute() }
    private val watchdog = Runnable { stop("테스트음 재생 종료") }
    private val routePoll = Runnable { updateRoute() }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refresh()
    }

    init {
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        refresh()
    }

    fun refresh() {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        state.value = state.value.copy(
            outputs = outputs.map(::label),
            usbOutputs = outputs.filter(::isUsb).map { ProbeOutput(it.id, label(it)) },
        )
        updateRoute()
    }

    fun play(preferredId: Int?) {
        stop()
        val preferred = if (preferredId == null) null else
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == preferredId && isUsb(it) }
        if (preferredId != null && preferred == null) {
            state.value = state.value.copy(status = "선택한 USB 오디오 장치가 연결되어 있지 않습니다")
            return
        }

        runCatching {
            val sampleRate = 48_000
            val frames = sampleRate * 12
            val pcm = ShortArray(frames) { frame ->
                val tickFrame = frame % (sampleRate / 2)
                if (tickFrame >= 2_400) 0 else
                    (sin(2 * PI * 660 * tickFrame / sampleRate) * 2800 * (1 - tickFrame / 2400.0)).toInt().toShort()
            }
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
                .build()
            track = player
            check(player.state == AudioTrack.STATE_NO_STATIC_DATA || player.state == AudioTrack.STATE_INITIALIZED) {
                "AudioTrack 생성 실패 (상태 ${player.state})"
            }
            val written = player.write(pcm, 0, pcm.size)
            check(written == pcm.size) { "테스트음 버퍼 쓰기 실패 ($written/${pcm.size} 샘플)" }
            check(player.state == AudioTrack.STATE_INITIALIZED) {
                "PCM 기록 후 AudioTrack 초기화 실패 (상태 ${player.state})"
            }
            check(preferred == null || player.setPreferredDevice(preferred)) { "USB 출력 경로 선택 실패" }
            player.addOnRoutingChangedListener(routeListener, handler)
            player.play()
            state.value = state.value.copy(playing = true, status = "120 BPM 테스트음 재생 중")
            updateRoute()
            handler.postDelayed(routePoll, 250)
            handler.postDelayed(watchdog, 12_000)
        }.onFailure {
            stop("오디오 재생 오류 · ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun updateRoute() {
        val routed = track?.routedDevice
        state.value = state.value.copy(actualRoute = routed?.let(::label), usbRouted = routed?.let(::isUsb) == true)
    }

    fun stop(status: String = "대기 중") {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(routePoll)
        track?.let {
            it.removeOnRoutingChangedListener(routeListener)
            runCatching { it.stop() }
            it.release()
        }
        track = null
        state.value = state.value.copy(playing = false, actualRoute = null, usbRouted = false, status = status)
    }

    override fun close() {
        audio.unregisterAudioDeviceCallback(deviceCallback)
        stop()
    }

    private fun isUsb(device: AudioDeviceInfo) = device.type in setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private fun label(device: AudioDeviceInfo): String {
        val type = when {
            isUsb(device) -> "USB"
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "스피커"
            else -> "유형 ${device.type}"
        }
        return "$type · ${device.productName} (#${device.id})"
    }
}
