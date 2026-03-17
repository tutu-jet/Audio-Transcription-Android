package com.collabora.whisperlive.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var controller: AsrController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = AsrController(applicationContext)

        setContent {
            val permissionGranted = remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                permissionGranted.value = granted
                if (!granted) {
                    controller.statusText = "请先授予麦克风权限"
                } else {
                    controller.refreshMicrophones()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    controller.releaseAll()
                }
            }

            AppTheme(themePreference = controller.themePreference) {
                AsrScreen(
                    controller = controller,
                    hasRecordPermission = permissionGranted.value,
                    onRequestPermission = {
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }
}

enum class ThemePreference {
    SYSTEM, LIGHT, DARK
}

data class TranscriptLine(
    val text: String = "",
    val translation: String? = null,
    val detectedLanguage: String? = null,
    val start: String? = null,
    val end: String? = null
)

data class MicrophoneItem(
    val id: Int,
    val name: String,
    val device: AudioDeviceInfo?
)

class AsrController(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("asr_compose_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    var isRecording by mutableStateOf(false)
        private set

    var waitingForStop by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("点击开始转录")
    var websocketUrl by mutableStateOf(
        prefs.getString(
            "ws_url",
            "ws://nonincriminating-obesely-rosy.ngrok-free.dev/asr"
        ) ?: "ws://nonincriminating-obesely-rosy.ngrok-free.dev/asr"
    )
    var chunkDurationMs by mutableStateOf(prefs.getInt("chunk_duration", 100))
    var settingsVisible by mutableStateOf(false)
    var serverUseAudioWorklet: Boolean? by mutableStateOf(null)
    var themePreference by mutableStateOf(loadTheme())
        private set

    var startElapsedRealtime by mutableLongStateOf(0L)
        private set

    var elapsedSeconds by mutableLongStateOf(0L)
        private set

    val transcriptLines = mutableStateListOf<TranscriptLine>()
    val waveformPoints = mutableStateListOf<Float>()
    val microphones = mutableStateListOf<MicrophoneItem>()

    var selectedMicrophoneId by mutableStateOf(
        prefs.getInt("selected_mic_id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    )
        private set

    var bufferTranscription by mutableStateOf("")
        private set
    var bufferDiarization by mutableStateOf("")
        private set
    var bufferTranslation by mutableStateOf("")
        private set
    var remainingTimeTranscription by mutableFloatStateOf(0f)
        private set
    var remainingTimeDiarization by mutableFloatStateOf(0f)
        private set
    var currentStatus by mutableStateOf("active_transcription")
        private set

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var userClosing = false
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var timerJob: Job? = null
    private var lastReceivedData: JSONObject? = null

    init {
        refreshMicrophones()
        if (waveformPoints.isEmpty()) {
            repeat(60) { waveformPoints.add(0f) }
        }
    }

    fun refreshMicrophones() {
        microphones.clear()
        microphones.add(MicrophoneItem(-1, "默认麦克风", null))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource }
                .map {
                    val label = buildString {
                        append(
                            it.productName?.toString()?.ifBlank { "输入设备 ${it.id}" }
                                ?: "输入设备 ${it.id}"
                        )
                        append(" (id=${it.id})")
                    }
                    MicrophoneItem(it.id, label, it)
                }

            microphones.addAll(inputs)

            if (selectedMicrophoneId != null && microphones.none { it.id == selectedMicrophoneId }) {
                selectedMicrophoneId = null
                prefs.edit().remove("selected_mic_id").apply()
            }
        }
    }

    fun setTheme(theme: ThemePreference) {
        themePreference = theme
        prefs.edit().putString("theme", theme.name).apply()
    }

    fun updateWebSocketUrl(url: String) {
        websocketUrl = url
        prefs.edit().putString("ws_url", url).apply()
    }

    fun updateChunkDuration(ms: Int) {
        chunkDurationMs = ms
        prefs.edit().putInt("chunk_duration", ms).apply()
    }

    fun updateSelectedMicrophone(id: Int?) {
        selectedMicrophoneId = id
        if (id == null || id == -1) {
            prefs.edit().remove("selected_mic_id").apply()
            statusText = "已切换到默认麦克风"
        } else {
            prefs.edit().putInt("selected_mic_id", id).apply()
            val micName = microphones.firstOrNull { it.id == id }?.name ?: "麦克风 $id"
            statusText = "已切换到：$micName"
        }
    }

    fun toggleRecording(hasPermission: Boolean) {
        if (!hasPermission) {
            statusText = "请先授予麦克风权限"
            return
        }

        if (!isRecording) {
            if (waitingForStop) return
            scope.launch { connectAndStart() }
        } else {
            scope.launch { stopRecording() }
        }
    }

    private suspend fun connectAndStart() {
        if (!isValidWsUrl(websocketUrl)) {
            statusText = "WebSocket 地址不合法，必须以 ws:// 或 wss:// 开头"
            return
        }

        statusText = "正在连接服务器..."
        currentStatus = "active_transcription"
        clearBuffers()
        userClosing = false

        val request = Request.Builder()
            .url(websocketUrl)
            .build()

        try {
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        statusText = "已连接服务器"
                        startRecordingInternal()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val data = JSONObject(text)
                        lastReceivedData = data
                        handleServerMessage(data)
                    } catch (e: Exception) {
                        scope.launch {
                            statusText = "收到无法解析的服务器消息"
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch {
                        handleSocketClosed()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch {
                        statusText = "WebSocket 连接失败：${t.message ?: "unknown"}"
                        isRecording = false
                        waitingForStop = false
                        releaseAudioOnly()
                    }
                }
            })
        } catch (e: Exception) {
            statusText = "无法建立 WebSocket：${e.message ?: "unknown"}"
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startRecordingInternal() {
        try {
            val sampleRate = 16_000
            val channelCount = 1
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                statusText = "无法获取录音缓冲区大小"
                return
            }

            val chunkSamples = maxOf(sampleRate * chunkDurationMs / 1000, 1024)
            val recordBufferSize = maxOf(minBufferSize, chunkSamples * 2)

            val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    recordBufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                statusText = "AudioRecord 初始化失败"
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                applyPreferredInputDevice(record)
            }

            val encoder = createAacEncoder(sampleRate, channelCount)
            val bufferInfo = MediaCodec.BufferInfo()

            audioRecord = record
            record.startRecording()

            statusText = "已连接。使用 AAC-ADTS 推流，正在录音..."
            startElapsedRealtime = SystemClock.elapsedRealtime()
            startTimer()
            isRecording = true
            waitingForStop = false

            audioJob?.cancel()
            audioJob = ioScope.launch {
                val shortBuffer = ShortArray(chunkSamples)
                var presentationTimeUs = 0L

                try {
                    while (isActive && isRecording) {
                        val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                        if (readCount <= 0) {
                            delay(10)
                            continue
                        }

                        updateWaveform(shortBuffer, readCount)

                        val pcmBytes = shortArrayToLittleEndianBytes(shortBuffer, readCount)

                        val inputIndex = encoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(pcmBytes)

                            val frameDurationUs = (readCount * 1_000_000L) / sampleRate
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                pcmBytes.size,
                                presentationTimeUs,
                                0
                            )
                            presentationTimeUs += frameDurationUs
                        }

                        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        while (outputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (
                                outputBuffer != null &&
                                bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                val aacRaw = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(aacRaw)

                                val adtsPacket = addAdtsHeader(aacRaw, sampleRate, channelCount)
                                webSocket?.send(adtsPacket.toByteString())
                            }

                            encoder.releaseOutputBuffer(outputIndex, false)
                            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        }
                    }

                    val eosInputIndex = encoder.dequeueInputBuffer(10_000)
                    if (eosInputIndex >= 0) {
                        encoder.queueInputBuffer(
                            eosInputIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }

                    var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outputIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (
                            outputBuffer != null &&
                            bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val aacRaw = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(aacRaw)

                            val adtsPacket = addAdtsHeader(aacRaw, sampleRate, channelCount)
                            webSocket?.send(adtsPacket.toByteString())
                        }

                        val flags = bufferInfo.flags
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }

                        outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    }
                } catch (e: Exception) {
                    scope.launch {
                        statusText = "AAC 编码/发送失败：${e.message ?: "unknown"}"
                    }
                } finally {
                    runCatching { encoder.stop() }
                    runCatching { encoder.release() }
                }
            }
        } catch (e: Exception) {
            statusText = "启动录音失败：${e.message ?: "unknown"}"
            releaseAudioOnly()
            isRecording = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun applyPreferredInputDevice(record: AudioRecord) {
        val selected = microphones.firstOrNull { it.id == selectedMicrophoneId }?.device
        if (selected != null) {
            runCatching {
                record.preferredDevice = selected
            }
        }
    }

    private fun updateWaveform(buffer: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count.coerceAtLeast(1))
        val normalized = (rms / 6000.0).toFloat().coerceIn(0f, 1f)

        scope.launch {
            if (waveformPoints.size >= 60) waveformPoints.removeAt(0)
            waveformPoints.add(normalized)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        elapsedSeconds = 0L
        timerJob = scope.launch {
            while (isActive && (isRecording || waitingForStop)) {
                elapsedSeconds = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000L)
                delay(1000)
            }
        }
    }

    suspend fun stopRecording() {
        waitingForStop = true
        isRecording = false
        statusText = "录音已停止，正在等待服务端处理..."

        releaseAudioOnly()

        userClosing = true
        webSocket?.send(ByteArray(0).toByteString())
    }

    private fun handleServerMessage(data: JSONObject) {
        val type = data.optString("type")

        when (type) {
            "config" -> {
                serverUseAudioWorklet = data.optBoolean("useAudioWorklet", true)
                statusText = if (serverUseAudioWorklet == true) {
                    "已连接。服务端为 PCM 模式。"
                } else {
                    "已连接。服务端返回非 PCM 配置。"
                }
                return
            }

            "diff", "snapshot" -> {
                return
            }

            "ready_to_stop" -> {
                scope.launch {
                    waitingForStop = false
                    applyLastServerRender(finalizing = true)
                    statusText = "音频处理完成，可以再次开始录音"
                    webSocket?.close(1000, "done")
                }
                return
            }
        }

        currentStatus = data.optString("status", "active_transcription")
        remainingTimeTranscription =
            data.optDouble("remaining_time_transcription", 0.0).toFloat()
        remainingTimeDiarization = 0f
        bufferTranscription = data.optString("buffer_transcription", "")
        bufferDiarization = ""
        bufferTranslation = data.optString("buffer_translation", "")

        val newLines = mutableListOf<TranscriptLine>()
        val arr = data.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            newLines += TranscriptLine(
                text = item.optString("text", ""),
                translation = item.optString("translation", "").takeIf { it.isNotBlank() },
                detectedLanguage = item.optString("detected_language", "").takeIf { it.isNotBlank() },
                start = item.opt("start")?.toString(),
                end = item.opt("end")?.toString()
            )
        }

        scope.launch {
            transcriptLines.clear()
            transcriptLines.addAll(newLines)
        }
    }

    private suspend fun handleSocketClosed() {
        if (userClosing) {
            if (waitingForStop) {
                statusText = "连接已关闭，显示最终结果"
                applyLastServerRender(finalizing = true)
            }
        } else {
            statusText = "WebSocket 已断开"
        }

        userClosing = false
        waitingForStop = false
        isRecording = false
        releaseAudioOnly()
    }

    private fun applyLastServerRender(finalizing: Boolean) {
        val data = lastReceivedData ?: return
        currentStatus = data.optString("status", currentStatus)
        bufferTranscription = data.optString("buffer_transcription", "")
        bufferDiarization = ""
        bufferTranslation = data.optString("buffer_translation", "")

        if (finalizing) {
            if (bufferTranscription.isNotBlank() || bufferTranslation.isNotBlank()) {
                if (transcriptLines.isEmpty()) {
                    transcriptLines.add(
                        TranscriptLine(
                            text = bufferTranscription.trim(),
                            translation = bufferTranslation.takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    val last = transcriptLines.last()
                    transcriptLines[transcriptLines.lastIndex] = last.copy(
                        text = buildString {
                            append(last.text)
                            if (bufferTranscription.isNotBlank()) {
                                if (isNotBlank()) append(" ")
                                append(bufferTranscription.trim())
                            }
                        },
                        translation = buildString {
                            if (!last.translation.isNullOrBlank()) append(last.translation)
                            if (bufferTranslation.isNotBlank()) append(bufferTranslation)
                        }.takeIf { it.isNotBlank() }
                    )
                }
            }
        }

        clearBuffers()
    }

    fun releaseAll() {
        userClosing = true
        try {
            webSocket?.close(1000, "release")
        } catch (_: Exception) {
        }
        webSocket = null
        releaseAudioOnly()
        timerJob?.cancel()
        timerJob = null
    }

    private fun releaseAudioOnly() {
        audioJob?.cancel()
        audioJob = null
        timerJob?.cancel()
        timerJob = null

        runCatching {
            audioRecord?.stop()
        }
        runCatching {
            audioRecord?.release()
        }
        audioRecord = null

        elapsedSeconds = 0L
        waveformPoints.clear()
        repeat(60) { waveformPoints.add(0f) }
    }

    private fun loadTheme(): ThemePreference {
        return runCatching {
            ThemePreference.valueOf(prefs.getString("theme", ThemePreference.SYSTEM.name)!!)
        }.getOrDefault(ThemePreference.SYSTEM)
    }

    private fun clearBuffers() {
        bufferTranscription = ""
        bufferDiarization = ""
        bufferTranslation = ""
        remainingTimeTranscription = 0f
        remainingTimeDiarization = 0f
    }

    private fun isValidWsUrl(url: String): Boolean {
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    private fun shortArrayToLittleEndianBytes(input: ShortArray, count: Int): ByteArray {
        val output = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val s = input[i].toInt()
            output[j++] = (s and 0xFF).toByte()
            output[j++] = ((s shr 8) and 0xFF).toByte()
        }
        return output
    }
}

@Composable
fun AsrScreen(
    controller: AsrController,
    hasRecordPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val chunkChoices = listOf(50, 100, 200, 500)

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (controller.isRecording) {
                    controller.releaseAll()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val timerText by remember(controller.elapsedSeconds) {
        derivedStateOf {
            val minutes = (controller.elapsedSeconds / 60).toString().padStart(2, '0')
            val seconds = (controller.elapsedSeconds % 60).toString().padStart(2, '0')
            "$minutes:$seconds"
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            HeaderSection(
                status = controller.statusText,
                timer = timerText,
                isRecording = controller.isRecording,
                waitingForStop = controller.waitingForStop,
                onToggleSettings = { controller.settingsVisible = !controller.settingsVisible }
            )

            Spacer(Modifier.height(12.dp))

            if (controller.settingsVisible) {
                SettingsCard(
                    websocketUrl = controller.websocketUrl,
                    onWebsocketUrlChange = { controller.updateWebSocketUrl(it) },
                    chunkDurationMs = controller.chunkDurationMs,
                    chunkChoices = chunkChoices,
                    onChunkSelected = { controller.updateChunkDuration(it) },
                    microphones = controller.microphones,
                    selectedMicrophoneId = controller.selectedMicrophoneId,
                    onMicrophoneSelected = { controller.updateSelectedMicrophone(it) },
                    themePreference = controller.themePreference,
                    onThemeChange = { controller.setTheme(it) }
                )
                Spacer(Modifier.height(12.dp))
            }

            WaveformCard(points = controller.waveformPoints)

            Spacer(Modifier.height(12.dp))

            RecordRow(
                hasRecordPermission = hasRecordPermission,
                isRecording = controller.isRecording,
                waitingForStop = controller.waitingForStop,
                onRequestPermission = onRequestPermission,
                onToggleRecording = { controller.toggleRecording(hasRecordPermission) }
            )

            Spacer(Modifier.height(16.dp))

            TranscriptCard(
                lines = controller.transcriptLines,
                bufferTranscription = controller.bufferTranscription,
                bufferTranslation = controller.bufferTranslation,
                remainingTimeTranscription = controller.remainingTimeTranscription,
                currentStatus = controller.currentStatus,
                isFinalizing = controller.waitingForStop
            )
        }
    }

    BackHandler(enabled = controller.settingsVisible) {
        controller.settingsVisible = false
    }
}

@Composable
private fun HeaderSection(
    status: String,
    timer: String,
    isRecording: Boolean,
    waitingForStop: Boolean,
    onToggleSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ASR Compose",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (status.isBlank()) {
                    if (isRecording) "录音中..." else if (waitingForStop) "处理中..." else "点击开始转录"
                } else status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onToggleSettings) {
                Text("设置")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    websocketUrl: String,
    onWebsocketUrlChange: (String) -> Unit,
    chunkDurationMs: Int,
    chunkChoices: List<Int>,
    onChunkSelected: (Int) -> Unit,
    microphones: List<MicrophoneItem>,
    selectedMicrophoneId: Int?,
    onMicrophoneSelected: (Int?) -> Unit,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit
) {
    var wsText by rememberSaveable(websocketUrl) { mutableStateOf(websocketUrl) }
    var chunkExpanded by remember { mutableStateOf(false) }
    var micExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("连接设置", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = wsText,
                onValueChange = {
                    wsText = it
                    onWebsocketUrlChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WebSocket 地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(Modifier.height(12.dp))

            Box {
                OutlinedButton(onClick = { chunkExpanded = true }) {
                    Text("分片时长：${chunkDurationMs}ms")
                }
                DropdownMenu(
                    expanded = chunkExpanded,
                    onDismissRequest = { chunkExpanded = false }
                ) {
                    chunkChoices.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item}ms") },
                            onClick = {
                                chunkExpanded = false
                                onChunkSelected(item)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Box {
                val selectedName =
                    microphones.firstOrNull { it.id == (selectedMicrophoneId ?: -1) }?.name
                        ?: "默认麦克风"

                OutlinedButton(onClick = { micExpanded = true }) {
                    Text(
                        text = "输入设备：$selectedName",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DropdownMenu(
                    expanded = micExpanded,
                    onDismissRequest = { micExpanded = false }
                ) {
                    microphones.forEach { mic ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    mic.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                micExpanded = false
                                onMicrophoneSelected(if (mic.id == -1) null else mic.id)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Text("主题", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            ThemePreference.entries.forEach { pref ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeChange(pref) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = themePreference == pref,
                        onClick = { onThemeChange(pref) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (pref) {
                            ThemePreference.SYSTEM -> "跟随系统"
                            ThemePreference.LIGHT -> "浅色"
                            ThemePreference.DARK -> "深色"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WaveformCard(points: List<Float>) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("波形", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(8.dp)
            ) {
                val sche = MaterialTheme.colorScheme
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (points.isEmpty()) return@Canvas

                    val centerY = size.height / 2f
                    val step = size.width / (points.size - 1).coerceAtLeast(1)

                    for (i in 0 until points.size - 1) {
                        val x1 = i * step
                        val x2 = (i + 1) * step
                        val y1 = centerY - points[i] * centerY
                        val y2 = centerY - points[i + 1] * centerY

                        drawLine(
                            color = sche.primary,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round
                        )

                        drawLine(
                            color = sche.primary.copy(alpha = 0.35f),
                            start = Offset(x1, centerY + (centerY - y1)),
                            end = Offset(x2, centerY + (centerY - y2)),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(
    hasRecordPermission: Boolean,
    isRecording: Boolean,
    waitingForStop: Boolean,
    onRequestPermission: () -> Unit,
    onToggleRecording: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (!hasRecordPermission) {
            Button(onClick = onRequestPermission) {
                Text("请求麦克风权限")
            }
        } else {
            Button(
                onClick = onToggleRecording,
                enabled = !waitingForStop,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.size(84.dp)
            ) {
                Text(
                    text = when {
                        waitingForStop -> "等待"
                        isRecording -> "停止"
                        else -> "开始"
                    },
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun TranscriptCard(
    lines: List<TranscriptLine>,
    bufferTranscription: String,
    bufferTranslation: String,
    remainingTimeTranscription: Float,
    currentStatus: String,
    isFinalizing: Boolean
) {
    val scrollState = rememberScrollState()

    val mergedText = buildString {
        lines.forEach { line ->
            val t = line.text.trim()
            if (t.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(t)
            }
        }

        val buf = bufferTranscription.trim()
        if (buf.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(buf)
        }
    }.trim()

    val mergedTranslation = buildString {
        lines.forEach { line ->
            val t = line.translation?.trim().orEmpty()
            if (t.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(t)
            }
        }

        val buf = bufferTranslation.trim()
        if (buf.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(buf)
        }
    }.trim()

    LaunchedEffect(mergedText, mergedTranslation) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("转写结果", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (currentStatus == "no_audio_detected") {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "没有检测到音频...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            if (!isFinalizing) {
                AssistChipText("转写延迟 ${fmt1(remainingTimeTranscription)}s")
                Spacer(Modifier.height(12.dp))
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    if (mergedText.isNotBlank()) {
                        Text(
                            text = mergedText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            text = "正在等待语音输入...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (mergedTranslation.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "翻译：\n$mergedTranslation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun TranscriptLineCard(
    line: TranscriptLine,
    lineText: String,
    translationText: String,
    isLast: Boolean,
    isFinalizing: Boolean,
    remainingTimeTranscription: Float
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (line.detectedLanguage != null) {
                    AssistChipText("语言: ${line.detectedLanguage}")
                }

                if (isLast && !isFinalizing) {
                    AssistChipText("转写延迟 ${fmt1(remainingTimeTranscription)}s")
                }

                val timeText = buildString {
                    if (!line.start.isNullOrBlank() || !line.end.isNullOrBlank()) {
                        append(line.start ?: "")
                        append(" - ")
                        append(line.end ?: "")
                    }
                }
                if (timeText.isNotBlank()) {
                    AssistChipText(timeText)
                }
            }

            if (lineText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lineText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (translationText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "翻译：$translationText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AssistChipText(text: String) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun fmt1(x: Float): String = String.format(Locale.US, "%.1f", x)

@Composable
fun AppTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit
) {
    val dark = when (themePreference) {
        ThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = if (dark) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

private fun addAdtsHeader(aacData: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
    val packetLength = aacData.size + 7
    val profile = 2
    val freqIdx = getAacSampleRateIndex(sampleRate)
    val chanCfg = channelCount

    val packet = ByteArray(packetLength)

    packet[0] = 0xFF.toByte()
    packet[1] = 0xF1.toByte()
    packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
    packet[3] = (((chanCfg and 3) shl 6) + (packetLength shr 11)).toByte()
    packet[4] = ((packetLength and 0x7FF) shr 3).toByte()
    packet[5] = (((packetLength and 7) shl 5) + 0x1F).toByte()
    packet[6] = 0xFC.toByte()

    System.arraycopy(aacData, 0, packet, 7, aacData.size)
    return packet
}

private fun getAacSampleRateIndex(sampleRate: Int): Int {
    return when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        7350 -> 12
        else -> 8
    }
}

private fun createAacEncoder(sampleRate: Int = 16000, channelCount: Int = 1): MediaCodec {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val format = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        sampleRate,
        channelCount
    )
    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    format.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    return codec
}