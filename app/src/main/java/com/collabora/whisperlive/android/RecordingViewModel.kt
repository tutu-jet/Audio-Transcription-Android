package com.collabora.whisperlive.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecordingViewModel {

    data class UiState(
        val serverUrl: String = "wss://nonincriminating-obesely-rosy.ngrok-free.dev",
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val transcript: String = "",
        val logs: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var socket: WhisperLiveWebSocket? = null
    private var streamer: AudioStreamer? = null

    fun setServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun start() {
        if (_uiState.value.isRecording) return

        _uiState.update { it.copy(isRecording = true, isPaused = false, transcript = "") }

        val ws = WhisperLiveWebSocket(
            serverUrl = _uiState.value.serverUrl,
            onLog = ::appendLog,
            onSegments = { text ->
                _uiState.update { it.copy(transcript = text) }
            }
        )
        socket = ws

        val localStreamer = AudioStreamer(
            onLog = ::appendLog,
            onAudioFrame = { bytes -> ws.sendAudio(bytes) }
        )
        streamer = localStreamer

        scope.launch {
            val ok: Boolean = try {
                ws.connectAndConfigure()
            } catch (t: Throwable) {
                appendLog("WebSocket connect/configure failed: ${t.message}")
                false
            }

            if (!ok) {
                stop()
                return@launch
            }

            localStreamer.start()
        }
    }

    fun pause() {
        streamer?.pause()
        _uiState.update { it.copy(isPaused = true) }
    }

    fun resume() {
        streamer?.resume()
        _uiState.update { it.copy(isPaused = false) }
    }

    fun stop() {
        streamer?.stop()
        streamer = null

        socket?.sendEndOfAudio()
        socket?.close()
        socket = null

        _uiState.update { it.copy(isRecording = false, isPaused = false) }
    }

    fun appendLog(line: String) {
        _uiState.update {
            val next = if (it.logs.isBlank()) line else it.logs + "\n" + line
            it.copy(logs = next.takeLast(8000))
        }
    }
}