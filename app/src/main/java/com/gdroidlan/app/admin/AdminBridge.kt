package com.gdroidlan.app.admin

import android.os.Build
import android.util.Base64
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.net.MqttLightClient
import com.gdroidlan.app.update.OfficialVersionCert
import com.gdroidlan.app.vpn.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * Ponte segura gLAN v3.9 <-> gLAN Admin v2.0:
 * - Envio de Feedback ECIES P-256 + AES-128-GCM (64 slots retidos + tópico ao vivo).
 * - Telemetria criptografada de Servidores Públicos E Privados (ECIES P-256 + AES-128-GCM,
 *   128 slots retidos + tópico ao vivo, legível exclusivamente pelo gLAN Admin).
 * - Verificação estrita ECDSA P-256 (SHA256withECDSA) de comunicados globais com expiração,
 *   modo manutenção, pausa do lobby público, versão mínima, suspensões temporárias ou permanentes,
 *   avisos diretos em sala (ROOM_NOTICE), expulsão administrativa de jogador (ADMIN_KICK_MEMBER),
 *   sincronização imediata (SYNC_SERVERS), encerramento de salas (KILL_ROOM) e respostas diretas.
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

    const val SRV_LIVE_TOPIC = "glan/v39/admin/srv_live"
    const val SRV_SLOT_COUNT = 128

    private const val HEADER_FEEDBACK_ECIES: Byte = 0x46 // 'F'
    private const val HEADER_ADMIN_ENVELOPE: Byte = 0x41 // 'A'
    private const val HEADER_SRV_TELEMETRY_ECIES: Byte = 0x53 // 'S'
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

    data class BanRecord(
        val tag: String,
        val reason: String,
        val untilMs: Long = 0L,
        val labelName: String = ""
    )

    data class VerifiedAdminState(
        val revision: Long = 0L,
        val timestampMs: Long = 0L,
        val bannerActive: Boolean = false,
        val bannerStyle: String = "info",
        val bannerTitle: String = "",
        val bannerBody: String = "",
        val bannerUntilMs: Long = 0L,
        val maintActive: Boolean = false,
        val maintMessage: String = "",
        val blockPublicRooms: Boolean = false,
        val minVersionCode: Int = 0,
        val bannedTags: Map<String, String> = emptyMap(),
        val banRecords: Map<String, BanRecord> = emptyMap(),
        val killedRooms: Map<String, Long> = emptyMap()
    )

    data class VerifiedKillCommand(
        val roomCode: String,
        val reason: String,
        val timestampMs: Long
    )

    data class VerifiedRoomNotice(
        val roomCode: String,
        val title: String,
        val message: String,
        val timestampMs: Long
    )

    data class VerifiedMemberKickCommand(
        val roomCode: String,
        val targetNodeId: Int,
        val targetTag: String,
        val blockInSession: Boolean,
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

    @Volatile
    private var latestReply: VerifiedAdminReply? = null

    private val recentKilledRooms = ConcurrentHashMap<String, Long>()
    private val liveCmdKillTimestamps = ConcurrentHashMap<String, Long>()

    private val stateListeners = CopyOnWriteArrayList<(VerifiedAdminState) -> Unit>()
    private val killListeners = CopyOnWriteArrayList<(VerifiedKillCommand) -> Unit>()
    private val syncListeners = CopyOnWriteArrayList<() -> Unit>()
    private val roomNoticeListeners = CopyOnWriteArrayList<(VerifiedRoomNotice) -> Unit>()
    private val memberKickListeners = CopyOnWriteArrayList<(VerifiedMemberKickCommand) -> Unit>()
    private val replyListeners = CopyOnWriteArrayList<(VerifiedAdminReply) -> Unit>()

    fun normalizeTag(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "").take(16)

    fun feedbackSlotTopic(slot: Int): String =
        String.format(
            "glan/v36/feedback/slot/%02d",
            ((slot % FEEDBACK_SLOT_COUNT) + FEEDBACK_SLOT_COUNT) % FEEDBACK_SLOT_COUNT
        )

    fun serverSlotForCode(roomCode: String): Int {
        val clean = RoomCrypto.normalizeRoomCode(roomCode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("glan-srv-slot-v39:$clean").toByteArray(Charsets.UTF_8))
        val v = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        return v % SRV_SLOT_COUNT
    }

    fun serverSlotTopic(roomCode: String): String =
        String.format("glan/v39/admin/srv/%03d", serverSlotForCode(roomCode))

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

    fun addSyncListener(l: () -> Unit) {
        syncListeners.add(l)
    }

    fun removeSyncListener(l: () -> Unit) {
        syncListeners.remove(l)
    }

    fun addRoomNoticeListener(l: (VerifiedRoomNotice) -> Unit) {
        roomNoticeListeners.add(l)
    }

    fun removeRoomNoticeListener(l: (VerifiedRoomNotice) -> Unit) {
        roomNoticeListeners.remove(l)
    }

    fun addMemberKickListener(l: (VerifiedMemberKickCommand) -> Unit) {
        memberKickListeners.add(l)
    }

    fun removeMemberKickListener(l: (VerifiedMemberKickCommand) -> Unit) {
        memberKickListeners.remove(l)
    }

    fun addReplyListener(l: (VerifiedAdminReply) -> Unit) {
        replyListeners.add(l)
        latestReply?.let { l(it) }
    }

    fun removeReplyListener(l: (VerifiedAdminReply) -> Unit) {
        replyListeners.remove(l)
    }

    fun isBannerCurrentlyActive(
        state: VerifiedAdminState = currentState,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!state.bannerActive || state.bannerBody.isBlank()) return false
        if (state.bannerUntilMs > 0L && nowMs >= state.bannerUntilMs) return false
        return true
    }

    fun checkAccountBanReason(
        accountTag: String?,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val clean = normalizeTag(accountTag.orEmpty())
        if (clean.isBlank()) return null
        val st = currentState
        val rec = st.banRecords[clean]
        if (rec != null) {
            if (rec.untilMs > 0L && nowMs >= rec.untilMs) {
                return null
            }
            return if (rec.untilMs > 0L) {
                val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                "${rec.reason} (Expira em ${fmt.format(Date(rec.untilMs))})"
            } else {
                rec.reason
            }
        }
        return st.bannedTags[clean]
    }

    fun checkNetworkGateError(
        installedVersionCode: Int,
        creatingPublicRoom: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<String, String>? {
        val st = currentState
        if (st.maintActive) {
            val msg = st.maintMessage.ifBlank {
                "A rede do gLAN está em manutenção temporária. Tente novamente em instantes."
            }
            return "REDE EM MANUTENÇÃO" to msg
        }
        if (st.minVersionCode > 0 && installedVersionCode < st.minVersionCode) {
            return "ATUALIZAÇÃO OBRIGATÓRIA" to
                "Esta versão do gLAN não é mais suportada pela rede atual. Baixe a versão oficial mais recente para criar ou entrar em salas."
        }
        if (creatingPublicRoom && st.blockPublicRooms) {
            return "SALAS PÚBLICAS PAUSADAS" to
                "A criação de salas públicas está temporariamente pausada pela administração. Use Sala Privada por código."
        }
        @Suppress("UNUSED_VARIABLE")
        val unusedNow = nowMs
        return null
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
                recentKilledRooms.clear()
                currentState = VerifiedAdminState()
                for (fn in stateListeners) {
                    try { fn(currentState) } catch (_: Exception) { }
                }
            } else if (topic.startsWith("glan/v36/admin/reply/")) {
                val tagTopic = normalizeTag(topic.substringAfterLast('/'))
                if (latestReply?.targetTag == tagTopic) {
                    latestReply = null
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
            val rawBanRecs = LinkedHashMap<String, BanRecord>()
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
                    val untilMs = o.optLong("until", 0L).coerceAtLeast(0L)
                    val label = RoomCrypto.cleanSafeText(o.optString("name", ""), 24)
                    bansMap[tag] = reason
                    rawBanRecs[tag] = BanRecord(tag = tag, reason = reason, untilMs = untilMs, labelName = label)
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

            // Verifica extensão v39 assinada (expiração de banner, manutenção, pausa de salas públicas, versão mínima e prazos de ban)
            val rawBannerUntil = j.optLong("bUntil", 0L).coerceAtLeast(0L)
            val rawMaintActive = j.optBoolean("mActive", false)
            val rawMaintMsg = RoomCrypto.cleanSafeText(j.optString("mMsg", ""), 240)
            val rawBlockPub = j.optBoolean("blkPub", false)
            val rawMinVer = j.optInt("minVer", 0).coerceIn(0, 9999)
            val sig39B64 = j.optString("sig39", "")

            val banUntilCanonical = rawBanRecs.entries
                .sortedBy { it.key }
                .joinToString(";") { "${it.key}:${it.value.untilMs}" }
            val canonical39 = "v39|state|$rev|$ts|$rawBannerUntil|" +
                "${if (rawMaintActive) 1 else 0}|$rawMaintMsg|" +
                "${if (rawBlockPub) 1 else 0}|$rawMinVer|$banUntilCanonical"
            val verifiedV39 = sig39B64.isNotBlank() && verifyAdminSig(canonical39, sig39B64)

            val verifiedBanRecords = if (verifiedV39) {
                rawBanRecs
            } else {
                bansMap.mapValues { (k, r) -> BanRecord(tag = k, reason = r, untilMs = 0L) }
            }

            // Sincroniza recentKilledRooms com o estado assinado mais recente (permitindo desbloqueio imediato pelo Admin)
            val now = System.currentTimeMillis()
            val updatedKills = HashSet<String>()
            for ((code, kts) in killsMap) {
                if (now - kts <= KILL_MEMORY_TTL_MS) {
                    recentKilledRooms[code] = kts
                    updatedKills.add(code)
                }
            }
            val itRecent = recentKilledRooms.entries.iterator()
            while (itRecent.hasNext()) {
                val e = itRecent.next()
                val liveCmdAt = liveCmdKillTimestamps[e.key] ?: 0L
                if (e.key !in updatedKills && (now - liveCmdAt > 15_000L)) {
                    itRecent.remove()
                }
            }

            val verified = VerifiedAdminState(
                revision = rev,
                timestampMs = ts,
                bannerActive = bActive && bBody.isNotBlank(),
                bannerStyle = bStyle,
                bannerTitle = bTitle.ifBlank { "COMUNICADO OFICIAL" },
                bannerBody = bBody,
                bannerUntilMs = if (verifiedV39) rawBannerUntil else 0L,
                maintActive = if (verifiedV39) rawMaintActive else false,
                maintMessage = if (verifiedV39) rawMaintMsg else "",
                blockPublicRooms = if (verifiedV39) rawBlockPub else false,
                minVersionCode = if (verifiedV39) rawMinVer else 0,
                bannedTags = bansMap,
                banRecords = verifiedBanRecords,
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
            val type = j.optString("type", "")
            val ts = j.optLong("ts", 0L)
            val now = System.currentTimeMillis()
            if (ts <= 0L || abs(now - ts) > LIVE_CMD_MAX_SKEW_MS) return

            when (type) {
                "KILL_ROOM" -> {
                    val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
                    if (code.length < 4) return
                    val reason = RoomCrypto.cleanSafeText(
                        j.optString("reason", "Sala encerrada pela administração do gLAN."),
                        140
                    ).ifBlank { "Sala encerrada pela administração do gLAN." }
                    val canonical = "v36|kill|$code|$reason|$ts"
                    if (!verifyAdminSig(canonical, j.optString("sig", ""))) return

                    recentKilledRooms[code] = now
                    liveCmdKillTimestamps[code] = now
                    val cmd = VerifiedKillCommand(code, reason, ts)
                    for (fn in killListeners) {
                        try { fn(cmd) } catch (_: Exception) { }
                    }
                }

                "SYNC_SERVERS" -> {
                    val canonical = "v39|sync|$ts"
                    if (!verifyAdminSig(canonical, j.optString("sig", ""))) return
                    for (fn in syncListeners) {
                        try { fn() } catch (_: Exception) { }
                    }
                }

                "ROOM_NOTICE" -> {
                    val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
                    if (code.length < 4) return
                    val title = RoomCrypto.cleanSafeText(
                        j.optString("title", "AVISO DA ADMINISTRAÇÃO"),
                        60
                    ).ifBlank { "AVISO DA ADMINISTRAÇÃO" }
                    val msg = RoomCrypto.cleanSafeText(j.optString("msg", ""), 300)
                    if (msg.isBlank()) return
                    val canonical = "v39|rnotice|$code|$title|$msg|$ts"
                    if (!verifyAdminSig(canonical, j.optString("sig", ""))) return

                    val notice = VerifiedRoomNotice(code, title, msg, ts)
                    for (fn in roomNoticeListeners) {
                        try { fn(notice) } catch (_: Exception) { }
                    }
                }

                "ADMIN_KICK_MEMBER" -> {
                    val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
                    if (code.length < 4) return
                    val targetNode = j.optInt("node", 0)
                    val targetTag = normalizeTag(j.optString("tag", ""))
                    if (targetNode <= 1 && targetTag.length < 4) return
                    val blockBit = if (j.optBoolean("block", true)) 1 else 0
                    val reason = RoomCrypto.cleanSafeText(
                        j.optString("reason", "Você foi removido da sala pela administração do gLAN."),
                        140
                    ).ifBlank { "Você foi removido da sala pela administração do gLAN." }
                    val canonical = "v39|rkick|$code|$targetNode|$targetTag|$blockBit|$reason|$ts"
                    if (!verifyAdminSig(canonical, j.optString("sig", ""))) return

                    val cmd = VerifiedMemberKickCommand(
                        roomCode = code,
                        targetNodeId = targetNode,
                        targetTag = targetTag,
                        blockInSession = (blockBit == 1),
                        reason = reason,
                        timestampMs = ts
                    )
                    for (fn in memberKickListeners) {
                        try { fn(cmd) } catch (_: Exception) { }
                    }
                }
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
            latestReply = reply
            for (fn in replyListeners) {
                try { fn(reply) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun encryptWithAdminBoxEcies(
        headerByte: Byte,
        kdfPrefix: String,
        plainBytes: ByteArray
    ): ByteArray? {
        val adminPub = adminBoxPublicKey ?: return null
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
        md.update(kdfPrefix.toByteArray(Charsets.UTF_8))
        md.update(shared)
        md.update(ephSpki)
        val aesKey = SecretKeySpec(md.digest().copyOf(16), "AES")

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(headerByte))
        val ct = cipher.doFinal(plainBytes)

        val pkt = ByteArray(1 + 1 + ephSpki.size + 12 + ct.size)
        pkt[0] = headerByte
        pkt[1] = (ephSpki.size and 0xFF).toByte()
        System.arraycopy(ephSpki, 0, pkt, 2, ephSpki.size)
        System.arraycopy(iv, 0, pkt, 2 + ephSpki.size, 12)
        System.arraycopy(ct, 0, pkt, 2 + ephSpki.size + 12, ct.size)
        return pkt
    }

    /**
     * Criptografa um relatório completo do servidor ativo (Público OU Privado) exclusivamente
     * para a chave pública do gLAN Admin (ADMIN_BOX_PUB), assinado pela chave efêmera da sala.
     */
    fun buildEncryptedServerReport(
        hostIdentity: RoomCrypto.IdentityKeyBox,
        isOpen: Boolean,
        roomCode: String,
        isPublicRoom: Boolean,
        roomTitle: String,
        hostName: String,
        hostTag: String,
        hostUid: Long,
        members: List<TunnelProtocol.RoomMemberInfo>,
        maxPlayers: Int,
        ipProtected: Boolean,
        gamePortOpen: Boolean,
        mapMode: String,
        startedAtMs: Long
    ): ByteArray? {
        val cleanCode = RoomCrypto.normalizeRoomCode(roomCode)
        if (cleanCode.length < 4) return null
        val cleanHost = RoomCrypto.cleanSafeText(hostName, 24).ifBlank { "Anfitrião" }
        val cleanTag = normalizeTag(hostTag)
        val defaultTitle = if (isPublicRoom) "Sala de $cleanHost" else "Sala Privada de $cleanHost"
        val cleanTitle = RoomCrypto.cleanSafeText(roomTitle, 28).ifBlank { defaultTitle }
        val cleanMap = RoomCrypto.cleanSafeText(mapMode, 28)
        val max = maxPlayers.coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
        val cur = if (isOpen) members.size.coerceIn(1, max) else 0
        val now = System.currentTimeMillis()
        val openBit = if (isOpen) 1 else 0

        val signData = "v39|srv|$cleanCode|$openBit|$cur|$max|$now".toByteArray(Charsets.UTF_8)
        val hostSig = hostIdentity.signToBase64(signData)

        val membersArr = JSONArray()
        if (isOpen) {
            for (m in members) {
                membersArr.put(
                    JSONObject().apply {
                        put("node", m.nodeId)
                        put("name", RoomCrypto.cleanSafeText(m.displayName, 24).ifBlank { "Jogador" })
                        put("tag", normalizeTag(m.accountTag))
                        put("ip", m.virtualIp)
                        put("host", m.isHost)
                        put("ping", m.pingMs)
                        put("route", RoomCrypto.cleanSafeText(m.routeLabel, 16))
                    }
                )
            }
        }

        val json = JSONObject().apply {
            put("open", isOpen)
            put("code", cleanCode)
            put("pubRoom", isPublicRoom)
            put("title", cleanTitle)
            put("host", cleanHost)
            put("htag", cleanTag)
            put("huid", hostUid)
            put("cur", cur)
            put("max", max)
            put("ipProt", ipProtected)
            put("gOpen", gamePortOpen)
            put("map", cleanMap)
            put("ver", OfficialVersionCert.BUILT_VERSION_NAME)
            put("vCode", OfficialVersionCert.BUILT_VERSION_CODE)
            put("startTs", startedAtMs)
            put("ts", now)
            put("hpub", hostIdentity.publicKeyBase64)
            put("hsig", hostSig)
            put("members", membersArr)
        }

        return encryptWithAdminBoxEcies(
            headerByte = HEADER_SRV_TELEMETRY_ECIES,
            kdfPrefix = "glan-srv-telemetry-v39|",
            plainBytes = json.toString().toByteArray(Charsets.UTF_8)
        )
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
        val cleanMsg = message.trim().replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), "").take(600)
        if (cleanMsg.length < 4) return null

        val feedbackId = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        val slot = rng.nextInt(FEEDBACK_SLOT_COUNT)
        slotOut?.takeIf { it.isNotEmpty() }?.set(0, slot)

        val devInfo = RoomCrypto.cleanSafeText(
            "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
            56
        )
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

        val pkt = encryptWithAdminBoxEcies(
            headerByte = HEADER_FEEDBACK_ECIES,
            kdfPrefix = "glan-feedback-v36|",
            plainBytes = json.toString().toByteArray(Charsets.UTF_8)
        ) ?: return null

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
