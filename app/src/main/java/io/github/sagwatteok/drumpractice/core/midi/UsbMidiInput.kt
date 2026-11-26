package io.github.sagwatteok.drumpractice.core.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MidiPort(val id: Int, val index: Int, val name: String, val identity: String)
data class MidiConnection(
    val ports: List<MidiPort> = emptyList(),
    val status: String = "검색 중",
    val active: MidiPort? = null,
    val hasSelection: Boolean = false,
    val activeSession: Int? = null,
)

internal typealias MidiPacketReceiver = (ByteArray, List<MidiMessage>, Int) -> Unit

internal interface MidiInput : Closeable {
    val supported: Boolean
    val connection: StateFlow<MidiConnection>
    fun rescan()
    fun connect(port: MidiPort)
    fun disconnect()
}

internal class UsbMidiInput(context: Context, private val receive: MidiPacketReceiver) : MidiInput {
    private val manager = context.getSystemService(MidiManager::class.java)
    override val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI) && manager != null
    private val thread = HandlerThread("usb-midi").apply { start() }
    private val handler = Handler(thread.looper)
    private val state = MutableStateFlow(MidiConnection())
    override val connection = state.asStateFlow()
    private var preferredIdentity: String? = null
    private var opening: MidiPort? = null
    private var active: MidiPort? = null
    private var device: MidiDevice? = null
    private var output: MidiOutputPort? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (active?.id == device.id || opening?.id == device.id) release()
            refresh()
        }
    }

    init {
        handler.post {
            if (supported) {
                if (Build.VERSION.SDK_INT >= 33) {
                    manager!!.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { handler.post(it) }, callback)
                } else {
                    @Suppress("DEPRECATION")
                    manager!!.registerDeviceCallback(callback, handler)
                }
            }
            refresh()
        }
    }

    private fun devices(): List<MidiDeviceInfo> {
        if (!supported) return emptyList()
        @Suppress("DEPRECATION")
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            manager!!.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else manager!!.devices.toList()
        return devices.filter { it.type == MidiDeviceInfo.TYPE_USB }
    }

    private fun ports(devices: List<MidiDeviceInfo>) = devices.flatMap { info ->
        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI"
        info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.map { port ->
            val label = port.name?.takeIf { it.isNotBlank() } ?: "포트 ${port.portNumber + 1}"
            val identity = listOf(name, props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                props.getString(MidiDeviceInfo.PROPERTY_PRODUCT), props.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER),
                port.portNumber).joinToString("|")
            MidiPort(info.id, port.portNumber, "$name · $label", identity)
        }
    }

    private fun refresh() {
        if (closed) return
        val devices = devices()
        val ports = ports(devices)
        val candidates = ports.filter { it.identity == preferredIdentity }
        state.value = MidiConnection(ports, when {
            !supported -> "이 기기는 Android MIDI를 지원하지 않습니다"
            active != null -> "MIDI 연결됨"
            opening != null -> "연결 중"
            candidates.size > 1 -> "같은 이름의 장치가 여러 개 있습니다. 포트를 선택하세요"
            preferredIdentity != null -> "연결 끊김 · 재연결 대기"
            ports.isEmpty() -> "USB MIDI 장치 없음"
            else -> "연결할 MIDI 포트를 선택하세요"
        }, active, preferredIdentity != null, active?.let { generation })
        if (active == null && opening == null && candidates.size == 1) {
            val port = candidates.single()
            open(port, devices.first { it.id == port.id })
        }
    }

    override fun rescan() { handler.post { refresh() } }

    override fun connect(port: MidiPort) {
        handler.post {
            if (closed) return@post
            release()
            preferredIdentity = port.identity
            val info = devices().firstOrNull { it.id == port.id }
            if (info == null) refresh() else open(port, info)
        }
    }

    private fun open(port: MidiPort, info: MidiDeviceInfo) {
        opening = port
        state.value = state.value.copy(status = "연결 중", active = null, hasSelection = true, activeSession = null)
        val token = ++generation
        runCatching {
            manager!!.openDevice(info, { opened ->
                if (closed || generation != token) {
                    opened?.let { runCatching { it.close() } }
                } else {
                    opening = null
                    device = opened
                    val connected = runCatching {
                        val midiPort = checkNotNull(opened?.openOutputPort(port.index))
                        output = midiPort
                        val parser = MidiParser()
                        midiPort.connect(object : MidiReceiver() {
                            @Synchronized
                            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                if (closed || generation != token) return
                                val received = System.nanoTime()
                                val bytes = data.copyOfRange(offset, offset + count)
                                val messages = mutableListOf<MidiMessage>()
                                parser.accept(bytes, timestamp, received, messages::add)
                                if (!closed && generation == token) receive(bytes, messages, token)
                            }
                        })
                    }.isSuccess
                    if (connected) {
                        active = port
                        refresh()
                    } else fail()
                }
            }, handler)
        }.onFailure { fail() }
    }

    private fun fail() {
        release()
        state.value = MidiConnection(
            ports(devices()), "MIDI 포트를 열 수 없습니다. 포트를 다시 선택하세요",
            hasSelection = preferredIdentity != null,
        )
    }

    private fun release() {
        generation++
        opening = null
        active = null
        runCatching { output?.close() }
        runCatching { device?.close() }
        output = null
        device = null
    }

    override fun disconnect() {
        handler.post {
            preferredIdentity = null
            release()
            refresh()
        }
    }

    override fun close() {
        closed = true
        handler.post {
            if (supported) manager!!.unregisterDeviceCallback(callback)
            release()
            thread.quitSafely()
        }
    }
}
