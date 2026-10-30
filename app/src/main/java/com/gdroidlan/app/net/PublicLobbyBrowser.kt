package com.gdroidlan.app.net

import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.vpn.TunnelProtocol
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Diretório em tempo real de Salas Públicas Abertas + Verificador pré-conexão (Room Probe):
 * - Escuta LOBBY_TOPIC para listar salas públicas ao vivo sem salas fantasmas.
 * - Verifica em ~150ms–2.2s se uma sala (pública ou privada) realmente existe antes
 *   de iniciar a VPN, evitando ficar preso em "Conectando..." em salas inexistentes.
 */
class PublicLobbyBrowser(
    private val onRoomsUpdated: (List<PublicRoomEntry>, Boolean) -> Unit
) {

    data class PublicRoomEntry(
        val roomCode: String,
        val roomTitle: String,
        val hostName: String,
        val currentPlayers: Int,
        val maxPlayers: Int,
        val lastSeenAtMs: Long
    ) {
        val isFull: Boolean get() = currentPlayers >= maxPlayers
    }

    sealed class ProbeResult {
        object Available : ProbeResult()
        object NotFound : ProbeResult()
        data class Full(val current: Int, val max: Int) : ProbeResult()
        object Blocked : ProbeResult()
        object Offline : ProbeResult()
    }

    companion object {
        const val LOBBY_TOPIC = "glan/v24/lobby/live"
        private const val STALE_TIMEOUT_MS = 8_500L
        private val lobbyCipher = RoomCrypto.SessionCipher("GLANPUBLICLOBBYV24")
        private val probeRng = SecureRandom()

        fun buildOpenBeaconEncrypted(
            roomCode: String,
            roomTitle: String,
            hostName: String,
            currentPlayers: Int,
            maxPlayers: Int
        ): ByteArray {
            val cleanTitle = roomTitle.trim().ifBlank { "Sala de $hostName" }.take(28)
            val json = JSONObject().apply {
                put("open", true)
                put("code", RoomCrypto.normalizeRoomCode(roomCode))
                put("title", cleanTitle)
                put("host", hostName.take(24))
                put("cur", currentPlayers.coerceIn(1, TunnelProtocol.MAX_TOTAL_PLAYERS))
                put("max", maxPlayers.coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS))
            }
            return lobbyCipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        }

        fun buildCloseBeaconEncrypted(roomCode: String): ByteArray {
            val json = JSONObject().apply {
                put("open", false)
                put("code", RoomCrypto.normalizeRoomCode(roomCode))
            }
            return lobbyCipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private val active = AtomicBoolean(false)
    private val roomsMap = ConcurrentHashMap<String, PublicRoomEntry>()
    private val topicListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    @Volatile private var mqtt: MqttLightClient? = null
    @Volatile private var brokerConnected = false

    fun start() {
        if (!active.compareAndSet(false, true)) {
            dispatchSnapshot()
            return
        }

        val client = MqttLightClient(
            socketProtector = { true },
            onPacketReceived = { topic, encrypted ->
                if (topic == LOBBY_TOPIC) {
                    handleIncomingBeacon(encrypted)
                } else {
                    topicListeners[topic]?.invoke(encrypted)
                }
            },
            onConnectionStateChanged = { connected, _ ->
                brokerConnected = connected
                if (active.get()) dispatchSnapshot()
            }
        )
        mqtt = client
        client.start(listOf(LOBBY_TOPIC))

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
    }

    fun refreshNow() {
        if (!active.get()) {
            start()
            return
        }
        mqtt?.forceResubscribe(LOBBY_TOPIC)
        pruneStaleRoomsAndDispatch(forceDispatch = true)
    }

    fun removeRoomImmediately(rawCode: String) {
        val code = RoomCrypto.normalizeRoomCode(rawCode)
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
        if (code.length < 4) {
            onResult(ProbeResult.NotFound)
            return
        }

        if (!active.get()) {
            start()
        }

        Thread({
            val waitStart = System.currentTimeMillis()
            while (mqtt?.isConnected() != true && System.currentTimeMillis() - waitStart < 2_200L) {
                try {
                    Thread.sleep(80L)
                } catch (_: InterruptedException) {
                    onResult(ProbeResult.Offline)
                    return@Thread
                }
            }

            val client = mqtt
            if (client == null || !client.isConnected()) {
                onResult(ProbeResult.Offline)
                return@Thread
            }

            val topicRoot = "gdroidlan/v1/${RoomCrypto.deriveTopicHash(code)}"
            val h2cTopic = "$topicRoot/h2c/ctl"
            val c2hTopic = "$topicRoot/c2h/ctl"
            val roomCipher = RoomCrypto.SessionCipher(code)
            val probeId = probeRng.nextLong()
            val latch = CountDownLatch(1)
            val outcomeRef = AtomicReference<ProbeResult?>(null)

            topicListeners[h2cTopic] = { encrypted ->
                val plain = roomCipher.decrypt(encrypted)
                if (plain != null && plain.isNotEmpty()) {
                    when (plain[0]) {
                        TunnelProtocol.TYPE_PROBE_ACK -> {
                            val ack = TunnelProtocol.parseProbeAck(plain)
                            if (ack != null && ack.probeId == probeId) {
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
                            val roster = TunnelProtocol.parseRoster(plain)
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

            try {
                client.forceResubscribe(h2cTopic)
                val probePacket = roomCipher.encrypt(TunnelProtocol.buildProbeReq(probeId, accountUid))
                client.publish(c2hTopic, probePacket)

                val stepMs = 650L
                var elapsed = 0L
                while (elapsed < timeoutMs && outcomeRef.get() == null) {
                    val waitSlice = minOf(stepMs, timeoutMs - elapsed)
                    if (latch.await(waitSlice, TimeUnit.MILLISECONDS)) {
                        break
                    }
                    elapsed += waitSlice
                    if (outcomeRef.get() == null && elapsed < timeoutMs) {
                        val retryPkt = roomCipher.encrypt(TunnelProtocol.buildProbeReq(probeId, accountUid))
                        client.publish(c2hTopic, retryPkt)
                    }
                }
            } catch (_: Exception) {
            } finally {
                topicListeners.remove(h2cTopic)
                client.unsubscribe(h2cTopic)
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
    }

    private fun pruneStaleRoomsAndDispatch(forceDispatch: Boolean) {
        val now = System.currentTimeMillis()
        var changed = false
        val it = roomsMap.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastSeenAtMs > STALE_TIMEOUT_MS) {
                it.remove()
                changed = true
            }
        }
        if (changed || forceDispatch) {
            dispatchSnapshot()
        }
    }

    private fun handleIncomingBeacon(encrypted: ByteArray) {
        if (!active.get()) return
        val plain = lobbyCipher.decrypt(encrypted) ?: return
        try {
            val j = JSONObject(String(plain, Charsets.UTF_8))
            val code = RoomCrypto.normalizeRoomCode(j.optString("code", ""))
            if (code.length < 4) return

            val isOpen = j.optBoolean("open", true)
            if (!isOpen) {
                if (roomsMap.remove(code) != null) {
                    dispatchSnapshot()
                }
                return
            }

            val host = j.optString("host", "Anfitrião").ifBlank { "Anfitrião" }.take(24)
            val title = j.optString("title", "Sala de $host").ifBlank { "Sala de $host" }.take(28)
            val max = j.optInt("max", TunnelProtocol.MAX_TOTAL_PLAYERS)
                .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
            val cur = j.optInt("cur", 1).coerceIn(1, max)

            roomsMap[code] = PublicRoomEntry(
                roomCode = code,
                roomTitle = title,
                hostName = host,
                currentPlayers = cur,
                maxPlayers = max,
                lastSeenAtMs = System.currentTimeMillis()
            )
            dispatchSnapshot()
        } catch (_: Exception) { }
    }

    private fun dispatchSnapshot() {
        val sorted = roomsMap.values
            .sortedWith(compareBy<PublicRoomEntry> { it.isFull }.thenBy { it.roomTitle.lowercase() })
        onRoomsUpdated(sorted, brokerConnected)
    }
}
