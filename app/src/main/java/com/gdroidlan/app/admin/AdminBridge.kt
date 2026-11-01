package com.gdroidlan.app.admin

import android.os.Build
import android.util.Base64
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.net.MqttLightClient
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Ponte segura gLAN v3.6 <-> gLAN Admin:
 * - Envio de Feedback com criptografia assimétrica ECIES P-256 + AES-128-GCM
 *   (apenas o app privado gLAN Admin possui a chave privada de leitura).
 * - Fila retida de 64 slots nos brokers TLS para que feedbacks enviados com o
 *   celular do administrador desligado cheguem assim que o gLAN Admin abrir.
 * - Verificação estrita ECDSA P-256 (SHA256withECDSA) de comunicados globais,
 *   bloqueios de contas (#TAG), encerramento administrativo de salas e respostas diretas.
 */
object AdminBridge {

    // Apenas chaves PÚBLICAS ficam no gLAN público; chaves privadas existem exclusivamente no gLAN Admin.
    private const val ADMIN_BOX_PUB_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEL520robKFh0FKpIZeDicZy0ipFAYrVt4nssMX2wvK+a2ugV+vQX3Osx3wjShRFyNSp6SGPIAVwM71EJ8etOuCg=="
    private const val ADMIN_SIGN_PUB_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELtB9eJFQ6yK0BkO64dVJFeaQcgWNshpCCgSeTQUojecWcl9ZPJApUxOiib2UWdFwolNogRJSMKKYQqa52zTa6w=="

    const val STATE_TOPIC = "glan/v36/admin/state"
    const val CMD_TOPIC = "glan/v36/admin/cmd"
    const val FEEDBACK_LIVE_TOPIC = "glan/v36/feedback/live"
    const val FEEDBACK_SLOT_COUNT = 64

    private const val HEADER_FEEDBACK_ECIES: Byte = 0x46 // 'F'
    private const val HEADER_ADMIN_ENVELOPE: Byte = 0x41 // 'A'
    private const val KILL_MEMORY_TTL_MS = 2L * 60L * 60L * 1000L
    private const val LIVE_CMD_MAX_SKEW_MS = 5L * 60L * 1000L

    private val rng = SecureRandom()

    private val adminEnvelopeKey: SecretKeySpec by lazy {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("glan-admin-envelope-v36".toByteArray(Charsets.UTF_8))
        SecretKeySpec(hash.copyOf(16), "AES")
    }

    private val adminBoxPublicKey: PublicKey? by lazy {
        decodePublicKey(ADMIN_BOX_PUB_B64)
    }

    private val adminSignPublicKey: PublicKey? by lazy {
        decodePublicKey(ADMIN_SIGN_PUB_B64)
    }

    data class VerifiedAdminState(
        val revision: Long = 0L,
        val timestampMs: Long = 0L,
        val bannerActive: Boolean = false,
        val bannerStyle: String = "info",
        val bannerTitle: String = "",
        val bannerBody: String = "",
        val bannedTags: Map<String, String> = emptyMap(),
        val killedRooms: Map<String, Long> = emptyMap()
    )

    data class VerifiedKillCommand(
        val roomCode: String,
        val reason: String,
        val timestampMs: Long
    )

    data class VerifiedAdminReply(
        val replyId: String,
        val targetTag: String,
        val message: String,
        val timestampMs: Long
    )

    @Volatile
    var currentState: VerifiedAdminState = VerifiedAdminState()
        private set

    private val recentKilledRooms = ConcurrentHashMap<String, Long>()
    private val stateListeners = CopyOnWriteArrayList<(VerifiedAdminState) -> Unit>()
    private val killListeners = CopyOnWriteArrayList<(VerifiedKillCommand) -> Unit>()
    private val replyListeners = CopyOnWriteArrayList<(VerifiedAdminReply) -> Unit>()

    fun normalizeTag(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "").take(16)

    fun feedbackSlotTopic(slot: Int): String =
        String.format("glan/v36/feedback/slot/%02d", ((slot % FEEDBACK_SLOT_COUNT) + FEEDBACK_SLOT_COUNT) % FEEDBACK_SLOT_COUNT)

    fun replyTopicForTag(accountTag: String): String? {
        val clean = normalizeTag(accountTag)
        if (clean.length < 4) return null
        return "glan/v36/admin/reply/$clean"
    }

    fun addStateListener(l: (VerifiedAdminState) -> Unit) {
        stateListeners.add(l)
        l(currentState)
    }

    fun removeStateListener(l: (VerifiedAdminState) -> Unit) {
        stateListeners.remove(l)
    }

    fun addKillListener(l: (VerifiedKillCommand) -> Unit) {
        killListeners.add(l)
    }

    fun removeKillListener(l: (VerifiedKillCommand) -> Unit) {
        killListeners.remove(l)
    }

    fun addReplyListener(l: (VerifiedAdminReply) -> Unit) {
        replyListeners.add(l)
    }

    fun removeReplyListener(l: (VerifiedAdminReply) -> Unit) {
        replyListeners.remove(l)
    }

    fun checkAccountBanReason(accountTag: String?): String? {
        val clean = normalizeTag(accountTag.orEmpty())
        if (clean.isBlank()) return null
        return currentState.bannedTags[clean]
    }

    fun isRoomRecentlyKilledByAdmin(roomCode: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = RoomCrypto.normalizeRoomCode(roomCode)
        if (clean.isBlank()) return false
        val killedAt = recentKilledRooms[clean] ?: currentState.killedRooms[clean] ?: return false
        if (nowMs - killedAt > KILL_MEMORY_TTL_MS) {
            recentKilledRooms.remove(clean)
            return false
        }
        return true
    }

    private fun decodePublicKey(b64: String): PublicKey? {
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw))
        } catch (_: Exception) {
            null
        }
    }

    private fun verifyAdminSig(canonical: String, sigB64: String): Boolean {
        val pub = adminSignPublicKey ?: return false
        if (sigB64.isBlank() || sigB64.length > 256) return false
        return try {
            val sigBytes = Base64.decode(sigB64, Base64.NO_WRAP)
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(canonical.toByteArray(Charsets.UTF_8))
            s.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptAdminEnvelope(packet: ByteArray): String? {
        if (packet.size < 1 + 12 + 17) return null
        if (packet[0] != HEADER_ADMIN_ENVELOPE) return null
        return try {
            val iv = packet.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, adminEnvelopeKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(byteArrayOf(HEADER_ADMIN_ENVELOPE))
            val plain = cipher.doFinal(packet, 13, packet.size - 13)
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun onAdminPacketReceived(topic: String, payload: ByteArray) {
        if (payload.isEmpty()) {
            if (topic == STATE_TOPIC) {
                currentState = VerifiedAdminState()
                for (fn in stateListeners) {
                    try { fn(currentState) } catch (_: Exception) { }
                }
            }
            return
        }

        when {
            topic == STATE_TOPIC -> handleStatePacket(payload)
            topic == CMD_TOPIC -> handleCmdPacket(payload)
            topic.startsWith("glan/v36/admin/reply/") -> handleReplyPacket(payload)
        }
    }

    private fun handleStatePacket(payload: ByteArray) {
        val jsonStr = decryptAdminEnvelope(payload) ?: return
        try {
            val j = JSONObject(jsonStr)
            val rev = j.optLong("rev", 0L)
            val ts = j.optLong("ts", 0L)
            if (rev <= 0L || ts <= 0L) return
            if (rev < currentState.revision) return

            val bActive = j.optBoolean("bActive", false)
            val bStyle = RoomCrypto.cleanSafeText(j.optString("bStyle", "info"), 12).ifBlank { "info" }
            val bTitle = RoomCrypto.cleanSafeText(j.optString("bTitle", ""), 60)
            val bBody = RoomCrypto.cleanSafeText(j.optString("bBody", ""), 320)

            val bansMap = LinkedHashMap<String, String>()
            val bansArr = j.optJSONArray("bans")
            if (bansArr != null) {
                for (i in 0 until bansArr.length()) {
                    val o = bansArr.optJSONObject(i) ?: continue
                    val tag = normalizeTag(o.optString("tag", ""))
                    if (tag.length < 4) continue
                    val reason = RoomCrypto.cleanSafeText(
                        o.optString("reason", "Conta suspensa pela administração."),
                        140
                    ).ifBlank { "Conta suspensa pela administração." }
                    bansMap[tag] = reason
                }
            }

            val killsMap = LinkedHashMap<String, Long>()
            val killsArr = j.optJSONArray("kills")
            if (killsArr != null) {
                for (i in 0 until killsArr.length()) {
                    val o = killsArr.optJSONObject(i) ?: continue
                    val code = RoomCrypto.normalizeRoomCode(o.optString("code", ""))
                    val kts = o.optLong("ts", 0L)
                    if (code.length >= 4 && kts > 0L) {
                        killsMap[code] = kts
                    }
                }
            }

            val bansCanonical = bansMap.entries
                .sortedBy { it.key }
                .joinToString(";") { "${it.key}=${it.value}" }
            val killsCanonical = killsMap.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}@${it.value}" }
            val activeBit = if (bActive) 1 else 0
            val canonical = "v36|state|$rev|$ts|$activeBit|$bStyle|$bTitle|$bBody|$bansCanonical|$killsCanonical"
            val sigB64 = j.optString("sig", "")
            if (!verifyAdminSig(canonical, sigB64)) return

            val now = System.currentTimeMillis()
            for ((code, kts) in killsMap) {
                if (now - kts <= KILL_MEMORY_TTL_MS) {
                    recentKilledRooms[code] = kts
                }
            }

            val verified = VerifiedAdminState(
                revision = rev,
                timestampMs = ts,
                bannerActive = bActive && bBody.isNotBlank(),
                bannerStyle = bStyle,
                bannerTitle = bTitle.ifBlank { "COMUNICADO OFICIAL" },
                bannerBody = bBody,
                bannedTags = bansMap,
                killedRooms = killsMap
            )
            currentState = verified
            for (fn in stateListeners) {
                try { fn(verified) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun handleCmdPacket(payload: ByteArray) {
        val jsonStr = decryptAdminEnvelope(payload) ?: return
        try {
            val j = JSONObject(jsonStr)
            if (j.optString("type", "") != "KILL_ROOM") return
            val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
            if (code.length < 4) return
            val reason = RoomCrypto.cleanSafeText(
                j.optString("reason", "Sala encerrada pela administração do gLAN."),
                140
            ).ifBlank { "Sala encerrada pela administração do gLAN." }
            val ts = j.optLong("ts", 0L)
            val now = System.currentTimeMillis()
            if (ts <= 0L || abs(now - ts) > LIVE_CMD_MAX_SKEW_MS) return

            val canonical = "v36|kill|$code|$reason|$ts"
            if (!verifyAdminSig(canonical, j.optString("sig", ""))) return

            recentKilledRooms[code] = now
            val cmd = VerifiedKillCommand(code, reason, ts)
            for (fn in killListeners) {
                try { fn(cmd) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun handleReplyPacket(payload: ByteArray) {
        val jsonStr = decryptAdminEnvelope(payload) ?: return
        try {
            val j = JSONObject(jsonStr)
            val id = RoomCrypto.cleanSafeText(j.optString("id", ""), 40)
            val tag = normalizeTag(j.optString("tag", ""))
            val msg = RoomCrypto.cleanSafeText(j.optString("msg", ""), 500)
            val ts = j.optLong("ts", 0L)
            if (id.isBlank() || tag.length < 4 || msg.isBlank() || ts <= 0L) return

            val canonical = "v36|reply|$id|$tag|$msg|$ts"
            if (!verifyAdminSig(canonical, j.optString("sig", ""))) return

            val reply = VerifiedAdminReply(id, tag, msg, ts)
            for (fn in replyListeners) {
                try { fn(reply) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /**
     * Criptografa um feedback com ECIES P-256 + AES-128-GCM e publica tanto em um slot
     * retido (00..63) quanto no tópico ao vivo nos brokers TLS.
     */
    fun buildEncryptedFeedbackPacket(
        category: String,
        displayName: String,
        accountUid: Long,
        accountTag: String,
        message: String,
        appVersion: String,
        networkSummary: String,
        slotOut: IntArray? = null
    ): Pair<Int, ByteArray>? {
        val adminPub = adminBoxPublicKey ?: return null
        val cleanMsg = message.trim().replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), "").take(600)
        if (cleanMsg.length < 4) return null

        val feedbackId = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        val slot = rng.nextInt(FEEDBACK_SLOT_COUNT)
        slotOut?.takeIf { it.isNotEmpty() }?.set(0, slot)

        val devInfo = RoomCrypto.cleanSafeText("${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})", 56)
        val json = JSONObject().apply {
            put("id", feedbackId)
            put("slot", slot)
            put("ts", System.currentTimeMillis())
            put("cat", RoomCrypto.cleanSafeText(category, 20).ifBlank { "Sugestão" })
            put("name", RoomCrypto.cleanSafeText(displayName, 24).ifBlank { "Jogador" })
            put("uid", accountUid)
            put("tag", normalizeTag(accountTag))
            put("msg", cleanMsg)
            put("ver", RoomCrypto.cleanSafeText(appVersion, 16))
            put("dev", devInfo)
            put("net", RoomCrypto.cleanSafeText(networkSummary, 40))
        }
        val plainBytes = json.toString().toByteArray(Charsets.UTF_8)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256, rng)
        val eph = kpg.generateKeyPair()
        val ephSpki = eph.public.encoded
        if (ephSpki.isEmpty() || ephSpki.size > 200) return null

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(eph.private)
        ka.doPhase(adminPub, true)
        val shared = ka.generateSecret()

        val md = MessageDigest.getInstance("SHA-256")
        md.update("glan-feedback-v36|".toByteArray(Charsets.UTF_8))
        md.update(shared)
        md.update(ephSpki)
        val aesKey = SecretKeySpec(md.digest().copyOf(16), "AES")

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(HEADER_FEEDBACK_ECIES))
        val ct = cipher.doFinal(plainBytes)

        val pkt = ByteArray(1 + 1 + ephSpki.size + 12 + ct.size)
        pkt[0] = HEADER_FEEDBACK_ECIES
        pkt[1] = (ephSpki.size and 0xFF).toByte()
        System.arraycopy(ephSpki, 0, pkt, 2, ephSpki.size)
        System.arraycopy(iv, 0, pkt, 2 + ephSpki.size, 12)
        System.arraycopy(ct, 0, pkt, 2 + ephSpki.size + 12, ct.size)
        return slot to pkt
    }

    fun sendEncryptedFeedbackAsync(
        category: String,
        displayName: String,
        accountUid: Long,
        accountTag: String,
        message: String,
        appVersion: String,
        networkSummary: String,
        onResult: (Boolean) -> Unit
    ) {
        Thread({
            val built = try {
                buildEncryptedFeedbackPacket(
                    category = category,
                    displayName = displayName,
                    accountUid = accountUid,
                    accountTag = accountTag,
                    message = message,
                    appVersion = appVersion,
                    networkSummary = networkSummary
                )
            } catch (_: Exception) {
                null
            }
            if (built == null) {
                onResult(false)
                return@Thread
            }
            val (slot, packet) = built
            val slotTopic = feedbackSlotTopic(slot)
            val sentOk = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val client = MqttLightClient(
                socketProtector = { true },
                onPacketReceived = { _, _ -> },
                onConnectionStateChanged = { connected, _ ->
                    if (connected) {
                        latch.countDown()
                    }
                }
            )
            try {
                client.start(emptyList())
                latch.await(7_500L, TimeUnit.MILLISECONDS)
                if (client.isConnected()) {
                    val okSlot = client.publish(slotTopic, packet, retain = true)
                    val okLive = client.publish(FEEDBACK_LIVE_TOPIC, packet, retain = false)
                    if (okSlot || okLive) {
                        sentOk.set(true)
                    }
                    try { Thread.sleep(280L) } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                client.stop()
            }
            onResult(sentOk.get())
        }, "glan-feedback-tx").apply { isDaemon = true }.start()
    }
}
