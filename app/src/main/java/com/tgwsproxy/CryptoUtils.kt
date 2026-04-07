package com.tgwsproxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val HANDSHAKE_LEN = 64
    private const val AES_KEY_LEN = 32
    private const val AES_IV_LEN = 16

    // Reserved bytes to detect non-MTProto connections
    private val RESERVED_FIRST_BYTES: Set<Byte> = setOf(
        0x16.toByte(), 0x80.toByte(), 0x7f.toByte()
    )

    private val RESERVED_STARTS: List<ByteArray> = listOf(
        byteArrayOf(0x50.toByte(), 0x4f, 0x53, 0x54), // POST
        byteArrayOf(0x47.toByte(), 0x45, 0x54, 0x20), // GET 
        byteArrayOf(0x48.toByte(), 0x45, 0x41, 0x44), // HEAD
        byteArrayOf(0x4f.toByte(), 0x50, 0x54, 0x49), // OPTI
        byteArrayOf(0x43.toByte(), 0x4f, 0x4e, 0x4e), // CONN
        byteArrayOf(0x54.toByte(), 0x52, 0x41, 0x43), // TRAC
        byteArrayOf(0x44.toByte(), 0x45, 0x4c, 0x45), // DELE
        byteArrayOf(0x50.toByte(), 0x55, 0x54, 0x20), // PUT
        byteArrayOf(0x50.toByte(), 0x41, 0x54, 0x43), // PATC
    )

    /**
     * Проверяет является ли пакет MTProto handshake
     */
    fun isMtProtoHandshake(data: ByteArray): Boolean {
        if (data.size < HANDSHAKE_LEN) return false

        // Check reserved first bytes
        if (data[0] in RESERVED_FIRST_BYTES) return false

        // Check reserved starts
        for (start in RESERVED_STARTS) {
            if (data.size >= start.size) {
                var match = true
                for (i in start.indices) {
                    if (data[i] != start[i]) {
                        match = false
                        break
                    }
                }
                if (match) return false
            }
        }

        return true
    }

    /**
     * Парсит handshake пакет MTProto
     * Возвращает: (dcId, isMedia, randomBytes)
     */
    fun parseHandshake(handshake: ByteArray, secretBytes: ByteArray): HandshakeResult? {
        if (handshake.size != HANDSHAKE_LEN) return null

        // Extract random bytes (first 32 bytes after XOR with secret)
        val randomBytes = ByteArray(32)
        for (i in 0 until 32) {
            randomBytes[i] = (handshake[i].toInt() xor secretBytes[i].toInt()).toByte()
        }

        // DC ID is in bytes 56-57 (big endian, short)
        val dcIdBuf = ByteBuffer.wrap(handshake, 56, 2).order(ByteOrder.BIG_ENDIAN)
        val dcId = dcIdBuf.short.toInt() and 0xFFFF

        // Check if media (bit flag in byte 60)
        val isMedia = (handshake[60].toInt() and 0x80) != 0

        return HandshakeResult(
            dcId = dcId,
            isMedia = isMedia,
            randomBytes = randomBytes,
            handshake = handshake
        )
    }

    /**
     * Генерирует AES ключ из prekey и secret
     */
    fun generateAesKey(prekey: ByteArray, secret: ByteArray): ByteArray {
        val combined = prekey + secret
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(combined)
    }

    /**
     * Создаёт AES-CTR шифратор
     */
    fun createAesCtr(key: ByteArray, iv: ByteArray): Cipher {
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher
    }

    /**
     * Создаёт AES-CTR дешифратор
     */
    fun createAesCtrDecrypt(key: ByteArray, iv: ByteArray): Cipher {
        // CTR mode is symmetric
        return createAesCtr(key, iv)
    }

    /**
     * XOR маска для данных
     */
    fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in data.indices) {
            result[i] = (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return result
    }

    /**
     * Генерирует relay init пакет
     */
    fun generateRelayInit(
        randomBytes: ByteArray,
        dcEncryptKey: ByteArray,
        dcIv: ByteArray
    ): ByteArray {
        val init = ByteArray(64)
        randomBytes.copyInto(init)

        val cipher = createAesCtr(dcEncryptKey, dcIv)
        return cipher.doFinal(init)
    }

    /**
     * Создаёт SecureRandom
     */
    fun secureRandom(): SecureRandom = SecureRandom()

    /**
     * Генерирует случайные байты
     */
    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom().nextBytes(bytes)
        return bytes
    }

    data class HandshakeResult(
        val dcId: Int,
        val isMedia: Boolean,
        val randomBytes: ByteArray,
        val handshake: ByteArray
    )
}
