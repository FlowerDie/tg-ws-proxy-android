package com.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "tg_ws_proxy_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.tgwsproxy.ACTION_START"
        const val ACTION_STOP = "com.tgwsproxy.ACTION_STOP"
        const val ACTION_STATUS = "com.tgwsproxy.ACTION_STATUS"
        const val ACTION_ERROR = "com.tgwsproxy.ACTION_ERROR"

        const val EXTRA_STATUS = "status"
        const val EXTRA_BYTES_READ = "bytes_read"
        const val EXTRA_BYTES_WRITTEN = "bytes_written"
        const val EXTRA_CONNECTIONS = "connections"
        const val EXTRA_ERROR = "error_msg"

        const val STATUS_STOPPED = 0
        const val STATUS_RUNNING = 1
        const val STATUS_ERROR = 2

        private var instance: ProxyService? = null

        fun isRunning(): Boolean = instance != null
    }

    private val isRunning = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private var totalBytesRead = 0L
    private var totalBytesWritten = 0L

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        if (isRunning.get()) {
            Log.d(TAG, "Proxy already running")
            return
        }

        try {
            val config = ProxyConfig(
                host = ConfigManager.getHost(this),
                port = ConfigManager.getPort(this),
                secret = ConfigManager.getSecret(this),
                dcIpMap = buildMap {
                    ConfigManager.getDc2Ip(this@ProxyService)?.let { put(2, it) }
                    ConfigManager.getDc4Ip(this@ProxyService)?.let { put(4, it) }
                }
            )

            Log.d(TAG, "Starting proxy on ${config.host}:${config.port}")

            serverSocket = ServerSocket(config.port, 50, java.net.InetAddress.getByName(config.host))
            isRunning.set(true)

            startForeground(NOTIFICATION_ID, createNotification(config.port))

            acceptThread = Thread {
                acceptClients(config)
            }.apply {
                name = "Proxy-Accept"
                isDaemon = true
                start()
            }

            broadcastStatus(STATUS_RUNNING)
            Log.d(TAG, "Proxy started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            isRunning.set(false)
            broadcastStatus(STATUS_ERROR)
            stopSelf()
        }
    }

    private fun acceptClients(config: ProxyConfig) {
        val handler = MTProtoHandler()

        while (isRunning.get() && !serverSocket?.isClosed!!) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                activeConnections.incrementAndGet()
                broadcastStatus(STATUS_RUNNING)

                Thread {
                    var lastError: String? = null
                    try {
                        handler.handleClientConnection(
                            clientSocket = clientSocket,
                            config = config,
                            onTrafficUpdate = { read, written ->
                                totalBytesRead += read
                                totalBytesWritten += written
                            },
                            onConnectionUpdate = { delta ->
                                activeConnections.addAndGet(delta)
                            },
                            onError = { msg ->
                                lastError = msg
                                Log.e(TAG, "Client error: $msg")
                                broadcastError(msg)
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in client handler", e)
                        broadcastError(e.message ?: "Unknown error")
                    } finally {
                        try {
                            clientSocket.close()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }.apply {
                    name = "Client-${activeConnections.get()}"
                    isDaemon = true
                    start()
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        }
    }

    private fun stopProxy() {
        if (!isRunning.get()) return

        Log.d(TAG, "Stopping proxy")
        isRunning.set(false)

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }

        acceptThread?.interrupt()
        acceptThread = null
        serverSocket = null

        broadcastStatus(STATUS_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Proxy stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int): Notification {
        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun broadcastStatus(status: Int) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_BYTES_READ, totalBytesRead)
            putExtra(EXTRA_BYTES_WRITTEN, totalBytesWritten)
            putExtra(EXTRA_CONNECTIONS, activeConnections.get())
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(msg: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR, msg)
        }
        sendBroadcast(intent)
    }
}
