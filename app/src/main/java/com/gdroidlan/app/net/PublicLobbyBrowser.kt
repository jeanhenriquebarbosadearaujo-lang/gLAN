package com.gdroidlan.app.net

import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.vpn.TunnelProtocol
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diretório em tempo real de Salas Públicas Abertas:
 * - Anfitriões públicos enviam beacons criptografados a cada 2s em LOBBY_TOPIC.
 * - Clientes escutam LOBBY_TOPIC e exibem todas as salas abertas instantaneamente,
 *   removendo salas encerradas na hora ou após 8.5s sem sinal.
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

    companion object {
        const val LOBBY_TOPIC = "glan/v24/lobby/live"
        private const val STALE_TIMEOUT_MS = 8_500L
        private val lobbyCipher = RoomCrypto.SessionCipher("GLANPUBLICLOBBYV24")

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
    @Volatile private var mqtt: MqttLightClient? = null
    @Volatile private var brokerConnected = false

    fun start() {
        if (!active.compareAndSet(false, true)) {
            dispatchSnapshot()
            return
        }

        val client = MqttLightClient(
            socketProtector = { /* Fora da VPN ativa */ },
            onPacketReceived = { _, encrypted -> handleIncomingBeacon(encrypted) },
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
                if (changed) {
                    dispatchSnapshot()
                }
            }
        }, "glan-lobby-pruner").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        brokerConnected = false
        mqtt?.stop()
        mqtt = null
        roomsMap.clear()
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
