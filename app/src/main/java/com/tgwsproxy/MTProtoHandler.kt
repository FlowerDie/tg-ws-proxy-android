package com.tgwsproxy

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

class MTProtoHandler {

    companion object {
        private const val TAG = "MTProto"
        private const val HS_LEN = 64

        private val DC_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5"
        )
    }

    fun handleClientConnection(
        clientSocket: Socket,
        config: ProxyConfig,
        onTrafficUpdate: (Long, Long) -> Unit,
        onConnectionUpdate: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        var totalRx = 0L
        var totalTx = 0L
        val alive = AtomicBoolean(true)

        try {
            val cIn = clientSocket.getInputStream()
            val cOut = clientSocket.getOutputStream()

            // === Read 64-byte handshake ===
            val hs = ByteArray(HS_LEN)
            var pos = 0
            while (pos < HS_LEN) {
                val n = cIn.read(hs, pos, HS_LEN - pos)
                if (n <= 0) {
                    Log.w(TAG, "Client EOF during handshake")
                    return
                }
                pos += n
            }
            totalRx += HS_LEN

            if (!CryptoUtils.isMtProtoHandshake(hs)) {
                val msg = "Not MTProto handshake"
                Log.w(TAG, msg)
                onError(msg)
                return
            }

            val secret = hexToBytes(config.secret)
            val hsResult = CryptoUtils.parseHandshake(hs, secret)
            if (hsResult == null) {
                onError("Bad handshake parse")
                return
            }

            Log.d(TAG, "DC=${hsResult.dcId} media=${hsResult.isMedia}")
            onError("DC ${hsResult.dcId} detected")

            // === Crypto setup ===
            val c2tKey = CryptoUtils.generateAesKey(hsResult.randomBytes, secret)
            val t2cKey = CryptoUtils.generateAesKey(secret, hsResult.randomBytes)
            val c2tIv = hsResult.randomBytes.copyOfRange(0, 4) + secret.copyOfRange(0, 12)
            val t2cIv = secret.copyOfRange(0, 4) + hsResult.randomBytes.copyOfRange(0, 12)

            // TCP -> upstream: decrypt from client, encrypt for TG
            val encForUp = CryptoUtils.createAesCtr(t2cKey, t2cIv)
            // upstream -> TCP: decrypt from TG, encrypt for client
            val decFromUp = CryptoUtils.createAesCtrDecrypt(t2cKey, t2cIv)
            val encForClient = CryptoUtils.createAesCtr(c2tKey, c2tIv)

            val dcId = hsResult.dcId
            val ip = config.dcIpMap[dcId] ?: DC_IPS[dcId]
            if (ip == null) {
                onError("No IP for DC $dcId")
                return
            }

            // === Try WebSocket ===
            val domains = WebSocketClient.getWsDomains(dcId, hsResult.isMedia)
            var ws: WebSocketClient? = null

            for (url in domains) {
                Log.d(TAG, "Trying WS: $url")
                val c = WebSocketClient(
                    onMessage = { data ->
                        try {
                            val plain = decFromUp.update(data)
                            val forClient = encForClient.update(plain)
                            synchronized(cOut) {
                                cOut.write(forClient)
                                cOut.flush()
                            }
                            totalTx += data.size
                            onTrafficUpdate(totalRx, totalTx)
                        } catch (e: Exception) {
                            Log.e(TAG, "WS->TCP error", e)
                            alive.set(false)
                        }
                    },
                    onClose = { code, reason ->
                        Log.d(TAG, "WS closed $code $reason")
                        alive.set(false)
                    }
                )
                if (c.connect(url, 5)) {
                    ws = c
                    Log.d(TAG, "WS connected: $url")
                    onError("WS: $url")
                    break
                }
            }

            if (ws != null) {
                // Bridge TCP -> WS
                val buf = ByteArray(65536)
                try {
                    while (alive.get() && ws!!.isConnected()) {
                        val avail = cIn.available()
                        if (avail > 0) {
                            val toRead = minOf(avail, buf.size)
                            val n = cIn.read(buf, 0, toRead)
                            if (n <= 0) break
                            val enc = encForUp.update(buf, 0, n)
                            if (!ws!!.send(enc)) {
                                Log.w(TAG, "WS send failed")
                                break
                            }
                            totalRx += n
                            onTrafficUpdate(totalRx, totalTx)
                        } else {
                            Thread.sleep(5)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Bridge stopped: ${e.message}")
                }
                ws.close()
            } else {
                // TCP fallback
                Log.w(TAG, "TCP fallback to $ip:443")
                onError("TCP fallback: $ip")

                val remote = Socket()
                remote.connect(InetSocketAddress(ip, 443), 10000)
                val rIn = remote.getInputStream()
                val rOut = remote.getOutputStream()

                val t1 = Thread {
                    val buf = ByteArray(65536)
                    while (alive.get()) {
                        val n = cIn.read(buf)
                        if (n <= 0) break
                        val enc = encForUp.update(buf, 0, n)
                        rOut.write(enc)
                        rOut.flush()
                        totalRx += n
                        onTrafficUpdate(totalRx, totalTx)
                    }
                    alive.set(false)
                }

                val t2 = Thread {
                    val buf = ByteArray(65536)
                    while (alive.get()) {
                        val n = rIn.read(buf)
                        if (n <= 0) break
                        val plain = decFromUp.update(buf, 0, n)
                        val forClient = encForClient.update(plain)
                        synchronized(cOut) {
                            cOut.write(forClient)
                            cOut.flush()
                        }
                        totalTx += n
                        onTrafficUpdate(totalRx, totalTx)
                    }
                    alive.set(false)
                }

                t1.start()
                t2.start()
                t1.join()
                t2.join()
                remote.close()
            }

            Log.d(TAG, "Session done: rx=$totalRx tx=$totalTx")

        } catch (e: Exception) {
            Log.e(TAG, "Fatal", e)
            onError("Fatal: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            onConnectionUpdate(-1)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val r = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            r[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return r
    }
}
