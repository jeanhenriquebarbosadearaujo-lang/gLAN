package com.gdroidlan.app.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gera códigos de sala curtos e deriva criptografia ponta a ponta AES-128-GCM
 * e identificadores de canal exclusivos a partir do código da sala.
 */
object RoomCrypto {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val rng = SecureRandom()

    fun generateRoomCode(length: Int = 6): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(ALPHABET[rng.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    fun normalizeRoomCode(raw: String): String {
        return raw.uppercase()
            .replace(Regex("[^A-Z0-9]"), "")
            .replace('O', '0')
            .trim()
    }

    fun deriveTopicHash(roomCode: String): String {
        val clean = normalizeRoomCode(roomCode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("gdroidlan-room-topic-v1:$clean").toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    class SessionCipher(roomCode: String) {
        private val keySpec: SecretKeySpec
        private val instanceSalt = ByteArray(4).also { rng.nextBytes(it) }
        private val nonceCounter = AtomicLong(1L)

        init {
            val clean = normalizeRoomCode(roomCode)
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(("gdroidlan-aes128gcm-v1:$clean").toByteArray(Charsets.UTF_8))
            keySpec = SecretKeySpec(hash.copyOf(16), "AES")
        }

        /**
         * Formato do pacote criptografado:
         * [0x47 ('G')][12 bytes IV][ciphertext + 16 bytes GCM tag]
         */
        fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray {
            val iv = ByteArray(12)
            System.arraycopy(instanceSalt, 0, iv, 0, 4)
            val seq = nonceCounter.getAndIncrement()
            ByteBuffer.wrap(iv, 4, 8).putLong(seq)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain, offset, length)

            val out = ByteArray(1 + 12 + encrypted.size)
            out[0] = 0x47
            System.arraycopy(iv, 0, out, 1, 12)
            System.arraycopy(encrypted, 0, out, 13, encrypted.size)
            return out
        }

        fun decrypt(packet: ByteArray, offset: Int = 0, length: Int = packet.size): ByteArray? {
            if (length < 1 + 12 + 17) return null
            if (packet[offset] != 0x47.toByte()) return null
            return try {
                val iv = packet.copyOfRange(offset + 1, offset + 13)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                cipher.doFinal(packet, offset + 13, length - 13)
            } catch (_: Exception) {
                null
            }
        }
    }
}
