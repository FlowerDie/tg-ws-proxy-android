package com.tgwsproxy

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WebSocketClient {

    companion object {
        private const val TAG = "WSClient"

        fun getWsDomains(dcId: Int, isMedia: Boolean): List<String> {
            return listOf(
                "wss://kws${dcId}.web.telegram.org/apiws",
                "wss://kws${dcId}-1.web.telegram.org/apiws",
                if (isMedia) "wss://kws${dcId}-2.web.telegram.org/apiws" else null,
                "wss://ws${dcId}.web.telegram.org/apiws",
            ).filterNotNull()
        }
    }

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val openLatch = CountDownLatch(1)
    private val messageQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(1000)
    private val closed = AtomicBoolean(false)
    private val errorRef = AtomicReference<Throwable?>(null)

    private val onMessage: (ByteArray) -> Unit
    private val onClose: (Int, String) -> Unit

    constructor(
        onMessage: (ByteArray) -> Unit,
        onClose: (Int, String) -> Unit = { _, _ -> }
    ) {
        this.onMessage = onMessage
        this.onClose = onClose
    }

    fun connect(url: String, timeoutSeconds: Long = 10): Boolean {
        try {
            Log.d(TAG, "Connecting: $url")

            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "binary")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Open: ${response.code}")
                    connected.set(true)
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    try {
                        onMessage(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "onMessage handler error", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Closing: $code $reason")
                    connected.set(false)
                    closed.set(true)
                    onClose(code, reason)
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Closed: $code $reason")
                    connected.set(false)
                    closed.set(true)
                    onClose(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Failure", t)
                    errorRef.set(t)
                    connected.set(false)
                    closed.set(true)
                    openLatch.countDown()
                    onClose(1006, t.message ?: "error")
                }
            })

            if (!openLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout")
                webSocket?.cancel()
                return false
            }

            return connected.get()
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            return false
        }
    }

    fun send(data: ByteArray): Boolean {
        return try {
            webSocket?.send(ByteString.of(*data)) == true
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            false
        }
    }

    fun close(code: Int = 1000, reason: String = "") {
        try {
            webSocket?.close(code, reason)
        } catch (_: Exception) {}
        connected.set(false)
        closed.set(true)
    }

    fun isConnected(): Boolean = connected.get()

    fun isClosed(): Boolean = closed.get()
}
