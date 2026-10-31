package com.gdroidlan.app.update

import com.gdroidlan.app.crypto.RoomCrypto
import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verificador automático descentralizado de novas versões (100% via MQTT + P2P Gossip,
 * sem conectar ao GitHub no app e imune a falsificação graças à assinatura ECDSA P-256).
 */
object UpdateNotifier {

    const val VERSION_TOPIC = "glan/v30/version/latest"
    private const val HEADER_VERSION_ENVELOPE: Byte = 0x56 // 'V'

    /**
     * Chave pública oficial ECDSA P-256 do criador (somente verificação).
     * A chave privada correspondente existe exclusivamente nos Secrets criptografados de build.
     */
    private const val OFFICIAL_PUB_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEicCu5ykpPzbUhyb9YrZPH9vIdC5mjp7FvfOY4zXfs9LEtWLsxBnwmowu87OGjBmmxEkRGRV9GvuUNs7y5stDnQ=="

    data class VerifiedRelease(
        val versionCode: Int,
        val versionName: String,
        val signatureB64: String
    )

    private val rng = SecureRandom()
    private val officialPublicKey: PublicKey? by lazy {
        RoomCrypto.decodeEcPublicKey(OFFICIAL_PUB_KEY_B64)
    }

    private val envelopeKeySpec: SecretKeySpec by lazy {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("gdroidlan-version-envelope-v30".toByteArray(Charsets.UTF_8))
            .copyOf(16)
        val spec = PBEKeySpec("GLANOFFICIALVERSIONV30".toCharArray(), salt, 8_000, 128)
        try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(skf.generateSecret(spec).encoded, "AES")
        } catch (_: Exception) {
            val fb = MessageDigest.getInstance("SHA-256")
                .digest("gdroidlan-version-envelope-v30:GLANOFFICIALVERSIONV30".toByteArray(Charsets.UTF_8))
            SecretKeySpec(fb.copyOf(16), "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private val highestSeenCode = AtomicInteger(OfficialVersionCert.BUILT_VERSION_CODE)
    private val retainedRefreshedThisProcess = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(VerifiedRelease) -> Unit>()

    @Volatile
    var latestNewerRelease: VerifiedRelease? = null
        private set

    fun canonicalSignPayload(versionCode: Int, versionName: String): ByteArray =
        "glan-official-release|$versionCode|$versionName".toByteArray(Charsets.UTF_8)

    fun verifyFields(versionCode: Int, rawVersionName: String, sigB64: String): VerifiedRelease? {
        if (versionCode < 1 || versionCode > 100_000) return null
        val cleanName = RoomCrypto.cleanSafeText(rawVersionName, 16)
        if (cleanName.isBlank() || !Regex("^[0-9]+(\\.[0-9]+){0,3}$").matches(cleanName)) return null
        val pub = officialPublicKey ?: return null
        if (!RoomCrypto.verifySignature(pub, canonicalSignPayload(versionCode, cleanName), sigB64)) {
            return null
        }
        return VerifiedRelease(versionCode, cleanName, sigB64.trim())
    }

    fun getBuiltInVerifiedRelease(): VerifiedRelease? {
        return verifyFields(
            OfficialVersionCert.BUILT_VERSION_CODE,
            OfficialVersionCert.BUILT_VERSION_NAME,
            OfficialVersionCert.BUILT_RELEASE_SIG_B64
        )
    }

    fun buildEncryptedAnnouncement(rel: VerifiedRelease): ByteArray {
        val plain = JSONObject().apply {
            put("vCode", rel.versionCode)
            put("vName", rel.versionName)
            put("sig", rel.signatureB64)
        }.toString().toByteArray(Charsets.UTF_8)

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, envelopeKeySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(HEADER_VERSION_ENVELOPE))
        val enc = cipher.doFinal(plain)

        val out = ByteArray(1 + 12 + enc.size)
        out[0] = HEADER_VERSION_ENVELOPE
        System.arraycopy(iv, 0, out, 1, 12)
        System.arraycopy(enc, 0, out, 13, enc.size)
        return out
    }

    fun parseEncryptedAnnouncement(packet: ByteArray): VerifiedRelease? {
        if (packet.size < 1 + 12 + 17) return null
        if (packet[0] != HEADER_VERSION_ENVELOPE) return null
        return try {
            val iv = packet.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, envelopeKeySpec, GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(HEADER_VERSION_ENVELOPE))
            val plain = cipher.doFinal(packet, 13, packet.size - 13)
            val j = JSONObject(String(plain, Charsets.UTF_8))
            verifyFields(
                j.optInt("vCode", 0),
                j.optString("vName", ""),
                j.optString("sig", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun shouldPublishSelfRetainedRefresh(myRelease: VerifiedRelease): Boolean {
        if (myRelease.versionCode < highestSeenCode.get()) return false
        return retainedRefreshedThisProcess.compareAndSet(false, true)
    }

    fun onVerifiedReleaseObserved(rel: VerifiedRelease, installedVersionCode: Int) {
        while (true) {
            val prev = highestSeenCode.get()
            if (rel.versionCode <= prev) break
            if (highestSeenCode.compareAndSet(prev, rel.versionCode)) break
        }

        if (rel.versionCode > installedVersionCode) {
            val currentNewer = latestNewerRelease
            if (currentNewer == null || rel.versionCode > currentNewer.versionCode) {
                latestNewerRelease = rel
                for (l in listeners) {
                    try {
                        l(rel)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    fun addListener(l: (VerifiedRelease) -> Unit) {
        listeners.add(l)
        latestNewerRelease?.let { l(it) }
    }

    fun removeListener(l: (VerifiedRelease) -> Unit) {
        listeners.remove(l)
    }
}
