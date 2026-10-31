package com.gdroidlan.app.net

import android.os.SystemClock
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.update.OfficialVersionCert
import com.gdroidlan.app.update.UpdateNotifier
import com.gdroidlan.app.vpn.TunnelProtocol
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Diretório autenticado em tempo real de Salas Públicas Abertas + Medidor de Ping + Verificador pré-conexão:
 * - Cada anúncio de abertura/fechamento de Sala Pública é assinado digitalmente com ECDSA P-256
 *   pela chave privada efêmera do Anfitrião, impedindo falsificação ou fechamento de salas alheias.
 * - Mede continuamente a latência real (ms) das salas abertas em segundo plano.
 * - Verifica em ~150ms–2.2s se uma sala realmente existe antes de ativar a VPN.
 */
class PublicLobbyBrowser(
    private val installedVersionCode: Int = OfficialVersionCert.BUILT_VERSION_CODE,
    private val onRoomsUpdated: (List<PublicRoomEntry>, Boolean) -> Unit
) {

    data class PublicRoomEntry(
        val roomCode: String,
        val roomTitle: String,
        val hostName: String,
        val currentPlayers: Int,
        val maxPlayers: Int,
        val ownerPubKeyB64: String,
        val lastSeenAtMs: Long,
        val pingMs: Long = -1L
    ) {
        val isFull: Boolean get() = currentPlayers >= maxPlayers
    }

    sealed class ProbeResult {
        object Available : ProbeResult()
        object NotFound : ProbeResult()
        data class Full(val current: Int, val max: Int) : ProbeResult()
        object Blocked : ProbeResult()
        object Offline : ProbeResult()
        object Aborted : ProbeResult()
    }

    companion object {
        const val LOBBY_TOPIC = "glan/v26/lobby/live"
        private const val STALE_TIMEOUT_MS = 8_500L
        private const val MAX_CLOCK_SKEW_MS = 180_000L
        private val lobbyCipher by lazy { RoomCrypto.SessionCipher("GLANPUBLICLOBBYV26") }
        private val probeRng = SecureRandom()

        private fun canonicalOpenSignData(
            code: String,
            title: String,
            host: String,
            cur: Int,
            max: Int,
            ts: Long
        ): ByteArray = "v26|open|$code|$title|$host|$cur|$max|$ts".toByteArray(Charsets.UTF_8)

        private fun canonicalCloseSignData(code: String, ts: Long): ByteArray =
            "v26|close|$code|$ts".toByteArray(Charsets.UTF_8)

        fun buildOpenBeaconEncrypted(
            identity: RoomCrypto.IdentityKeyBox,
            roomCode: String,
            roomTitle: String,
            hostName: String,
            currentPlayers: Int,
            maxPlayers: Int
        ): ByteArray {
            val cleanCode = RoomCrypto.normalizeRoomCode(roomCode)
            val cleanHost = RoomCrypto.cleanSafeText(hostName, 24).ifBlank { "Anfitrião" }
            val cleanTitle = RoomCrypto.cleanSafeText(roomTitle, 28).ifBlank { "Sala de $cleanHost" }
            val max = maxPlayers.coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
            val cur = currentPlayers.coerceIn(1, max)
            val ts = System.currentTimeMillis()
            val sig = identity.signToBase64(canonicalOpenSignData(cleanCode, cleanTitle, cleanHost, cur, max, ts))

            val json = JSONObject().apply {
                put("open", true)
                put("code", cleanCode)
                put("title", cleanTitle)
                put("host", cleanHost)
                put("cur", cur)
                put("max", max)
                put("ts", ts)
                put("pub", identity.publicKeyBase64)
                put("sig", sig)
                UpdateNotifier.getBuiltInVerifiedRelease()?.let { rel ->
                    put("vCode", rel.versionCode)
                    put("vName", rel.versionName)
                    put("vSig", rel.signatureB64)
                }
            }
            return lobbyCipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        }

        fun buildCloseBeaconEncrypted(
            identity: RoomCrypto.IdentityKeyBox,
            roomCode: String
        ): ByteArray {
            val cleanCode = RoomCrypto.normalizeRoomCode(roomCode)
            val ts = System.currentTimeMillis()
            val sig = identity.signToBase64(canonicalCloseSignData(cleanCode, ts))
            val json = JSONObject().apply {
                put("open", false)
                put("code", cleanCode)
                put("ts", ts)
                put("pub", identity.publicKeyBase64)
                put("sig", sig)
            }
            return lobbyCipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private val active = AtomicBoolean(false)
    private val roomsMap = ConcurrentHashMap<String, PublicRoomEntry>()
    private val pingCacheByCode = ConcurrentHashMap<String, Long>()
    private val lastPingedAtByCode = ConcurrentHashMap<String, Long>()
    private val topicListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray) -> Unit>>()

    @Volatile private var mqtt: MqttLightClient? = null
    @Volatile private var brokerConnected = false
    @Volatile private var autoPingEnabled = true

    fun setAutoPingEnabled(enabled: Boolean) {
        autoPingEnabled = enabled
        if (!enabled) {
            pingCacheByCode.clear()
            for ((code, entry) in roomsMap) {
                roomsMap[code] = entry.copy(pingMs = -1L)
            }
            if (active.get()) dispatchSnapshot()
        }
    }

    private fun registerTopicListener(topic: String, fn: (ByteArray) -> Unit) {
        val list = topicListeners.getOrPut(topic) { CopyOnWriteArrayList() }
        list.add(fn)
    }

    private fun unregisterTopicListener(topic: String, fn: (ByteArray) -> Unit): Boolean {
        val list = topicListeners[topic] ?: return true
        list.remove(fn)
        if (list.isEmpty()) {
            topicListeners.remove(topic, list)
            return true
        }
        return false
    }

    fun start() {
        if (!active.compareAndSet(false, true)) {
            dispatchSnapshot()
            return
        }

        val client = MqttLightClient(
            socketProtector = { true },
            onPacketReceived = { topic, encrypted ->
                when (topic) {
                    LOBBY_TOPIC -> handleIncomingBeacon(encrypted)
                    UpdateNotifier.VERSION_TOPIC -> {
                        UpdateNotifier.parseEncryptedAnnouncement(encrypted)?.let { rel ->
                            UpdateNotifier.onVerifiedReleaseObserved(rel, installedVersionCode)
                        }
                    }
                    else -> {
                        val listeners = topicListeners[topic]
                        if (listeners != null) {
                            for (fn in listeners) {
                                try {
                                    fn(encrypted)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
            },
            onConnectionStateChanged = { connected, _ ->
                brokerConnected = connected
                if (active.get()) dispatchSnapshot()
                if (connected) {
                    scheduleSelfRetainedVersionPublishIfNewest()
                }
            }
        )
        mqtt = client
        client.start(listOf(LOBBY_TOPIC, UpdateNotifier.VERSION_TOPIC))

        Thread({
            while (active.get()) {
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!active.get()) break
                pruneStaleRoomsAndDispatch(forceDispatch = false)
            }
        }, "glan-lobby-pruner").apply { isDaemon = true }.start()

        Thread({
            while (active.get()) {
                try {
                    Thread.sleep(1_200L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!active.get()) break
                if (!autoPingEnabled || !brokerConnected) continue

                val nowElapsed = SystemClock.elapsedRealtime()
                val candidates = roomsMap.values
                    .filter { entry ->
                        val lastPing = lastPingedAtByCode[entry.roomCode] ?: 0L
                        entry.pingMs < 0L || (nowElapsed - lastPing >= 4_500L)
                    }
                    .sortedBy { if (it.pingMs < 0L) 0L else (lastPingedAtByCode[it.roomCode] ?: 0L) }
                    .take(5)

                for (room in candidates) {
                    if (!active.get() || !autoPingEnabled || !brokerConnected) break
                    val activeClient = mqtt ?: break
                    if (!activeClient.isConnected()) break

                    lastPingedAtByCode[room.roomCode] = SystemClock.elapsedRealtime()
                    val measured = measureRoomPingOnce(activeClient, room.roomCode, timeoutMs = 1_350L)
                    if (measured > 0L) {
                        pingCacheByCode[room.roomCode] = measured
                        val cur = roomsMap[room.roomCode]
                        if (cur != null) {
                            roomsMap[room.roomCode] = cur.copy(pingMs = measured)
                            dispatchSnapshot()
                        }
                    }
                }
            }
        }, "glan-lobby-pinger").apply { isDaemon = true }.start()
    }

    private fun measureRoomPingOnce(
        client: MqttLightClient,
        cleanCode: String,
        timeoutMs: Long
    ): Long {
        val topicRoot = "gdroidlan/v26/${RoomCrypto.deriveTopicHash(cleanCode)}"
        val h2cTopic = "$topicRoot/h2c/ctl"
        val c2hTopic = "$topicRoot/c2h/ctl"
        val roomCipher = RoomCrypto.SessionCipher(cleanCode)
        val probeId = probeRng.nextLong()
        val latch = CountDownLatch(1)
        val rttResult = AtomicLong(-1L)
        var sentAtElapsed = 0L

        val listener: (ByteArray) -> Unit = { encrypted ->
            val plain = roomCipher.decrypt(encrypted)
            if (plain != null && plain.isNotEmpty() && plain[0] == TunnelProtocol.TYPE_PROBE_ACK) {
                val ack = TunnelProtocol.parseProbeAck(plain)
                if (ack != null && ack.probeId == probeId && sentAtElapsed > 0L) {
                    val rtt = (SystemClock.elapsedRealtime() - sentAtElapsed).coerceIn(1L, 9_999L)
                    if (rttResult.compareAndSet(-1L, rtt)) {
                        latch.countDown()
                    }
                }
            }
        }

        registerTopicListener(h2cTopic, listener)
        try {
            client.forceResubscribe(h2cTopic)
            val pkt = roomCipher.encrypt(TunnelProtocol.buildProbeReq(probeId, 0L))
            sentAtElapsed = SystemClock.elapsedRealtime()
            client.publish(c2hTopic, pkt)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            val emptyNow = unregisterTopicListener(h2cTopic, listener)
            if (emptyNow) {
                mqtt?.unsubscribe(h2cTopic)
            }
        }
        return rttResult.get()
    }

    fun refreshNow() {
        if (!active.get()) {
            start()
            return
        }
        lastPingedAtByCode.clear()
        mqtt?.forceResubscribe(LOBBY_TOPIC)
        pruneStaleRoomsAndDispatch(forceDispatch = true)
    }

    fun removeRoomImmediately(rawCode: String) {
        val code = RoomCrypto.normalizeRoomCode(rawCode)
        pingCacheByCode.remove(code)
        lastPingedAtByCode.remove(code)
        if (roomsMap.remove(code) != null) {
            dispatchSnapshot()
        }
    }

    fun probeRoom(
        rawCode: String,
        accountUid: Long,
        timeoutMs: Long = 2_300L,
        onResult: (ProbeResult) -> Unit
    ) {
        val code = RoomCrypto.normalizeRoomCode(rawCode)
        if (code.length < 4 || code.length > 12) {
            onResult(ProbeResult.NotFound)
            return
        }

        if (!active.get()) {
            start()
        }

        Thread({
            val waitStart = System.currentTimeMillis()
            while (active.get() && mqtt?.isConnected() != true && System.currentTimeMillis() - waitStart < 2_200L) {
                try {
                    Thread.sleep(80L)
                } catch (_: InterruptedException) {
                    onResult(ProbeResult.Aborted)
                    return@Thread
                }
            }

            if (!active.get()) {
                onResult(ProbeResult.Aborted)
                return@Thread
            }

            val client = mqtt
            if (client == null || !client.isConnected()) {
                onResult(ProbeResult.Offline)
                return@Thread
            }

            val topicRoot = "gdroidlan/v26/${RoomCrypto.deriveTopicHash(code)}"
            val h2cTopic = "$topicRoot/h2c/ctl"
            val c2hTopic = "$topicRoot/c2h/ctl"
            val roomCipher = RoomCrypto.SessionCipher(code)
            val probeId = probeRng.nextLong()
            val latch = CountDownLatch(1)
            val outcomeRef = AtomicReference<ProbeResult?>(null)
            var sentAtElapsed = SystemClock.elapsedRealtime()

            val listener: (ByteArray) -> Unit = { encrypted ->
                val plain = roomCipher.decrypt(encrypted)
                if (plain != null && plain.isNotEmpty()) {
                    when (plain[0]) {
                        TunnelProtocol.TYPE_PROBE_ACK -> {
                            val ack = TunnelProtocol.parseProbeAck(plain)
                            if (ack != null && ack.probeId == probeId) {
                                val rtt = (SystemClock.elapsedRealtime() - sentAtElapsed).coerceIn(1L, 9_999L)
                                if (autoPingEnabled) {
                                    pingCacheByCode[code] = rtt
                                }
                                val mapped = when (ack.status) {
                                    TunnelProtocol.PROBE_STATUS_BLOCKED -> ProbeResult.Blocked
                                    TunnelProtocol.PROBE_STATUS_FULL -> ProbeResult.Full(ack.curPlayers, ack.maxPlayers)
                                    else -> ProbeResult.Available
                                }
                                if (outcomeRef.compareAndSet(null, mapped)) {
                                    latch.countDown()
                                }
                            }
                        }
                        TunnelProtocol.TYPE_ROSTER -> {
                            val roster = TunnelProtocol.parseRoster(plain, expectedHostKey = null)
                            if (roster != null) {
                                val cur = roster.members.size
                                val mapped = if (cur >= roster.maxPlayers) {
                                    ProbeResult.Full(cur, roster.maxPlayers)
                                } else {
                                    ProbeResult.Available
                                }
                                if (outcomeRef.compareAndSet(null, mapped)) {
                                    latch.countDown()
                                }
                            }
                        }
                    }
                }
            }

            registerTopicListener(h2cTopic, listener)
            try {
                client.forceResubscribe(h2cTopic)
                val probePacket = roomCipher.encrypt(TunnelProtocol.buildProbeReq(probeId, accountUid))
                sentAtElapsed = SystemClock.elapsedRealtime()
                client.publish(c2hTopic, probePacket)

                val stepMs = 650L
                var elapsed = 0L
                while (active.get() && elapsed < timeoutMs && outcomeRef.get() == null) {
                    val waitSlice = minOf(stepMs, timeoutMs - elapsed)
                    if (latch.await(waitSlice, TimeUnit.MILLISECONDS)) {
                        break
                    }
                    elapsed += waitSlice
                    if (active.get() && outcomeRef.get() == null && elapsed < timeoutMs) {
                        val retryPkt = roomCipher.encrypt(TunnelProtocol.buildProbeReq(probeId, accountUid))
                        sentAtElapsed = SystemClock.elapsedRealtime()
                        client.publish(c2hTopic, retryPkt)
                    }
                }
            } catch (_: Exception) {
            } finally {
                val emptyNow = unregisterTopicListener(h2cTopic, listener)
                if (emptyNow) {
                    mqtt?.unsubscribe(h2cTopic)
                }
            }

            if (!active.get() && outcomeRef.get() == null) {
                onResult(ProbeResult.Aborted)
                return@Thread
            }

            val finalOutcome = outcomeRef.get() ?: ProbeResult.NotFound
            if (finalOutcome is ProbeResult.NotFound) {
                removeRoomImmediately(code)
            }
            onResult(finalOutcome)
        }, "glan-room-probe").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        brokerConnected = false
        topicListeners.clear()
        mqtt?.stop()
        mqtt = null
        roomsMap.clear()
        pingCacheByCode.clear()
        lastPingedAtByCode.clear()
    }

    private fun pruneStaleRoomsAndDispatch(forceDispatch: Boolean) {
        val now = System.currentTimeMillis()
        var changed = false
        val it = roomsMap.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastSeenAtMs > STALE_TIMEOUT_MS) {
                pingCacheByCode.remove(entry.key)
                lastPingedAtByCode.remove(entry.key)
                it.remove()
                changed = true
            }
        }
        if (changed || forceDispatch) {
            dispatchSnapshot()
        }
    }

    private fun scheduleSelfRetainedVersionPublishIfNewest() {
        val myRel = UpdateNotifier.getBuiltInVerifiedRelease() ?: return
        Thread({
            try {
                Thread.sleep(1_600L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!active.get() || !brokerConnected) return@Thread
            if (UpdateNotifier.shouldPublishSelfRetainedRefresh(myRel)) {
                val pkt = UpdateNotifier.buildEncryptedAnnouncement(myRel)
                mqtt?.publish(UpdateNotifier.VERSION_TOPIC, pkt, retain = true)
            }
        }, "glan-ver-refresh").apply { isDaemon = true }.start()
    }

    private fun handleIncomingBeacon(encrypted: ByteArray) {
        if (!active.get()) return
        val plain = lobbyCipher.decrypt(encrypted) ?: return
        try {
            val now = System.currentTimeMillis()
            val j = JSONObject(String(plain, Charsets.UTF_8))
            val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
            if (code.length < 4 || code.length > 12) return

            val ts = j.optLong("ts", 0L)
            if (ts <= 0L || abs(now - ts) > MAX_CLOCK_SKEW_MS) return

            val pubB64 = j.optString("pub", "")
            val sigB64 = j.optString("sig", "")
            val pubKey = RoomCrypto.decodeEcPublicKey(pubB64) ?: return

            val existing = roomsMap[code]
            if (existing != null && now - existing.lastSeenAtMs <= STALE_TIMEOUT_MS) {
                if (existing.ownerPubKeyB64 != pubB64) return
            }

            val isOpen = j.optBoolean("open", true)
            if (!isOpen) {
                if (!RoomCrypto.verifySignature(pubKey, canonicalCloseSignData(code, ts), sigB64)) return
                if (existing != null && existing.ownerPubKeyB64 == pubB64) {
                    pingCacheByCode.remove(code)
                    lastPingedAtByCode.remove(code)
                    roomsMap.remove(code)
                    dispatchSnapshot()
                }
                return
            }

            val host = RoomCrypto.cleanSafeText(j.optString("host", ""), 24).ifBlank { "Anfitrião" }
            val title = RoomCrypto.cleanSafeText(j.optString("title", ""), 28).ifBlank { "Sala de $host" }
            val max = j.optInt("max", TunnelProtocol.MAX_TOTAL_PLAYERS)
                .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
            val cur = j.optInt("cur", 1).coerceIn(1, max)

            if (!RoomCrypto.verifySignature(
                    pubKey,
                    canonicalOpenSignData(code, title, host, cur, max, ts),
                    sigB64
                )
            ) {
                return
            }

            val vCode = j.optInt("vCode", 0)
            if (vCode > 0) {
                UpdateNotifier.verifyFields(vCode, j.optString("vName", ""), j.optString("vSig", ""))?.let { rel ->
                    UpdateNotifier.onVerifiedReleaseObserved(rel, installedVersionCode)
                }
            }

            val cachedPing = if (autoPingEnabled) (pingCacheByCode[code] ?: existing?.pingMs ?: -1L) else -1L
            roomsMap[code] = PublicRoomEntry(
                roomCode = code,
                roomTitle = title,
                hostName = host,
                currentPlayers = cur,
                maxPlayers = max,
                ownerPubKeyB64 = pubB64,
                lastSeenAtMs = now,
                pingMs = cachedPing
            )
            dispatchSnapshot()
        } catch (_: Exception) { }
    }

    private fun dispatchSnapshot() {
        val sorted = roomsMap.values
            .sortedWith(
                compareBy<PublicRoomEntry> { it.isFull }
                    .thenBy { if (it.pingMs > 0L) it.pingMs else Long.MAX_VALUE }
                    .thenBy { it.roomTitle.lowercase() }
            )
        onRoomsUpdated(sorted, brokerConnected)
    }
}
