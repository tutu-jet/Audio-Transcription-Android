package com.collabora.whisperlive.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhisperLiveWebSocket(
    private val serverUrl: String,
    private val onLog: (String) -> Unit,
    private val onSegments: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val uid = UUID.randomUUID().toString()

    @Volatile
    private var webSocket: WebSocket? = null

    private val readyLatch = CountDownLatch(1)

    fun connectAndConfigure(timeoutSeconds: Long = 10): Boolean {
        onLog("Connecting: $serverUrl")

        val request = Request.Builder().url(serverUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("WebSocket opened")
                sendConfig(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // server usually sends JSON text; ignore binary
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("WebSocket failure: ${t.message}")
                readyLatch.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onLog("WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("WebSocket closed: $code $reason")
                readyLatch.countDown()
            }
        })

        webSocket = ws

        // wait until SERVER_READY (or timeout/failure)
        readyLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        return true
    }

    private fun sendConfig(ws: WebSocket) {
        val json = JSONObject()
            .put("uid", uid)
            .put("language", "en")
            .put("task", "transcribe")
            .put("model", "small")
            .put("use_vad", true)
            .put("max_clients", 4)
            .put("max_connection_time", 600)

        val ok = ws.send(json.toString())
        onLog("Sent config: $ok")
    }

    private fun handleText(text: String) {
        try {
            val obj = JSONObject(text)

            if (obj.has("status")) {
                onLog("Server status: ${obj.optString("status")} ${obj.optString("message")}")
                return
            }

            if (obj.optString("message") == "SERVER_READY") {
                onLog("Server ready")
                readyLatch.countDown()
                return
            }

            if (obj.has("segments")) {
                val segments = obj.getJSONArray("segments")
                // simplest UX: concatenate last N segments' text
                val sb = StringBuilder()
                for (i in 0 until segments.length()) {
                    val seg = segments.getJSONObject(i)
                    val t = seg.optString("text").trim()
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(t)
                    }
                }
                onSegments(sb.toString())
            }
        } catch (e: Exception) {
            onLog("JSON parse error: ${e.message}")
        }
    }

    fun sendAudio(float32Bytes: ByteArray) {
        val ws = webSocket ?: return
        ws.send(ByteString.of(*float32Bytes))
    }

    fun sendEndOfAudio() {
        webSocket?.send("END_OF_AUDIO")
    }

    fun close() {
        webSocket?.close(1000, "bye")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}

