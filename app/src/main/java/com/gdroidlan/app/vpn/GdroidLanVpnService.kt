package com.gdroidlan.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gdroidlan.app.MainActivity
import com.gdroidlan.app.R
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.net.MqttLightClient
import com.gdroidlan.app.net.PublicLobbyBrowser
import com.gdroidlan.app.net.StunClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GdroidLanVpnService : VpnService() {

    enum class StatusPhase {
        OFFLINE,
        CONNECTING,
        WAITING_FRIEND,
        ONLINE,
        ERROR
    }

    data class TunnelSnapshot(
        val phase: StatusPhase = StatusPhase.OFFLINE,
        val isHost: Boolean = true,
        val isPublicRoom: Boolean = false,
        val publicRoomTitle: String = "",
        val roomCode: String = "",
        val myNodeId: Int = 0,
        val myVirtualIp: String = "—",
        val hostVirtualIp: String = TunnelProtocol.HOST_VIRTUAL_IP,
        val statusText: String = "",
        val routeMode: String = "—",
        val pingMs: Long = -1L,
        val totalPlayersInRoom: Int = 0,
        val maxTotalPlayers: Int = TunnelProtocol.MAX_TOTAL_PLAYERS,
        val blockedCount: Int = 0,
        val members: List<TunnelProtocol.RoomMemberInfo> = emptyList(),
        val txBytes: Long = 0L,
        val rxBytes: Long = 0L
    )

    private class PeerNode(
        val nodeId: Int,
        @Volatile var sessionId: Long,
        @Volatile var accountUid: Long,
        @Volatile var displayName: String
    ) {
        val candidateEndpoints = CopyOnWriteArrayList<InetSocketAddress>()
        @Volatile var activeDirectEndpoint: InetSocketAddress? = null
        @Volatile var lastDirectRxAt: Long = 0L
        @Volatile var lastAnyRxAt: Long = System.currentTimeMillis()
        @Volatile var lastRttMs: Long = -1L

        fun isDirectActive(now: Long = System.currentTimeMillis()): Boolean =
            activeDirectEndpoint != null && (now - lastDirectRxAt) < 8_500L

        fun isAlive(now: Long = System.currentTimeMillis()): Boolean =
            (now - lastAnyRxAt) < 38_000L
    }

    companion object {
        const val ACTION_START_HOST = "com.gdroidlan.app.START_HOST"
        const val ACTION_START_CLIENT = "com.gdroidlan.app.START_CLIENT"
        const val ACTION_STOP = "com.gdroidlan.app.STOP"
        const val ACTION_MANUAL_PING = "com.gdroidlan.app.MANUAL_PING"
        const val ACTION_UPDATE_CONFIG = "com.gdroidlan.app.UPDATE_CONFIG"
        const val ACTION_KICK_PEER = "com.gdroidlan.app.KICK_PEER"
        const val ACTION_CLEAR_BLOCKS = "com.gdroidlan.app.CLEAR_BLOCKS"

        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_ACCOUNT_UID = "account_uid"
        const val EXTRA_MAX_PLAYERS = "max_players"
        const val EXTRA_IS_PUBLIC = "is_public"
        const val EXTRA_ROOM_TITLE = "room_title"
        const val EXTRA_TARGET_NODE_ID = "target_node_id"
        const val EXTRA_BLOCK_SESSION = "block_session"

        private const val CHANNEL_ID = "gdroid_lan_vpn_channel"
        private const val NOTIF_ID = 4101

        @Volatile
        var currentSnapshot: TunnelSnapshot = TunnelSnapshot()
            private set

        private val listeners = CopyOnWriteArrayList<(TunnelSnapshot) -> Unit>()
        @Volatile var onManualPongToast: ((Long, String) -> Unit)? = null
        @Volatile var onModerationEventToast: ((String) -> Unit)? = null

        fun addListener(l: (TunnelSnapshot) -> Unit) {
            listeners.add(l)
            l(currentSnapshot)
        }

        fun removeListener(l: (TunnelSnapshot) -> Unit) {
            listeners.remove(l)
        }

        private fun publishState(newState: TunnelSnapshot) {
            currentSnapshot = newState
            for (l in listeners) {
                try {
                    l(newState)
                } catch (_: Exception) { }
            }
        }
    }

    private val sessionRunning = AtomicBoolean(false)
    private val manualPingPending = AtomicBoolean(false)
    private val txBytesTotal = AtomicLong(0L)
    private val rxBytesTotal = AtomicLong(0L)
    private val tunWriteLock = Any()

    private var wakeLock: PowerManager.WakeLock? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunOutStream: FileOutputStream? = null
    private var udpSocket: DatagramSocket? = null
    private var mqttClient: MqttLightClient? = null

    private var isHostRole = true
    private var activeRoomCode = ""
    private var topicRoot = ""
    private var cipher: RoomCrypto.SessionCipher? = null

    @Volatile private var myDisplayName = "Jogador"
    @Volatile private var myAccountUid = 0L
    @Volatile private var maxTotalPlayers = TunnelProtocol.MAX_TOTAL_PLAYERS
    @Volatile private var isPublicRoom = false
    @Volatile private var publicRoomTitle = ""
    @Volatile private var hostDisplayNameOnClient = "Anfitrião"
    @Volatile private var remoteRosterCache: List<TunnelProtocol.RoomMemberInfo> = emptyList()

    @Volatile private var myNodeId: Int = 0
    private val clientSessionId: Long = SecureRandom().nextLong()
    @Volatile private var myPublicEndpoint: InetSocketAddress? = null
    @Volatile private var myLocalEndpoint: InetSocketAddress? = null
    @Volatile private var lastManualPingAtMs: Long = 0L

    private val peersByNode = ConcurrentHashMap<Int, PeerNode>()
    private val sessionToNode = ConcurrentHashMap<Long, Int>()
    private val blockedSessions = ConcurrentHashMap.newKeySet<Long>()
    private val blockedAccountUids = ConcurrentHashMap.newKeySet<Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnelSession(userRequested = true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_MANUAL_PING -> {
                val now = System.currentTimeMillis()
                if (sessionRunning.get() && now - lastManualPingAtMs >= 4_500L) {
                    lastManualPingAtMs = now
                    manualPingPending.set(true)
                    Thread({ sendImmediatePingToPeers() }, "gdroid-manual-ping").start()
                }
                return START_STICKY
            }

            ACTION_UPDATE_CONFIG -> {
                // Capacidade e visibilidade permanecem travadas enquanto a sala estiver ativa
                if (sessionRunning.get()) {
                    return START_STICKY
                }
                val newMax = intent.getIntExtra(EXTRA_MAX_PLAYERS, -1)
                if (newMax in TunnelProtocol.MIN_TOTAL_PLAYERS..TunnelProtocol.MAX_TOTAL_PLAYERS) {
                    maxTotalPlayers = newMax
                }
                if (intent.hasExtra(EXTRA_IS_PUBLIC)) {
                    isPublicRoom = intent.getBooleanExtra(EXTRA_IS_PUBLIC, isPublicRoom)
                }
                intent.getStringExtra(EXTRA_ROOM_TITLE)?.let {
                    publicRoomTitle = it.trim().take(28)
                }
                return START_STICKY
            }

            ACTION_KICK_PEER -> {
                if (sessionRunning.get() && isHostRole) {
                    val targetNode = intent.getIntExtra(EXTRA_TARGET_NODE_ID, 0)
                    val blockSession = intent.getBooleanExtra(EXTRA_BLOCK_SESSION, true)
                    if (targetNode in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) {
                        Thread({ kickPeerFromServer(targetNode, blockSession) }, "gdroid-kick").start()
                    }
                }
                return START_STICKY
            }

            ACTION_CLEAR_BLOCKS -> {
                blockedSessions.clear()
                blockedAccountUids.clear()
                if (sessionRunning.get()) {
                    refreshSnapshotUi()
                }
                return START_STICKY
            }

            ACTION_START_HOST, ACTION_START_CLIENT -> {
                val rawCode = intent.getStringExtra(EXTRA_ROOM_CODE).orEmpty()
                val cleanCode = RoomCrypto.normalizeRoomCode(rawCode)
                if (cleanCode.length < 4) {
                    publishState(
                        TunnelSnapshot(
                            phase = StatusPhase.ERROR,
                            statusText = "Código da sala inválido."
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                myDisplayName = intent.getStringExtra(EXTRA_PLAYER_NAME)
                    ?.trim()?.ifBlank { "Jogador" }?.take(24) ?: "Jogador"
                myAccountUid = intent.getLongExtra(EXTRA_ACCOUNT_UID, 0L)
                maxTotalPlayers = intent.getIntExtra(EXTRA_MAX_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
                    .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)

                val wantHost = (intent.action == ACTION_START_HOST)
                isPublicRoom = wantHost && intent.getBooleanExtra(EXTRA_IS_PUBLIC, false)
                publicRoomTitle = intent.getStringExtra(EXTRA_ROOM_TITLE).orEmpty().trim().take(28)
                startTunnelSession(wantHost, cleanCode)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnelSession(userRequested = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnelSession(userRequested = true)
        super.onDestroy()
    }

    // =========================================================================
    // CICLO DE VIDA DA SESSÃO
    // =========================================================================

    private fun startTunnelSession(asHost: Boolean, roomCode: String) {
        stopTunnelSession(userRequested = false)

        isHostRole = asHost
        activeRoomCode = roomCode
        topicRoot = "gdroidlan/v1/${RoomCrypto.deriveTopicHash(roomCode)}"
        cipher = RoomCrypto.SessionCipher(roomCode)
        peersByNode.clear()
        sessionToNode.clear()
        blockedSessions.clear()
        blockedAccountUids.clear()
        remoteRosterCache = emptyList()
        txBytesTotal.set(0L)
        rxBytesTotal.set(0L)
        myPublicEndpoint = null
        myNodeId = if (asHost) TunnelProtocol.HOST_NODE_ID else 0
        sessionRunning.set(true)

        acquireWakeLockSafe()
        startForegroundSafe(
            if (asHost) "Servidor ativo • Sala $roomCode (10.10.0.1)"
            else "Conectando na sala $roomCode..."
        )

        publishState(
            TunnelSnapshot(
                phase = StatusPhase.CONNECTING,
                isHost = asHost,
                isPublicRoom = isPublicRoom,
                publicRoomTitle = publicRoomTitle,
                roomCode = roomCode,
                myNodeId = myNodeId,
                myVirtualIp = if (asHost) TunnelProtocol.HOST_VIRTUAL_IP else "Negociando...",
                totalPlayersInRoom = if (asHost) 1 else 0,
                maxTotalPlayers = maxTotalPlayers,
                statusText = if (asHost) "Iniciando servidor da sala..." else "Conectando na sala $roomCode..."
            )
        )

        Thread({
            try {
                val dgram = DatagramSocket(0).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                    sendBufferSize = 256 * 1024
                }
                protect(dgram)
                udpSocket = dgram
                myLocalEndpoint = StunClient.findLocalLanAddress(dgram.localPort)
                startUdpReaderLoop(dgram)
                StunClient.sendBindingRequests(dgram)

                if (asHost) {
                    if (!establishTunInterface(TunnelProtocol.HOST_NODE_ID)) {
                        failAndStop("Permissão de VPN recusada ou indisponível no aparelho.")
                        return@Thread
                    }
                }

                val initialTopics = if (asHost) {
                    listOf("$topicRoot/c2h/ctl", "$topicRoot/c2h/pkt")
                } else {
                    listOf("$topicRoot/h2c/ctl", "$topicRoot/h2c/pkt/all")
                }

                val mqtt = MqttLightClient(
                    socketProtector = { sock -> protect(sock) },
                    onPacketReceived = { topic, encrypted -> handleMqttPacket(topic, encrypted) },
                    onConnectionStateChanged = { isConn, _ ->
                        if (!sessionRunning.get()) return@MqttLightClient
                        if (isConn) {
                            if (asHost) {
                                broadcastRosterFromHost()
                                refreshSnapshotUi()
                            } else if (myNodeId == 0) {
                                sendClientJoinRequest()
                            } else {
                                broadcastMyCandidates()
                            }
                        } else {
                            refreshSnapshotUi()
                        }
                    }
                )
                mqttClient = mqtt
                mqtt.start(initialTopics)

                startMaintenanceLoop()
            } catch (e: Exception) {
                failAndStop("Falha ao iniciar rede virtual: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "gdroid-session-init").start()
    }

    private fun establishTunInterface(nodeId: Int): Boolean {
        if (tunInterface != null) return true
        val virtualIp = TunnelProtocol.nodeIp(nodeId)
        val pfd = Builder()
            .setSession("gLAN ($activeRoomCode)")
            .setMtu(TunnelProtocol.VPN_MTU)
            .addAddress(virtualIp, 24)
            .addRoute("10.10.0.0", 24)
            .establish() ?: return false

        tunInterface = pfd
        tunOutStream = FileOutputStream(pfd.fileDescriptor)
        startTunReaderLoop(pfd)
        return true
    }

    // =========================================================================
    // MODERAÇÃO E LISTA DE PARTICIPANTES
    // =========================================================================

    private fun kickPeerFromServer(targetNode: Int, blockInSession: Boolean) {
        val peer = peersByNode.remove(targetNode) ?: return
        sessionToNode.remove(peer.sessionId)
        if (blockInSession) {
            blockedSessions.add(peer.sessionId)
            if (peer.accountUid != 0L) {
                blockedAccountUids.add(peer.accountUid)
            }
        }

        val reason = if (blockInSession) {
            "Você foi removido e bloqueado desta sala pelo anfitrião."
        } else {
            "Você foi removido da sala pelo anfitrião."
        }

        val kickEncrypted = cipher?.encrypt(
            TunnelProtocol.buildKick(peer.sessionId, targetNode, reason)
        )
        if (kickEncrypted != null) {
            repeat(3) {
                peer.activeDirectEndpoint?.let { ep -> sendUdpRaw(kickEncrypted, ep) }
                mqttClient?.publish("$topicRoot/h2c/ctl", kickEncrypted)
            }
        }

        broadcastRosterFromHost()
        refreshSnapshotUi()
        onModerationEventToast?.invoke("${peer.displayName} foi removido da sala.")
    }

    private fun buildHostMembersList(now: Long = System.currentTimeMillis()): List<TunnelProtocol.RoomMemberInfo> {
        val list = ArrayList<TunnelProtocol.RoomMemberInfo>()
        list.add(
            TunnelProtocol.RoomMemberInfo(
                nodeId = TunnelProtocol.HOST_NODE_ID,
                sessionId = 0L,
                accountUid = myAccountUid,
                displayName = myDisplayName,
                virtualIp = TunnelProtocol.HOST_VIRTUAL_IP,
                isHost = true,
                pingMs = 0L,
                routeLabel = "Anfitrião"
            )
        )
        val sortedClients = peersByNode.values
            .filter { it.isAlive(now) }
            .sortedBy { it.nodeId }
        for (p in sortedClients) {
            val route = if (p.isDirectActive(now)) "P2P" else "Ponte"
            list.add(
                TunnelProtocol.RoomMemberInfo(
                    nodeId = p.nodeId,
                    sessionId = p.sessionId,
                    accountUid = p.accountUid,
                    displayName = p.displayName,
                    virtualIp = TunnelProtocol.nodeIp(p.nodeId),
                    isHost = false,
                    pingMs = p.lastRttMs,
                    routeLabel = route
                )
            )
        }
        return list
    }

    private fun broadcastRosterFromHost() {
        if (!isHostRole || !sessionRunning.get()) return
        val members = buildHostMembersList()
        val enc = cipher?.encrypt(TunnelProtocol.buildRoster(maxTotalPlayers, members)) ?: return
        mqttClient?.publish("$topicRoot/h2c/ctl", enc)
        if (isPublicRoom) {
            publishPublicLobbyBeacon(currentCount = members.size)
        }
    }

    private fun publishPublicLobbyBeacon(currentCount: Int = -1) {
        if (!isHostRole || !isPublicRoom || !sessionRunning.get()) return
        val count = if (currentCount > 0) {
            currentCount
        } else {
            1 + peersByNode.values.count { it.isAlive() }
        }
        val beacon = PublicLobbyBrowser.buildOpenBeaconEncrypted(
            roomCode = activeRoomCode,
            roomTitle = publicRoomTitle,
            hostName = myDisplayName,
            currentPlayers = count,
            maxPlayers = maxTotalPlayers
        )
        mqttClient?.publish(PublicLobbyBrowser.LOBBY_TOPIC, beacon)
    }

    private fun publishPublicLobbyClose() {
        if (activeRoomCode.isBlank()) return
        val closeBeacon = PublicLobbyBrowser.buildCloseBeaconEncrypted(activeRoomCode)
        mqttClient?.publish(PublicLobbyBrowser.LOBBY_TOPIC, closeBeacon)
    }

    // =========================================================================
    // LEITURA TUN0 E UDP
    // =========================================================================

    private fun startTunReaderLoop(pfd: ParcelFileDescriptor) {
        Thread({
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(2048)
            while (sessionRunning.get() && tunInterface === pfd) {
                try {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    val dstNode = TunnelProtocol.inspectOutgoingIpv4Target(buf, n)
                    if (dstNode < 0 || dstNode == myNodeId) continue

                    val sender = myNodeId
                    if (sender <= 0) continue

                    val plainFrame = TunnelProtocol.buildIpv4Frame(sender, dstNode, buf, n)
                    val encrypted = cipher?.encrypt(plainFrame) ?: continue
                    txBytesTotal.addAndGet(n.toLong())

                    if (isHostRole) {
                        if (dstNode == TunnelProtocol.BROADCAST_NODE_ID) {
                            sendToAllClients(encrypted, isGamePacket = true)
                        } else {
                            sendToSpecificClient(dstNode, encrypted, isGamePacket = true)
                        }
                    } else {
                        sendFromClientToHost(encrypted, isGamePacket = true)
                    }
                } catch (_: Exception) {
                    if (!sessionRunning.get()) break
                }
            }
        }, "gdroid-tun-rx").start()
    }

    private fun writeIpv4ToTun(rawIpv4: ByteArray, offset: Int, length: Int) {
        if (length < 20) return
        synchronized(tunWriteLock) {
            try {
                tunOutStream?.write(rawIpv4, offset, length)
                rxBytesTotal.addAndGet(length.toLong())
            } catch (_: Exception) { }
        }
    }

    private fun startUdpReaderLoop(sock: DatagramSocket) {
        Thread({
            val buf = ByteArray(2048)
            val dp = DatagramPacket(buf, buf.size)
            while (sessionRunning.get() && udpSocket === sock && !sock.isClosed) {
                try {
                    dp.length = buf.size
                    sock.receive(dp)
                    val len = dp.length
                    if (len <= 0) continue

                    if (buf[0] == 0x01.toByte() && buf[1] == 0x01.toByte()) {
                        val mapped = StunClient.tryParseBindingResponse(buf, len)
                        if (mapped != null && mapped != myPublicEndpoint) {
                            myPublicEndpoint = mapped
                            if (myNodeId > 0) broadcastMyCandidates()
                        }
                        continue
                    }

                    val plain = cipher?.decrypt(buf, 0, len) ?: continue
                    val remoteAddr = InetSocketAddress(dp.address, dp.port)
                    handleDecryptedFrame(plain, viaUdpFrom = remoteAddr)
                } catch (_: Exception) {
                    if (!sessionRunning.get()) break
                }
            }
        }, "gdroid-udp-rx").apply { isDaemon = true }.start()
    }

    private fun handleMqttPacket(topic: String, encrypted: ByteArray) {
        if (!sessionRunning.get()) return
        val plain = cipher?.decrypt(encrypted) ?: return
        handleDecryptedFrame(plain, viaUdpFrom = null)
    }

    // =========================================================================
    // PROCESSAMENTO DE FRAMES
    // =========================================================================

    private fun handleDecryptedFrame(plain: ByteArray, viaUdpFrom: InetSocketAddress?) {
        if (plain.isEmpty() || !sessionRunning.get()) return
        val now = System.currentTimeMillis()

        when (plain[0]) {
            TunnelProtocol.TYPE_PROBE_REQ -> {
                if (!isHostRole) return
                val probe = TunnelProtocol.parseProbeReq(plain) ?: return
                val aliveTotal = 1 + peersByNode.values.count { it.isAlive(now) }
                val status = when {
                    probe.accountUid != 0L && blockedAccountUids.contains(probe.accountUid) ->
                        TunnelProtocol.PROBE_STATUS_BLOCKED
                    aliveTotal >= maxTotalPlayers ->
                        TunnelProtocol.PROBE_STATUS_FULL
                    else ->
                        TunnelProtocol.PROBE_STATUS_OK
                }
                val ack = cipher?.encrypt(
                    TunnelProtocol.buildProbeAck(probe.probeId, status, aliveTotal, maxTotalPlayers)
                ) ?: return
                mqttClient?.publish("$topicRoot/h2c/ctl", ack)
            }

            TunnelProtocol.TYPE_JOIN_REQ -> {
                if (!isHostRole) return
                val req = TunnelProtocol.parseJoinReq(plain) ?: return

                if (blockedSessions.contains(req.sessionId) ||
                    (req.accountUid != 0L && blockedAccountUids.contains(req.accountUid))
                ) {
                    val rej = cipher?.encrypt(
                        TunnelProtocol.buildJoinReject(
                            req.sessionId,
                            "Entrada recusada: sua conta foi removida desta sala pelo anfitrião."
                        )
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                val maxGuests = (maxTotalPlayers - 1).coerceIn(1, 7)
                val existingNode = sessionToNode[req.sessionId]?.takeIf { peersByNode[it]?.isAlive(now) == true }
                val aliveGuests = peersByNode.values.count { it.isAlive(now) }
                if (existingNode == null && aliveGuests >= maxGuests) {
                    val rej = cipher?.encrypt(
                        TunnelProtocol.buildJoinReject(
                            req.sessionId,
                            "Sala cheia ($maxTotalPlayers/$maxTotalPlayers jogadores)."
                        )
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                val assigned = existingNode ?: assignNodeForClient(req.sessionId)
                if (assigned < 0) {
                    val rej = cipher?.encrypt(
                        TunnelProtocol.buildJoinReject(req.sessionId, "Sala cheia ($maxTotalPlayers/$maxTotalPlayers jogadores).")
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                val isBrandNewJoin = (existingNode == null)
                val peer = peersByNode.getOrPut(assigned) {
                    PeerNode(assigned, req.sessionId, req.accountUid, req.displayName)
                }
                peer.sessionId = req.sessionId
                peer.accountUid = req.accountUid
                peer.displayName = req.displayName
                peer.lastAnyRxAt = now
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                val ack = cipher?.encrypt(
                    TunnelProtocol.buildJoinAck(req.sessionId, assigned, maxTotalPlayers, myDisplayName)
                ) ?: return
                mqttClient?.publish("$topicRoot/h2c/ctl", ack)
                broadcastMyCandidates()
                broadcastRosterFromHost()
                refreshSnapshotUi()

                if (isBrandNewJoin) {
                    onModerationEventToast?.invoke("${req.displayName} entrou na sala.")
                }
            }

            TunnelProtocol.TYPE_JOIN_ACK -> {
                if (isHostRole) return
                val ack = TunnelProtocol.parseJoinAck(plain) ?: return
                if (ack.sessionId != clientSessionId) return
                if (ack.assignedNodeId !in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) return

                maxTotalPlayers = ack.maxPlayers
                hostDisplayNameOnClient = ack.hostName

                val hostPeer = peersByNode.getOrPut(TunnelProtocol.HOST_NODE_ID) {
                    PeerNode(TunnelProtocol.HOST_NODE_ID, 0L, 0L, ack.hostName)
                }
                hostPeer.displayName = ack.hostName
                hostPeer.lastAnyRxAt = now

                if (myNodeId == 0) {
                    myNodeId = ack.assignedNodeId
                    mqttClient?.subscribe("$topicRoot/h2c/pkt/${ack.assignedNodeId}")
                    if (!establishTunInterface(ack.assignedNodeId)) {
                        failAndStop("Não foi possível criar a interface VPN no Android.")
                        return
                    }
                    broadcastMyCandidates()
                    sendImmediatePingToPeers()
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_JOIN_REJECT -> {
                if (isHostRole) return
                val rej = TunnelProtocol.parseRejectOrKick(plain) ?: return
                if (rej.sessionId == clientSessionId) {
                    failAndStop(rej.reason)
                }
            }

            TunnelProtocol.TYPE_KICK -> {
                if (isHostRole) return
                val kick = TunnelProtocol.parseRejectOrKick(plain) ?: return
                if (kick.sessionId == clientSessionId || (myNodeId > 0 && kick.targetNodeId == myNodeId)) {
                    failAndStop(kick.reason)
                }
            }

            TunnelProtocol.TYPE_ROSTER -> {
                if (isHostRole) return
                val roster = TunnelProtocol.parseRoster(plain) ?: return
                maxTotalPlayers = roster.maxPlayers
                remoteRosterCache = roster.members
                peersByNode[TunnelProtocol.HOST_NODE_ID]?.lastAnyRxAt = now
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_CANDIDATES -> {
                if (plain.size < 3) return
                val senderNode = plain[1].toInt() and 0xFF
                if (senderNode == myNodeId || senderNode <= 0) return
                if (!isHostRole && senderNode != TunnelProtocol.HOST_NODE_ID) return

                val peer = if (isHostRole) {
                    peersByNode[senderNode] ?: return
                } else {
                    peersByNode.getOrPut(senderNode) {
                        PeerNode(senderNode, 0L, 0L, hostDisplayNameOnClient)
                    }
                }
                peer.lastAnyRxAt = now
                val endpoints = TunnelProtocol.parseCandidatesEndpoints(plain.copyOfRange(2, plain.size))
                for (ep in endpoints) {
                    if (ep !in peer.candidateEndpoints) {
                        peer.candidateEndpoints.add(ep)
                    }
                }
                punchUdpTowardPeer(peer)
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_PING -> {
                if (plain.size < 11 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val targetNode = plain[2].toInt() and 0xFF
                if (senderNode == myNodeId) return
                if (targetNode != myNodeId && targetNode != TunnelProtocol.BROADCAST_NODE_ID) return
                val sentAt = ByteBuffer.wrap(plain, 3, 8).long

                val peer = if (isHostRole) {
                    peersByNode[senderNode] ?: return
                } else {
                    peersByNode.getOrPut(senderNode) {
                        PeerNode(senderNode, 0L, 0L, hostDisplayNameOnClient)
                    }
                }
                peer.lastAnyRxAt = now
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                val pong = cipher?.encrypt(TunnelProtocol.buildPong(myNodeId, senderNode, sentAt)) ?: return
                if (viaUdpFrom != null) {
                    sendUdpRaw(pong, viaUdpFrom)
                } else if (isHostRole) {
                    sendToSpecificClient(senderNode, pong, isGamePacket = false)
                } else {
                    sendFromClientToHost(pong, isGamePacket = false)
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_PONG -> {
                if (plain.size < 11 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val targetNode = plain[2].toInt() and 0xFF
                if (targetNode != myNodeId) return
                val sentAt = ByteBuffer.wrap(plain, 3, 8).long
                val rtt = (now - sentAt).coerceIn(1L, 9_999L)

                val peer = if (isHostRole) {
                    peersByNode[senderNode] ?: return
                } else {
                    peersByNode.getOrPut(senderNode) {
                        PeerNode(senderNode, 0L, 0L, hostDisplayNameOnClient)
                    }
                }
                peer.lastAnyRxAt = now
                peer.lastRttMs = rtt
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                if (manualPingPending.compareAndSet(true, false)) {
                    val mode = if (peer.isDirectActive(now)) "P2P Direto" else "Ponte CGNAT"
                    onManualPongToast?.invoke(rtt, mode)
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_IPV4_PKT -> {
                if (plain.size <= 23 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val dstNode = plain[2].toInt() and 0xFF
                if (senderNode == myNodeId) return

                val peer = if (isHostRole) {
                    peersByNode[senderNode] ?: return
                } else {
                    peersByNode.getOrPut(senderNode) {
                        PeerNode(senderNode, 0L, 0L, hostDisplayNameOnClient)
                    }
                }
                peer.lastAnyRxAt = now
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                val payloadLen = plain.size - 3
                if (isHostRole) {
                    when {
                        dstNode == TunnelProtocol.HOST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                        }
                        dstNode == TunnelProtocol.BROADCAST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                            val reEnc = cipher?.encrypt(plain) ?: return
                            peersByNode.keys().toList()
                                .filter { it != senderNode }
                                .forEach { otherId -> sendToSpecificClient(otherId, reEnc, isGamePacket = true) }
                        }
                        dstNode in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID -> {
                            if (peersByNode.containsKey(dstNode)) {
                                val reEnc = cipher?.encrypt(plain) ?: return
                                sendToSpecificClient(dstNode, reEnc, isGamePacket = true)
                            }
                        }
                    }
                } else {
                    if (dstNode == myNodeId || dstNode == TunnelProtocol.BROADCAST_NODE_ID) {
                        writeIpv4ToTun(plain, 3, payloadLen)
                    }
                }
            }

            TunnelProtocol.TYPE_BYE -> {
                if (plain.size < 2) return
                val senderNode = plain[1].toInt() and 0xFF
                if (isHostRole) {
                    val removed = peersByNode.remove(senderNode)
                    if (removed != null) {
                        sessionToNode.remove(removed.sessionId)
                        broadcastRosterFromHost()
                        refreshSnapshotUi()
                    }
                } else if (senderNode == TunnelProtocol.HOST_NODE_ID) {
                    failAndStop("O anfitrião encerrou a sala $activeRoomCode.")
                }
            }
        }
    }

    // =========================================================================
    // ENVIO HÍBRIDO (UDP P2P -> PONTE CGNAT)
    // =========================================================================

    private fun assignNodeForClient(sessionId: Long): Int {
        sessionToNode[sessionId]?.let { return it }
        val now = System.currentTimeMillis()
        for (candidate in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) {
            val existing = peersByNode[candidate]
            if (existing == null || !existing.isAlive(now)) {
                sessionToNode[sessionId] = candidate
                return candidate
            }
        }
        return -1
    }

    private fun sendUdpRaw(encrypted: ByteArray, target: InetSocketAddress): Boolean {
        val sock = udpSocket ?: return false
        if (sock.isClosed) return false
        return try {
            sock.send(DatagramPacket(encrypted, encrypted.size, target.address, target.port))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendToSpecificClient(clientNode: Int, encrypted: ByteArray, isGamePacket: Boolean) {
        val peer = peersByNode[clientNode] ?: return
        val direct = peer.activeDirectEndpoint
        if (direct != null && peer.isDirectActive() && sendUdpRaw(encrypted, direct)) {
            return
        }
        val topic = if (isGamePacket) "$topicRoot/h2c/pkt/$clientNode" else "$topicRoot/h2c/ctl"
        mqttClient?.publish(topic, encrypted)
    }

    private fun sendToAllClients(encrypted: ByteArray, isGamePacket: Boolean) {
        val alivePeers = peersByNode.values.filter { it.isAlive() }
        if (alivePeers.isEmpty()) return
        var anyNeedsBridge = false
        for (p in alivePeers) {
            val direct = p.activeDirectEndpoint
            if (direct != null && p.isDirectActive()) {
                if (!sendUdpRaw(encrypted, direct)) anyNeedsBridge = true
            } else {
                anyNeedsBridge = true
            }
        }
        if (anyNeedsBridge) {
            val topic = if (isGamePacket) "$topicRoot/h2c/pkt/all" else "$topicRoot/h2c/ctl"
            mqttClient?.publish(topic, encrypted)
        }
    }

    private fun sendFromClientToHost(encrypted: ByteArray, isGamePacket: Boolean) {
        val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
        val direct = hostPeer?.activeDirectEndpoint
        if (hostPeer != null && direct != null && hostPeer.isDirectActive() && sendUdpRaw(encrypted, direct)) {
            return
        }
        val topic = if (isGamePacket) "$topicRoot/c2h/pkt" else "$topicRoot/c2h/ctl"
        mqttClient?.publish(topic, encrypted)
    }

    private fun sendClientJoinRequest() {
        val enc = cipher?.encrypt(
            TunnelProtocol.buildJoinReq(clientSessionId, myAccountUid, myDisplayName)
        ) ?: return
        mqttClient?.publish("$topicRoot/c2h/ctl", enc)
    }

    private fun broadcastMyCandidates() {
        val me = myNodeId
        if (me <= 0) return
        udpSocket?.let { myLocalEndpoint = StunClient.findLocalLanAddress(it.localPort) }
        val frame = TunnelProtocol.buildCandidates(me, myPublicEndpoint, myLocalEndpoint)
        val enc = cipher?.encrypt(frame) ?: return
        val topic = if (isHostRole) "$topicRoot/h2c/ctl" else "$topicRoot/c2h/ctl"
        mqttClient?.publish(topic, enc)
    }

    private fun punchUdpTowardPeer(peer: PeerNode) {
        val me = myNodeId
        if (me <= 0) return
        val probe = cipher?.encrypt(
            TunnelProtocol.buildPing(me, peer.nodeId, System.currentTimeMillis())
        ) ?: return
        for (ep in peer.candidateEndpoints) {
            sendUdpRaw(probe, ep)
        }
    }

    private fun sendImmediatePingToPeers() {
        val me = myNodeId
        if (me <= 0) return
        val now = System.currentTimeMillis()
        if (isHostRole) {
            for (p in peersByNode.values.filter { it.isAlive(now) }) {
                val enc = cipher?.encrypt(TunnelProtocol.buildPing(me, p.nodeId, now)) ?: continue
                if (!p.isDirectActive(now)) punchUdpTowardPeer(p)
                sendToSpecificClient(p.nodeId, enc, isGamePacket = false)
            }
        } else {
            val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
            val enc = cipher?.encrypt(TunnelProtocol.buildPing(me, TunnelProtocol.HOST_NODE_ID, now)) ?: return
            if (hostPeer != null && !hostPeer.isDirectActive(now)) {
                punchUdpTowardPeer(hostPeer)
            }
            sendFromClientToHost(enc, isGamePacket = false)
        }
    }

    // =========================================================================
    // LOOP DE MANUTENÇÃO E UI
    // =========================================================================

    private fun startMaintenanceLoop() {
        Thread({
            val startedAt = System.currentTimeMillis()
            var tick = 0
            while (sessionRunning.get()) {
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!sessionRunning.get()) break
                tick++
                val now = System.currentTimeMillis()

                if (!isHostRole && myNodeId == 0) {
                    if (now - startedAt > 6_000L) {
                        failAndStop("Essa sala não existe ou já foi encerrada.")
                        break
                    }
                    sendClientJoinRequest()
                    continue
                }

                if (!isHostRole && myNodeId > 0) {
                    val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
                    if (hostPeer != null && !hostPeer.isAlive(now)) {
                        failAndStop("A sala foi encerrada pelo anfitrião.")
                        break
                    }
                }

                if (tick % 8 == 1) {
                    udpSocket?.let { StunClient.sendBindingRequests(it) }
                }

                if (isHostRole) {
                    var removedAny = false
                    val it = peersByNode.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        if (!e.value.isAlive(now)) {
                            sessionToNode.remove(e.value.sessionId)
                            it.remove()
                            removedAny = true
                        }
                    }
                    if (removedAny || tick % 2 == 0) {
                        broadcastRosterFromHost()
                    } else if (isPublicRoom) {
                        publishPublicLobbyBeacon()
                    }
                }

                if (tick % 3 == 0) {
                    broadcastMyCandidates()
                }
                sendImmediatePingToPeers()
                refreshSnapshotUi()
            }
        }, "gdroid-maint-loop").apply { isDaemon = true }.start()
    }

    private fun refreshSnapshotUi() {
        if (!sessionRunning.get()) return
        val now = System.currentTimeMillis()
        val mqttOk = mqttClient?.isConnected() == true
        val alivePeers = peersByNode.values.filter { it.isAlive(now) }
        val anyDirect = alivePeers.any { it.isDirectActive(now) }
        val bestPing = alivePeers.map { it.lastRttMs }.filter { it > 0 }.minOrNull() ?: -1L

        val myIp = if (myNodeId > 0) TunnelProtocol.nodeIp(myNodeId) else "Negociando..."
        val phase: StatusPhase
        val detail: String
        val routeLabel: String
        val membersList: List<TunnelProtocol.RoomMemberInfo>

        if (isHostRole) {
            membersList = buildHostMembersList(now)
            if (alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = if (anyDirect) "P2P" else "Ponte"
                detail = "Sala ativa. No GDroid, todos entram no IP 10.10.0.1."
            } else if (mqttOk) {
                phase = StatusPhase.WAITING_FRIEND
                routeLabel = "Pronto"
                detail = if (isPublicRoom) {
                    "Sala pública aberta e visível na lista para todos."
                } else {
                    "Sala privada pronta. Envie o código $activeRoomCode para entrarem."
                }
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "..."
                detail = "Registrando sala $activeRoomCode..."
            }
        } else {
            if (myNodeId > 0 && alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = if (anyDirect) "P2P" else "Ponte"
                detail = "Conectado na sala de $hostDisplayNameOnClient. Entre no IP 10.10.0.1 no GDroid."
                membersList = if (remoteRosterCache.isNotEmpty()) {
                    remoteRosterCache
                } else {
                    listOf(
                        TunnelProtocol.RoomMemberInfo(
                            nodeId = TunnelProtocol.HOST_NODE_ID,
                            sessionId = 0L,
                            accountUid = 0L,
                            displayName = hostDisplayNameOnClient,
                            virtualIp = TunnelProtocol.HOST_VIRTUAL_IP,
                            isHost = true,
                            pingMs = bestPing,
                            routeLabel = routeLabel
                        ),
                        TunnelProtocol.RoomMemberInfo(
                            nodeId = myNodeId,
                            sessionId = clientSessionId,
                            accountUid = myAccountUid,
                            displayName = myDisplayName,
                            virtualIp = myIp,
                            isHost = false,
                            pingMs = 0L,
                            routeLabel = "Você"
                        )
                    )
                }
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "..."
                detail = "Procurando sala $activeRoomCode..."
                membersList = emptyList()
            }
        }

        val totalInRoom = membersList.size.coerceAtLeast(if (isHostRole) 1 else 0)

        publishState(
            TunnelSnapshot(
                phase = phase,
                isHost = isHostRole,
                isPublicRoom = isPublicRoom,
                publicRoomTitle = publicRoomTitle,
                roomCode = activeRoomCode,
                myNodeId = myNodeId,
                myVirtualIp = myIp,
                hostVirtualIp = TunnelProtocol.HOST_VIRTUAL_IP,
                statusText = detail,
                routeMode = routeLabel,
                pingMs = bestPing,
                totalPlayersInRoom = totalInRoom,
                maxTotalPlayers = maxTotalPlayers,
                blockedCount = blockedSessions.size,
                members = membersList,
                txBytes = txBytesTotal.get(),
                rxBytes = rxBytesTotal.get()
            )
        )
    }

    private fun failAndStop(errorMsg: String) {
        stopTunnelSession(userRequested = false)
        publishState(
            TunnelSnapshot(
                phase = StatusPhase.ERROR,
                isHost = isHostRole,
                roomCode = activeRoomCode,
                maxTotalPlayers = maxTotalPlayers,
                statusText = errorMsg
            )
        )
        stopSelf()
    }

    private fun stopTunnelSession(userRequested: Boolean) {
        val wasRunning = sessionRunning.getAndSet(false)
        if (wasRunning && myNodeId > 0) {
            try {
                if (isHostRole && isPublicRoom) {
                    publishPublicLobbyClose()
                }
                val bye = cipher?.encrypt(TunnelProtocol.buildBye(myNodeId))
                if (bye != null) {
                    val t = if (isHostRole) "$topicRoot/h2c/ctl" else "$topicRoot/c2h/ctl"
                    mqttClient?.publish(t, bye)
                }
            } catch (_: Exception) { }
        }

        mqttClient?.stop()
        mqttClient = null

        try {
            udpSocket?.close()
        } catch (_: Exception) { }
        udpSocket = null

        synchronized(tunWriteLock) {
            try {
                tunOutStream?.close()
            } catch (_: Exception) { }
            tunOutStream = null
            try {
                tunInterface?.close()
            } catch (_: Exception) { }
            tunInterface = null
        }

        releaseWakeLockSafe()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }

        if (userRequested) {
            publishState(TunnelSnapshot(maxTotalPlayers = maxTotalPlayers))
        }
    }

    // =========================================================================
    // NOTIFICAÇÃO E WAKELOCK
    // =========================================================================

    private fun startForegroundSafe(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "gLAN Rede Virtual",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a rede virtual LAN ativa durante a partida"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, GdroidLanVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("gLAN Ativo")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Desconectar", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
        }
    }

    private fun acquireWakeLockSafe() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gdroidlan:tunnel").apply {
                setReferenceCounted(false)
                acquire(6L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLockSafe() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) { }
        wakeLock = null
    }
}
