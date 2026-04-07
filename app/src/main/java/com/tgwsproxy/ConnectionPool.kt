package com.tgwsproxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ConnectionPool(
    private val poolSize: Int = 4,
    private val config: ProxyConfig
) {
    companion object {
        private const val TAG = "ConnectionPool"
    }

    // Key: "dcId-mediaType" e.g., "2-false" or "4-true"
    private val pools = ConcurrentHashMap<String, MutableList<WsConnection>>()
    private val lock = ReentrantLock()
    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    fun getConnection(dcId: Int, isMedia: Boolean): WsConnection? {
        val key = "$dcId-$isMedia"

        lock.withLock {
            val pool = pools[key]
            if (pool != null && pool.isNotEmpty()) {
                val conn = pool.removeAt(pool.size - 1)
                if (conn.client.isConnected()) {
                    hits.incrementAndGet()
                    Log.d(TAG, "Pool hit for $key (remaining: ${pool.size})")
                    return conn
                } else {
                    Log.d(TAG, "Pool miss for $key (connection closed)")
                }
            }
        }

        misses.incrementAndGet()
        Log.d(TAG, "Pool miss for $key, creating new connection")

        // Try to connect to any of the WebSocket domains
        val domains = WebSocketClient.getWsDomains(dcId, isMedia)
        for (url in domains) {
            val client = WebSocketClient(
                onMessage = { /* Messages handled by bridge */ },
                onClose = { _, _ -> /* Handle close */ }
            )

            if (client.connect(url)) {
                val conn = WsConnection(client, dcId, isMedia)
                Log.d(TAG, "New WebSocket connection established: $url")
                return conn
            }
        }

        Log.e(TAG, "Failed to connect to any WebSocket endpoint for DC $dcId")
        return null
    }

    fun releaseConnection(conn: WsConnection) {
        if (!conn.client.isConnected()) return

        val key = "${conn.dcId}-${conn.isMedia}"
        lock.withLock {
            val pool = pools.getOrPut(key) { mutableListOf() }
            if (pool.size < poolSize) {
                pool.add(conn)
                Log.d(TAG, "Connection returned to pool $key (size: ${pool.size})")
            } else {
                conn.client.close()
                Log.d(TAG, "Pool full for $key, closing connection")
            }
        }
    }

    fun prewarm() {
        // Pre-warm connections for known DCs
        val dcIds = listOf(2, 4) // DC 2 and DC 4 are main
        for (dcId in dcIds) {
            for (isMedia in listOf(false, true)) {
                Thread {
                    val domains = WebSocketClient.getWsDomains(dcId, isMedia)
                    for (url in domains) {
                        val client = WebSocketClient(onMessage = {})
                        if (client.connect(url)) {
                            val conn = WsConnection(client, dcId, isMedia)
                            releaseConnection(conn)
                            break
                        }
                    }
                }.start()
            }
        }
    }

    fun closeAll() {
        lock.withLock {
            for ((key, pool) in pools) {
                for (conn in pool) {
                    conn.client.close()
                }
                pool.clear()
            }
            pools.clear()
        }
    }

    fun getStats(): PoolStats {
        return PoolStats(
            hits = hits.get(),
            misses = misses.get(),
            totalConnections = pools.values.sumOf { it.size }
        )
    }

    data class WsConnection(
        val client: WebSocketClient,
        val dcId: Int,
        val isMedia: Boolean
    )

    data class PoolStats(
        val hits: Int,
        val misses: Int,
        val totalConnections: Int
    )
}
