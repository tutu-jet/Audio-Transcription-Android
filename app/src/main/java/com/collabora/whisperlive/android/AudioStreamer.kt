package com.collabora.whisperlive.android

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Captures mic audio (typically 48kHz on devices), resamples to 16kHz mono,
 * converts to float32 PCM bytes (little-endian), and streams via callback.
 */
class AudioStreamer(
    private val onLog: (String) -> Unit,
    private val onAudioFrame: (ByteArray) -> Unit,
) {
    private val targetSampleRate = 16_000

    @Volatile private var running = false
    @Volatile private var paused = false

    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        running = true
        paused = false

        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Prefer 48k, fallback to 44.1k, then 16k.
        val candidates = intArrayOf(48_000, 44_100, 16_000)
        var sampleRate = candidates.last()
        var minBuf = 0
        for (sr in candidates) {
            val m = AudioRecord.getMinBufferSize(sr, channelConfig, audioFormat)
            if (m > 0) {
                sampleRate = sr
                minBuf = m
                break
            }
        }

        val bufferSize = max(minBuf, sampleRate / 10 * 2) // ~100ms int16

        val rec = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
        recorder = rec

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onLog("AudioRecord init failed")
            running = false
            return
        }

        onLog("AudioRecord started. inputSampleRate=$sampleRate bufferSize=$bufferSize")
        rec.startRecording()

        val inBuf = ShortArray(bufferSize / 2)

        while (running) {
            if (paused) {
                Thread.sleep(20)
                continue
            }

            val read = rec.read(inBuf, 0, inBuf.size)
            if (read <= 0) continue

            val resampled = if (sampleRate == targetSampleRate) {
                inBuf.copyOfRange(0, read)
            } else {
                Resampler.resampleLinear(inBuf, read, sampleRate, targetSampleRate)
            }

            val floatBytes = pcm16ToFloat32Bytes(resampled)
            onAudioFrame(floatBytes)
        }

        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recorder = null
        onLog("AudioRecord stopped")
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running = false
    }

    private fun pcm16ToFloat32Bytes(pcm: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            val f = (s.toInt() / 32768.0f)
            bb.putFloat(f)
        }
        return bb.array()
    }
}

