package com.tgwsproxy

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher

class MTProtoHandler {

    companion object {
        private const val TAG = "MTProtoHandler"

        // Default Telegram DC IPs
        private val DC_DEFAULT_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5"
        )

        private const val HANDSHAKE_LEN = 64
    }

    /**
     * Обрабатывает клиентское подключение
     */
    fun handleClientConnection(
        clientSocket: Socket,
        config: ProxyConfig,
        onTrafficUpdate: (Long, Long) -> Unit,
        onConnectionUpdate: (Int) -> Unit
    ) {
        var bytesRead = 0L
        var bytesWritten = 0L

        try {
            // Read handshake
            val handshake = ByteArray(HANDSHAKE_LEN)
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            var readLen = 0
            while (readLen < HANDSHAKE_LEN) {
                val n = input.read(handshake, readLen, HANDSHAKE_LEN - readLen)
                if (n <= 0) {
                    Log.w(TAG, "Client disconnected during handshake")
                    return
                }
                readLen += n
            }

            bytesRead += HANDSHAKE_LEN

            // Validate MTProto handshake
            if (!CryptoUtils.isMtProtoHandshake(handshake)) {
                Log.w(TAG, "Invalid MTProto handshake, closing connection")
                clientSocket.close()
                return
            }

            // Parse handshake with secret
            val secretBytes = hexStringToByteArray(config.secret)

            val handshakeResult = CryptoUtils.parseHandshake(handshake, secretBytes)
                ?: run {
                    Log.w(TAG, "Failed to parse handshake")
                    clientSocket.close()
                    return
                }

            Log.d(TAG, "Handshake parsed: DC=${handshakeResult.dcId}, Media=${handshakeResult.isMedia}")

            // Generate encryption keys
            val cltToTgKey = CryptoUtils.generateAesKey(handshakeResult.randomBytes, secretBytes)
            val tgToCltKey = CryptoUtils.generateAesKey(secretBytes, handshakeResult.randomBytes)

            val cltToTgIv = concatByteArrays(
                handshakeResult.randomBytes.copyOfRange(0, 4),
                secretBytes.copyOfRange(0, 12)
            )
            val tgToCltIv = concatByteArrays(
                secretBytes.copyOfRange(0, 4),
                handshakeResult.randomBytes.copyOfRange(0, 12)
            )

            val cltEncryptor = CryptoUtils.createAesCtr(cltToTgKey, cltToTgIv)
            val cltDecryptor = CryptoUtils.createAesCtrDecrypt(cltToTgKey, cltToTgIv)
            val tgEncryptor = CryptoUtils.createAesCtr(tgToCltKey, tgToCltIv)
            val tgDecryptor = CryptoUtils.createAesCtrDecrypt(tgToCltKey, tgToCltIv)

            // Get target DC IP
            val targetDcId = handshakeResult.dcId
            val targetIp = config.dcIpMap[targetDcId] ?: DC_DEFAULT_IPS[targetDcId]
                ?: run {
                    Log.e(TAG, "No IP configured for DC $targetDcId")
                    clientSocket.close()
                    return
                }

            // Try WebSocket connection first
            val wsDomains = WebSocketClient.getWsDomains(targetDcId, handshakeResult.isMedia)
            var wsClient: WebSocketClient? = null

            for (url in wsDomains) {
                val client = WebSocketClient(
                    onMessage = { /* handled by bridge */ },
                    onClose = { _, _ -> }
                )
                if (client.connect(url)) {
                    wsClient = client
                    break
                }
            }

            if (wsClient != null) {
                // WebSocket bridge
                handleWsBridge(
                    clientSocket = clientSocket,
                    input = input,
                    output = output,
                    wsClient = wsClient,
                    cltDecryptor = cltDecryptor,
                    cltEncryptor = cltEncryptor,
                    tgDecryptor = tgDecryptor,
                    tgEncryptor = tgEncryptor,
                    onTrafficUpdate = { r, w ->
                        bytesRead += r
                        bytesWritten += w
                        onTrafficUpdate(bytesRead, bytesWritten)
                    }
                )
                wsClient.close()
            } else {
                // TCP fallback
                Log.w(TAG, "WebSocket unavailable, falling back to TCP for DC $targetDcId")
                handleTcpBridge(
                    clientSocket = clientSocket,
                    input = input,
                    output = output,
                    targetIp = targetIp,
                    targetPort = 443,
                    cltDecryptor = cltDecryptor,
                    cltEncryptor = cltEncryptor,
                    tgDecryptor = tgDecryptor,
                    tgEncryptor = tgEncryptor,
                    onTrafficUpdate = { r, w ->
                        bytesRead += r
                        bytesWritten += w
                        onTrafficUpdate(bytesRead, bytesWritten)
                    }
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                // Ignore
            }
            onConnectionUpdate(-1)
        }
    }

    private fun handleWsBridge(
        clientSocket: Socket,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        wsClient: WebSocketClient,
        cltDecryptor: Cipher,
        cltEncryptor: Cipher,
        tgDecryptor: Cipher,
        tgEncryptor: Cipher,
        onTrafficUpdate: (Long, Long) -> Unit
    ) {
        // TCP -> WebSocket direction
        val tcpToWs = Thread {
            try {
                val buffer = ByteArray(32 * 1024) // 32KB buffer
                while (!clientSocket.isClosed && wsClient.isConnected()) {
                    val n = input.read(buffer)
                    if (n <= 0) break

                    val decrypted = cltDecryptor.update(buffer, 0, n)
                    val encrypted = tgEncryptor.update(decrypted)
                    if (!wsClient.send(encrypted)) break

                    onTrafficUpdate(n.toLong(), 0)
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP->WS bridge stopped", e)
            }
        }

        tcpToWs.start()
        tcpToWs.join()
    }

    private fun handleTcpBridge(
        clientSocket: Socket,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        targetIp: String,
        targetPort: Int,
        cltDecryptor: Cipher,
        cltEncryptor: Cipher,
        tgDecryptor: Cipher,
        tgEncryptor: Cipher,
        onTrafficUpdate: (Long, Long) -> Unit
    ) {
        Log.d(TAG, "TCP bridge to $targetIp:$targetPort")

        val remoteSocket = Socket()
        remoteSocket.connect(InetSocketAddress(targetIp, targetPort), 10000)
        val remoteInput = remoteSocket.getInputStream()
        val remoteOutput = remoteSocket.getOutputStream()

        // TCP -> Remote
        val tcpToRemote = Thread {
            try {
                val buffer = ByteArray(32 * 1024)
                while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                    val n = input.read(buffer)
                    if (n <= 0) break

                    val decrypted = cltDecryptor.update(buffer, 0, n)
                    val encrypted = tgEncryptor.update(decrypted)
                    remoteOutput.write(encrypted)
                    remoteOutput.flush()

                    onTrafficUpdate(n.toLong(), 0)
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP->Remote bridge stopped", e)
            }
        }

        // Remote -> TCP
        val remoteToTcp = Thread {
            try {
                val buffer = ByteArray(32 * 1024)
                while (!clientSocket.isClosed && !remoteSocket.isClosed) {
                    val n = remoteInput.read(buffer)
                    if (n <= 0) break

                    val decrypted = tgDecryptor.update(buffer, 0, n)
                    val encrypted = cltEncryptor.update(decrypted)
                    output.write(encrypted)
                    output.flush()

                    onTrafficUpdate(0, n.toLong())
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote->TCP bridge stopped", e)
            }
        }

        tcpToRemote.start()
        remoteToTcp.start()

        tcpToRemote.join()
        remoteToTcp.join()

        remoteSocket.close()
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            result[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun concatByteArrays(a: ByteArray, b: ByteArray): ByteArray {
        return a + b
    }
}
