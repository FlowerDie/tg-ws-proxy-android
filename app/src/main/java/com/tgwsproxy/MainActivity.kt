package com.tgwsproxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tgwsproxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ProxyService.ACTION_STATUS) {
                updateStatus(
                    status = intent.getIntExtra(ProxyService.EXTRA_STATUS, ProxyService.STATUS_STOPPED),
                    bytesRead = intent.getLongExtra(ProxyService.EXTRA_BYTES_READ, 0),
                    bytesWritten = intent.getLongExtra(ProxyService.EXTRA_BYTES_WRITTEN, 0),
                    connections = intent.getIntExtra(ProxyService.EXTRA_CONNECTIONS, 0)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadSettings()
        updateStatus(if (ProxyService.isRunning()) ProxyService.STATUS_RUNNING else ProxyService.STATUS_STOPPED)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(ProxyService.ACTION_STATUS),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupUI() {
        // Toggle button
        binding.btnToggle.setOnClickListener {
            if (ProxyService.isRunning()) {
                stopProxy()
            } else {
                startProxy()
            }
        }

        // Save settings button
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        binding.etHost.setText(ConfigManager.getHost(this))
        binding.etPort.setText(ConfigManager.getPort(this).toString())
        binding.etSecret.setText(ConfigManager.getSecret(this))
        binding.etDc2.setText(ConfigManager.getDc2Ip(this) ?: "")
        binding.etDc4.setText(ConfigManager.getDc4Ip(this) ?: "")
    }

    private fun saveSettings() {
        val host = binding.etHost.text?.toString()?.trim() ?: "127.0.0.1"
        val portStr = binding.etPort.text?.toString()?.trim() ?: "1443"
        val secret = binding.etSecret.text?.toString()?.trim() ?: ""
        val dc2 = binding.etDc2.text?.toString()?.trim()
        val dc4 = binding.etDc4.text?.toString()?.trim()

        // Validate port
        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(this, "Неверный порт (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate secret
        if (secret.isNotBlank() && !ConfigManager.validateSecret(secret)) {
            Toast.makeText(this, "Секрет должен быть 32 hex символа (0-9, a-f)", Toast.LENGTH_SHORT).show()
            return
        }

        ConfigManager.setHost(this, host)
        ConfigManager.setPort(this, port)
        if (secret.isNotBlank()) ConfigManager.setSecret(this, secret)
        ConfigManager.setDc2Ip(this, dc2?.takeIf { it.isNotEmpty() })
        ConfigManager.setDc4Ip(this, dc4?.takeIf { it.isNotEmpty() })

        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
    }

    private fun startProxy() {
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }

        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Прокси запускается...", Toast.LENGTH_SHORT).show()
    }

    private fun stopProxy() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Прокси останавливается...", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(
        status: Int,
        bytesRead: Long = 0,
        bytesWritten: Long = 0,
        connections: Int = 0
    ) {
        val statusText = when (status) {
            ProxyService.STATUS_RUNNING -> getString(R.string.status_running)
            ProxyService.STATUS_ERROR -> getString(R.string.status_error)
            else -> getString(R.string.status_stopped)
        }

        binding.tvStatus.text = getString(R.string.proxy_status, statusText)
        binding.tvStatus.setTextColor(
            when (status) {
                ProxyService.STATUS_RUNNING -> getColorCompat(R.color.success)
                ProxyService.STATUS_ERROR -> getColorCompat(R.color.error)
                else -> getColorCompat(R.color.text_secondary)
            }
        )

        binding.tvConnections.text = getString(R.string.connections, connections)
        binding.tvTraffic.text = getString(
            R.string.traffic_stats,
            formatBytes(bytesRead),
            formatBytes(bytesWritten)
        )

        // Update button
        if (status == ProxyService.STATUS_RUNNING) {
            binding.btnToggle.text = getString(R.string.stop_proxy)
            binding.btnToggle.isEnabled = true
        } else {
            binding.btnToggle.text = getString(R.string.start_proxy)
            binding.btnToggle.isEnabled = status != ProxyService.STATUS_RUNNING
        }
    }

    private fun getColorCompat(colorRes: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startProxy()
            } else {
                Toast.makeText(this, "Разрешение на уведомления отклонено", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
