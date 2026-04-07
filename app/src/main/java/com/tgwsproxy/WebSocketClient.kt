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

class WebSocketClient {

    companion object {
        private const val TAG = "WebSocketClient"

        // Telegram WebSocket domains
        fun getWsDomains(dcId: Int, isMedia: Boolean): List<String> {
            return listOf(
                "wss://kws${dcId}.web.telegram.org/apiws",
                "wss://kws${dcId}-1.web.telegram.org/apiws",
                if (isMedia) "wss://kws${dcId}-2.web.telegram.org/apiws" else null
            ).filterNotNull()
        }
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val openLatch = CountDownLatch(1)
    private val messageListener: (ByteArray) -> Unit
    private val closeListener: (Int, String) -> Unit
    private var lastError: Throwable? = null

    constructor(
        onMessage: (ByteArray) -> Unit,
        onClose: (Int, String) -> Unit = { _, _ -> }
    ) {
        this.messageListener = onMessage
        this.closeListener = onClose
    }

    /**
     * Подключается к WebSocket серверу Telegram
     */
    fun connect(url: String, timeoutSeconds: Long = 10): Boolean {
        try {
            Log.d(TAG, "Connecting to WebSocket: $url")

            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // No read timeout for long-lived connections
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened: ${response.code}")
                    isConnected.set(true)
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    messageListener(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Ignore text messages (shouldn't happen with Telegram)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code $reason")
                    isConnected.set(false)
                    closeListener(code, reason)
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    isConnected.set(false)
                    closeListener(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    lastError = t
                    isConnected.set(false)
                    openLatch.countDown()
                    closeListener(1006, t.message ?: "Unknown error")
                }
            })

            // Wait for connection
            if (!openLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                Log.e(TAG, "WebSocket connection timeout")
                webSocket?.cancel()
                return false
            }

            return isConnected.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            lastError = e
            return false
        }
    }

    /**
     * Отправляет данные через WebSocket
     */
    fun send(data: ByteArray): Boolean {
        return try {
            webSocket?.send(ByteString.of(*data)) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send", e)
            false
        }
    }

    /**
     * Закрывает соединение
     */
    fun close(code: Int = 1000, reason: String = "") {
        try {
            webSocket?.close(code, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing", e)
        }
        isConnected.set(false)
    }

    fun isConnected(): Boolean = isConnected.get()

    fun getLastError(): Throwable? = lastError
}
