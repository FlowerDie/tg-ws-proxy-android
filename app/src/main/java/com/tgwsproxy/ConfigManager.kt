package com.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object ConfigManager {
    private const val PREFS_NAME = "tg_ws_proxy_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_SECRET = "secret"
    private const val KEY_DC2 = "dc2"
    private const val KEY_DC4 = "dc4"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getHost(context: Context): String {
        return getPrefs(context).getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"
    }

    fun setHost(context: Context, host: String) {
        getPrefs(context).edit().putString(KEY_HOST, host).apply()
    }

    fun getPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_PORT, 1443)
    }

    fun setPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    fun getSecret(context: Context): String {
        val saved = getPrefs(context).getString(KEY_SECRET, null)
        return if (saved.isNullOrBlank()) {
            val newSecret = generateSecret()
            setSecret(context, newSecret)
            newSecret
        } else {
            saved
        }
    }

    fun setSecret(context: Context, secret: String) {
        getPrefs(context).edit().putString(KEY_SECRET, secret).apply()
    }

    fun getDc2Ip(context: Context): String? {
        return getPrefs(context).getString(KEY_DC2, null)?.takeIf { it.isNotBlank() }
    }

    fun setDc2Ip(context: Context, ip: String?) {
        getPrefs(context).edit().putString(KEY_DC2, ip ?: "").apply()
    }

    fun getDc4Ip(context: Context): String? {
        return getPrefs(context).getString(KEY_DC4, null)?.takeIf { it.isNotBlank() }
    }

    fun setDc4Ip(context: Context, ip: String?) {
        getPrefs(context).edit().putString(KEY_DC4, ip ?: "").apply()
    }

    fun generateSecret(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun validateSecret(secret: String): Boolean {
        return secret.length == 32 && secret.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String,
    val dcIpMap: Map<Int, String> = emptyMap()
)
