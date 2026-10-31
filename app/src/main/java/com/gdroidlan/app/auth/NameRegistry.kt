package com.gdroidlan.app.auth

import com.gdroidlan.app.net.MqttLightClient
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Registro descentralizado de nomes únicos via MQTT TLS Retained (sem usar GitHub nem servidor pago):
 * - Associa cada nome normalizado (canonicalNameKey) ao accountUid único da instalação.
 * - Se outro usuário já registrou o mesmo nome, retorna Taken ("Já existe um usuário com esse nome.").
 * - Ao renomear no Perfil, libera o nome antigo (retained vazio) e registra o novo nome.
 */
object NameRegistry {

    private const val TOPIC_PREFIX = "glan/v31/names/"
    private const val HEADER_NAME_CLAIM: Byte = 0x4E // 'N'

    sealed class ClaimResult {
        object Claimed : ClaimResult()
        object AlreadyTaken : ClaimResult()
    }

    private val rng = SecureRandom()

    private val claimKeySpec: SecretKeySpec by lazy {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("gdroidlan-name-claim-v31".toByteArray(Charsets.UTF_8))
            .copyOf(16)
        val spec = PBEKeySpec("GLANUNIQUENAMESV31".toCharArray(), salt, 8_000, 128)
        try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(skf.generateSecret(spec).encoded, "AES")
        } catch (_: Exception) {
            val fb = MessageDigest.getInstance("SHA-256")
                .digest("gdroidlan-name-claim-v31:GLANUNIQUENAMESV31".toByteArray(Charsets.UTF_8))
            SecretKeySpec(fb.copyOf(16), "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun topicForDisplayName(displayName: String): String? {
        val key = NameModerationFilter.canonicalNameKey(displayName)
        if (key.length < 2) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("glan-name-slot-v31:$key").toByteArray(Charsets.UTF_8))
        val hash16 = digest.take(8).joinToString("") { "%02x".format(it) }
        return "$TOPIC_PREFIX$hash16"
    }

    fun buildEncryptedClaim(displayName: String, accountUid: Long): ByteArray {
        val key = NameModerationFilter.canonicalNameKey(displayName)
        val plain = JSONObject().apply {
            put("uid", accountUid)
            put("key", key)
            put("ts", System.currentTimeMillis())
        }.toString().toByteArray(Charsets.UTF_8)

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, claimKeySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(HEADER_NAME_CLAIM))
        val enc = cipher.doFinal(plain)

        val out = ByteArray(1 + 12 + enc.size)
        out[0] = HEADER_NAME_CLAIM
        System.arraycopy(iv, 0, out, 1, 12)
        System.arraycopy(enc, 0, out, 13, enc.size)
        return out
    }

    fun parseClaimOwnerUid(packet: ByteArray, expectedCanonicalKey: String): Long? {
        if (packet.isEmpty()) return 0L
        if (packet.size < 1 + 12 + 17) return null
        if (packet[0] != HEADER_NAME_CLAIM) return null
        return try {
            val iv = packet.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, claimKeySpec, GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(HEADER_NAME_CLAIM))
            val plain = cipher.doFinal(packet, 13, packet.size - 13)
            val j = JSONObject(String(plain, Charsets.UTF_8))
            val key = j.optString("key", "")
            if (key != expectedCanonicalKey) return null
            j.optLong("uid", 0L).takeIf { it != 0L }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Verifica e registra um nome em conexão dedicada rápida (usado na criação inicial da conta em AuthActivity).
     */
    fun checkAndClaimStandalone(
        newDisplayName: String,
        accountUid: Long,
        waitMs: Long = 850L,
        onComplete: (ClaimResult) -> Unit
    ) {
        val targetTopic = topicForDisplayName(newDisplayName)
        val canonicalKey = NameModerationFilter.canonicalNameKey(newDisplayName)
        if (targetTopic == null || canonicalKey.length < 2) {
            onComplete(ClaimResult.Claimed)
            return
        }

        Thread({
            val connLatch = CountDownLatch(1)
            val claimLatch = CountDownLatch(1)
            val ownerRef = AtomicLong(0L)

            val tempMqtt = MqttLightClient(
                socketProtector = { true },
                onPacketReceived = { topic, payload ->
                    if (topic == targetTopic) {
                        val owner = parseClaimOwnerUid(payload, canonicalKey)
                        if (owner != null && owner != 0L) {
                            ownerRef.set(owner)
                            claimLatch.countDown()
                        }
                    }
                },
                onConnectionStateChanged = { connected, _ ->
                    if (connected) connLatch.countDown()
                }
            )

            try {
                tempMqtt.start(listOf(targetTopic))
                if (connLatch.await(1_600L, TimeUnit.MILLISECONDS)) {
                    claimLatch.await(waitMs, TimeUnit.MILLISECONDS)
                    val existingOwner = ownerRef.get()
                    if (existingOwner != 0L && existingOwner != accountUid) {
                        onComplete(ClaimResult.AlreadyTaken)
                        return@Thread
                    }
                    val claimPkt = buildEncryptedClaim(newDisplayName, accountUid)
                    tempMqtt.publish(targetTopic, claimPkt, retain = true)
                    try {
                        Thread.sleep(150L)
                    } catch (_: InterruptedException) { }
                }
                onComplete(ClaimResult.Claimed)
            } catch (_: Exception) {
                onComplete(ClaimResult.Claimed)
            } finally {
                tempMqtt.stop()
            }
        }, "glan-auth-name-check").apply { isDaemon = true }.start()
    }
}
