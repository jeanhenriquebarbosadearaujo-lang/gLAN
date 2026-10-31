package com.gdroidlan.app.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Núcleo criptográfico endurecido do gLAN v2.6:
 * - Derivação PBKDF2-HMAC-SHA256 + AES-128-GCM autenticado com janela anti-replay.
 * - Identidade assimétrica efêmera EC P-256 (ECDSA SHA256withECDSA) para impedir
 *   falsificação de salas públicas, expulsões falsas ou encerramento forjado.
 * - Acordo de chaves ECDH P-256 por participante (Forward Secrecy par-a-par), isolando
 *   criptograficamente o tráfego de cada jogador mesmo em salas públicas.
 */
object RoomCrypto {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val HEADER_ROOM_CIPHER: Byte = 0x47 // 'G'
    const val HEADER_PAIRWISE_CIPHER: Byte = 0x48     // 'H'
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

    /**
     * Remove caracteres de controle, quebras de linha, caracteres invisíveis e overrides RTL
     * para evitar ataques visuais em nomes de jogadores e títulos de salas públicas.
     */
    fun cleanSafeText(raw: String, maxLen: Int): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            val unsafeInvisible = ch.isISOControl() ||
                code in 0x200B..0x200F ||
                code in 0x202A..0x202E ||
                code in 0x2060..0x206F ||
                code == 0xFEFF
            if (unsafeInvisible) {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(maxLen)
    }

    fun deriveTopicHash(roomCode: String): String {
        val clean = normalizeRoomCode(roomCode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("gdroidlan-room-topic-v26:$clean").toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun derivePbkdf2AesKey(secretText: String, contextSalt: String): SecretKeySpec {
        val saltBytes = MessageDigest.getInstance("SHA-256")
            .digest(contextSalt.toByteArray(Charsets.UTF_8))
            .copyOf(16)
        val spec = PBEKeySpec(secretText.toCharArray(), saltBytes, 8_000, 128)
        return try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(skf.generateSecret(spec).encoded, "AES")
        } catch (_: Exception) {
            val fallback = MessageDigest.getInstance("SHA-256")
                .digest(("$contextSalt:$secretText").toByteArray(Charsets.UTF_8))
            SecretKeySpec(fallback.copyOf(16), "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Janela deslizante de 512 pacotes por instância emissora para bloquear ataques de Replay
     * sem descartar pacotes UDP fora de ordem durante picos de oscilação no 4G/Wi-Fi.
     */
    private class ReplayWindow {
        private var highestSeq: Long = 0L
        private val seenRing = LongArray(512)

        @Synchronized
        fun accept(seq: Long): Boolean {
            if (seq <= 0L) return false
            if (highestSeq - seq >= 512L) return false
            val slot = (seq % 512L).toInt()
            if (seenRing[slot] == seq) return false
            seenRing[slot] = seq
            if (seq > highestSeq) {
                highestSeq = seq
            }
            return true
        }
    }

    /**
     * Cifra simétrica derivada do código da sala com proteção anti-replay.
     * Formato: [0x47 ('G')][4 bytes salt][8 bytes seq][ciphertext + 16 bytes GCM tag]
     */
    class SessionCipher(roomCode: String) {
        private val keySpec: SecretKeySpec
        private val instanceSalt = ByteArray(4).also { rng.nextBytes(it) }
        private val nonceCounter = AtomicLong(1L)
        private val replayBySender = ConcurrentHashMap<Int, ReplayWindow>()

        init {
            val clean = normalizeRoomCode(roomCode)
            keySpec = derivePbkdf2AesKey(clean, "gdroidlan-room-aes-v26:$clean")
        }

        fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray {
            val iv = ByteArray(12)
            System.arraycopy(instanceSalt, 0, iv, 0, 4)
            val seq = nonceCounter.getAndIncrement()
            ByteBuffer.wrap(iv, 4, 8).putLong(seq)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(HEADER_ROOM_CIPHER))
            val encrypted = cipher.doFinal(plain, offset, length)

            val out = ByteArray(1 + 12 + encrypted.size)
            out[0] = HEADER_ROOM_CIPHER
            System.arraycopy(iv, 0, out, 1, 12)
            System.arraycopy(encrypted, 0, out, 13, encrypted.size)
            return out
        }

        fun decrypt(packet: ByteArray, offset: Int = 0, length: Int = packet.size): ByteArray? {
            if (length < 1 + 12 + 17) return null
            if (packet[offset] != HEADER_ROOM_CIPHER) return null
            return try {
                val iv = packet.copyOfRange(offset + 1, offset + 13)
                val senderSaltId = ByteBuffer.wrap(iv, 0, 4).int
                val seq = ByteBuffer.wrap(iv, 4, 8).long
                if (seq <= 0L) return null

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                cipher.updateAAD(byteArrayOf(HEADER_ROOM_CIPHER))
                val plain = cipher.doFinal(packet, offset + 13, length - 13)

                if (replayBySender.size > 256 && !replayBySender.containsKey(senderSaltId)) {
                    replayBySender.clear()
                }
                val window = replayBySender.getOrPut(senderSaltId) { ReplayWindow() }
                if (!window.accept(seq)) return null
                plain
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Cifra par-a-par derivada por ECDH P-256 exclusiva entre Anfitrião e um nó específico.
     * Vincula criptograficamente o senderNodeId no AAD do AES-GCM + janela anti-replay.
     * Formato: [0x48 ('H')][1 byte senderNodeId][4 bytes salt][8 bytes seq][ciphertext + GCM tag]
     */
    class PairwiseCipher(rawKeyMaterial: ByteArray) {
        private val keySpec = SecretKeySpec(rawKeyMaterial.copyOf(16), "AES")
        private val instanceSalt = ByteArray(4).also { rng.nextBytes(it) }
        private val nonceCounter = AtomicLong(1L)
        private val replayBySender = ConcurrentHashMap<Int, ReplayWindow>()

        fun encrypt(senderNodeId: Int, plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray {
            val iv = ByteArray(12)
            System.arraycopy(instanceSalt, 0, iv, 0, 4)
            val seq = nonceCounter.getAndIncrement()
            ByteBuffer.wrap(iv, 4, 8).putLong(seq)
            val nodeByte = (senderNodeId and 0xFF).toByte()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(HEADER_PAIRWISE_CIPHER, nodeByte))
            val encrypted = cipher.doFinal(plain, offset, length)

            val out = ByteArray(2 + 12 + encrypted.size)
            out[0] = HEADER_PAIRWISE_CIPHER
            out[1] = nodeByte
            System.arraycopy(iv, 0, out, 2, 12)
            System.arraycopy(encrypted, 0, out, 14, encrypted.size)
            return out
        }

        fun decrypt(
            expectedSenderNodeId: Int,
            packet: ByteArray,
            offset: Int = 0,
            length: Int = packet.size
        ): ByteArray? {
            if (length < 2 + 12 + 17) return null
            if (packet[offset] != HEADER_PAIRWISE_CIPHER) return null
            val wireSender = packet[offset + 1].toInt() and 0xFF
            if (wireSender != expectedSenderNodeId) return null

            return try {
                val iv = packet.copyOfRange(offset + 2, offset + 14)
                val senderSaltId = ByteBuffer.wrap(iv, 0, 4).int
                val seq = ByteBuffer.wrap(iv, 4, 8).long
                if (seq <= 0L) return null

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                cipher.updateAAD(byteArrayOf(HEADER_PAIRWISE_CIPHER, packet[offset + 1]))
                val plain = cipher.doFinal(packet, offset + 14, length - 14)

                val window = replayBySender.getOrPut(senderSaltId) { ReplayWindow() }
                if (!window.accept(seq)) return null
                plain
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Identidade assimétrica efêmera EC P-256 por sessão para assinaturas ECDSA e ECDH.
     */
    class IdentityKeyBox {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(256, rng)
        }.generateKeyPair()

        val publicKeyBytes: ByteArray = keyPair.public.encoded
        val publicKeyBase64: String = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)

        fun sign(data: ByteArray): ByteArray {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(keyPair.private)
            sig.update(data)
            return sig.sign()
        }

        fun signToBase64(data: ByteArray): String =
            Base64.encodeToString(sign(data), Base64.NO_WRAP)

        fun derivePairwiseCipher(
            remotePublicKey: PublicKey,
            roomCode: String,
            clientNodeId: Int
        ): PairwiseCipher {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(remotePublicKey, true)
            val sharedSecret = ka.generateSecret()

            val md = MessageDigest.getInstance("SHA-256")
            md.update("gdroidlan-ecdh-v26:".toByteArray(Charsets.UTF_8))
            md.update(normalizeRoomCode(roomCode).toByteArray(Charsets.UTF_8))
            md.update(byteArrayOf((clientNodeId and 0xFF).toByte()))
            md.update(sharedSecret)
            return PairwiseCipher(md.digest())
        }
    }

    fun decodeEcPublicKey(base64Key: String): PublicKey? {
        if (base64Key.isBlank() || base64Key.length > 256) return null
        return try {
            val raw = Base64.decode(base64Key, Base64.NO_WRAP)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun verifySignature(publicKey: PublicKey, data: ByteArray, signatureBase64: String): Boolean {
        if (signatureBase64.isBlank() || signatureBase64.length > 256) return false
        return try {
            val sigBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            val ver = Signature.getInstance("SHA256withECDSA")
            ver.initVerify(publicKey)
            ver.update(data)
            ver.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }
}
