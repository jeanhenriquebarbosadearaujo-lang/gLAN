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
import com.gdroidlan.app.update.OfficialVersionCert
import com.gdroidlan.app.update.UpdateNotifier
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.PublicKey
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        val blockedNames: List<String> = emptyList(),
        val members: List<TunnelProtocol.RoomMemberInfo> = emptyList(),
        val txBytes: Long = 0L,
        val rxBytes: Long = 0L
    )

    private class PeerNode(
        val nodeId: Int,
        @Volatile var sessionId: Long,
        @Volatile var accountUid: Long,
        @Volatile var displayName: String,
        @Volatile var pairwiseCipher: RoomCrypto.PairwiseCipher
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
        const val ACTION_KICK_PEER = "com.gdroidlan.app.KICK_PEER"
        const val ACTION_CLEAR_BLOCKS = "com.gdroidlan.app.CLEAR_BLOCKS"

        const val EXTRA_CALLER_TOKEN = "caller_token"
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_ACCOUNT_UID = "account_uid"
        const val EXTRA_MAX_PLAYERS = "max_players"
        const val EXTRA_IS_PUBLIC = "is_public"
        const val EXTRA_ROOM_TITLE = "room_title"
        const val EXTRA_TARGET_NODE_ID = "target_node_id"
        const val EXTRA_BLOCK_SESSION = "block_session"

        val INTERNAL_CALLER_TOKEN: String = UUID.randomUUID().toString()

        private const val CHANNEL_ID = "gdroid_lan_vpn_channel"
        private const val NOTIF_ID = 4101
        private const val MAX_HANDSHAKE_PACKETS_PER_SEC = 18

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
    private val sessionGeneration = AtomicLong(0L)
    private val manualPingPending = AtomicBoolean(false)
    private val txBytesTotal = AtomicLong(0L)
    private val rxBytesTotal = AtomicLong(0L)
    private val tunWriteLock = Any()
    private val rng = SecureRandom()

    private val handshakeWindowSec = AtomicLong(0L)
    private val handshakeCountInSec = AtomicInteger(0)

    private var wakeLock: PowerManager.WakeLock? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunOutStream: FileOutputStream? = null
    private var udpSocket: DatagramSocket? = null
    private var mqttClient: MqttLightClient? = null

    private var isHostRole = true
    private var activeRoomCode = ""
    private var topicRoot = ""
    private var roomCipher: RoomCrypto.SessionCipher? = null
    @Volatile private var sessionIdentity: RoomCrypto.IdentityKeyBox? = null
    @Volatile private var clientToHostPairwise: RoomCrypto.PairwiseCipher? = null
    @Volatile private var pinnedHostPubKey: PublicKey? = null

    @Volatile private var myDisplayName = "Jogador"
    @Volatile private var myAccountUid = 0L
    @Volatile private var maxTotalPlayers = TunnelProtocol.MAX_TOTAL_PLAYERS
    @Volatile private var isPublicRoom = false
    @Volatile private var publicRoomTitle = ""
    @Volatile private var hostDisplayNameOnClient = "Anfitrião"
    @Volatile private var remoteRosterCache: List<TunnelProtocol.RoomMemberInfo> = emptyList()

    @Volatile private var myNodeId: Int = 0
    @Volatile private var clientSessionId: Long = rng.nextLong()
    @Volatile private var myPublicEndpoint: InetSocketAddress? = null
    @Volatile private var myLocalEndpoint: InetSocketAddress? = null
    @Volatile private var lastManualPingAtMs: Long = 0L

    private val peersByNode = ConcurrentHashMap<Int, PeerNode>()
    private val sessionToNode = ConcurrentHashMap<Long, Int>()
    private val blockedSessions = ConcurrentHashMap.newKeySet<Long>()
    private val blockedAccountUids = ConcurrentHashMap.newKeySet<Long>()
    private val blockedPlayerNames = ConcurrentHashMap<String, String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val token = intent.getStringExtra(EXTRA_CALLER_TOKEN)
        if (token != INTERNAL_CALLER_TOKEN) {
            return START_NOT_STICKY
        }

        when (intent.action) {
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
                blockedPlayerNames.clear()
                if (sessionRunning.get()) {
                    refreshSnapshotUi()
                }
                return START_STICKY
            }

            ACTION_START_HOST, ACTION_START_CLIENT -> {
                startForegroundSafe("Inicializando túnel seguro...")

                val rawCode = intent.getStringExtra(EXTRA_ROOM_CODE).orEmpty()
                val cleanCode = RoomCrypto.normalizeRoomCode(rawCode)
                if (cleanCode.length < 4 || cleanCode.length > 12) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    publishState(
                        TunnelSnapshot(
                            phase = StatusPhase.ERROR,
                            statusText = "Código da sala inválido."
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                myDisplayName = RoomCrypto.cleanSafeText(
                    intent.getStringExtra(EXTRA_PLAYER_NAME).orEmpty(),
                    24
                ).ifBlank { "Jogador" }
                myAccountUid = intent.getLongExtra(EXTRA_ACCOUNT_UID, 0L)
                maxTotalPlayers = intent.getIntExtra(EXTRA_MAX_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
                    .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)

                val wantHost = (intent.action == ACTION_START_HOST)
                isPublicRoom = wantHost && intent.getBooleanExtra(EXTRA_IS_PUBLIC, false)
                publicRoomTitle = RoomCrypto.cleanSafeText(
                    intent.getStringExtra(EXTRA_ROOM_TITLE).orEmpty(),
                    28
                )
                startTunnelSession(wantHost, cleanCode)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnelSession(userRequested = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnelSession(userRequested = true)
        super.onDestroy()
    }

    private fun allowHandshakeRate(nowMs: Long): Boolean {
        val sec = nowMs / 1000L
        val prevSec = handshakeWindowSec.get()
        if (sec != prevSec && handshakeWindowSec.compareAndSet(prevSec, sec)) {
            handshakeCountInSec.set(0)
        }
        return handshakeCountInSec.incrementAndGet() <= MAX_HANDSHAKE_PACKETS_PER_SEC
    }

    // =========================================================================
    // CICLO DE VIDA DA SESSÃO
    // =========================================================================

    private fun startTunnelSession(asHost: Boolean, roomCode: String) {
        stopTunnelSession(userRequested = false)

        val gen = sessionGeneration.incrementAndGet()
        isHostRole = asHost
        activeRoomCode = roomCode
        topicRoot = "gdroidlan/v26/${RoomCrypto.deriveTopicHash(roomCode)}"
        roomCipher = RoomCrypto.SessionCipher(roomCode)
        sessionIdentity = RoomCrypto.IdentityKeyBox()
        clientToHostPairwise = null
        pinnedHostPubKey = null
        clientSessionId = rng.nextLong()

        peersByNode.clear()
        sessionToNode.clear()
        blockedSessions.clear()
        blockedAccountUids.clear()
        blockedPlayerNames.clear()
        remoteRosterCache = emptyList()
        txBytesTotal.set(0L)
        rxBytesTotal.set(0L)
        myPublicEndpoint = null
        myNodeId = if (asHost) TunnelProtocol.HOST_NODE_ID else 0
        sessionRunning.set(true)

        acquireWakeLockSafe()
        startForegroundSafe(
            if (asHost) "Servidor seguro • Sala $roomCode (10.10.0.1)"
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
                statusText = if (asHost) "Iniciando servidor seguro..." else "Autenticando na sala $roomCode..."
            )
        )

        Thread({
            try {
                if (sessionGeneration.get() != gen || !sessionRunning.get()) return@Thread

                val dgram = DatagramSocket(0).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                    sendBufferSize = 256 * 1024
                }
                protect(dgram)
                udpSocket = dgram
                myLocalEndpoint = StunClient.findLocalLanAddress(dgram.localPort)
                startUdpReaderLoop(dgram, gen)
                StunClient.sendBindingRequests(dgram)

                if (asHost) {
                    if (!establishTunInterface(TunnelProtocol.HOST_NODE_ID, gen)) {
                        failAndStop("Permissão de VPN recusada ou indisponível no aparelho.")
                        return@Thread
                    }
                }

                val initialTopics = if (asHost) {
                    listOf("$topicRoot/c2h/ctl", "$topicRoot/c2h/pkt", UpdateNotifier.VERSION_TOPIC)
                } else {
                    listOf("$topicRoot/h2c/ctl", UpdateNotifier.VERSION_TOPIC)
                }

                val mqtt = MqttLightClient(
                    socketProtector = { sock -> protect(sock) },
                    onPacketReceived = { topic, encrypted ->
                        if (sessionGeneration.get() == gen && sessionRunning.get()) {
                            if (topic == UpdateNotifier.VERSION_TOPIC) {
                                UpdateNotifier.parseEncryptedAnnouncement(encrypted)?.let { rel ->
                                    UpdateNotifier.onVerifiedReleaseObserved(
                                        rel,
                                        OfficialVersionCert.BUILT_VERSION_CODE
                                    )
                                }
                            } else {
                                handleEncryptedNetworkPacket(encrypted, viaUdpFrom = null)
                            }
                        }
                    },
                    onConnectionStateChanged = { isConn, _ ->
                        if (sessionGeneration.get() != gen || !sessionRunning.get()) return@MqttLightClient
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
                if (sessionGeneration.get() != gen || !sessionRunning.get()) {
                    mqtt.stop()
                    return@Thread
                }
                mqttClient = mqtt
                mqtt.start(initialTopics)

                startMaintenanceLoop(gen)
            } catch (e: Exception) {
                if (sessionGeneration.get() == gen && sessionRunning.get()) {
                    failAndStop("Falha ao iniciar rede virtual: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }, "gdroid-session-init").start()
    }

    private fun establishTunInterface(nodeId: Int, gen: Long): Boolean {
        if (tunInterface != null) return true
        val virtualIp = TunnelProtocol.nodeIp(nodeId)
        val pfd = Builder()
            .setSession("gLAN ($activeRoomCode)")
            .setMtu(TunnelProtocol.VPN_MTU)
            .setBlocking(true)
            .addAddress(virtualIp, 24)
            .addRoute("10.10.0.0", 24)
            .establish() ?: return false

        tunInterface = pfd
        tunOutStream = FileOutputStream(pfd.fileDescriptor)
        startTunReaderLoop(pfd, gen)
        return true
    }

    // =========================================================================
    // MODERAÇÃO (EXPULSAR / BLOQUEAR) E LISTA DE PARTICIPANTES
    // =========================================================================

    private fun kickPeerFromServer(targetNode: Int, blockInSession: Boolean) {
        val peer = peersByNode.remove(targetNode) ?: return
        sessionToNode.remove(peer.sessionId)
        if (blockInSession) {
            blockedSessions.add(peer.sessionId)
            if (peer.accountUid != 0L) {
                blockedAccountUids.add(peer.accountUid)
            }
            val blockKey = if (peer.accountUid != 0L) "uid:${peer.accountUid}" else "sid:${peer.sessionId}"
            blockedPlayerNames[blockKey] = peer.displayName
        }

        val reason = if (blockInSession) {
            "Você foi expulso e bloqueado desta sala pelo anfitrião."
        } else {
            "Você foi expulso da sala pelo anfitrião."
        }

        val kickPlain = TunnelProtocol.buildKick(peer.sessionId, targetNode, reason)
        val kickPairwise = peer.pairwiseCipher.encrypt(TunnelProtocol.HOST_NODE_ID, kickPlain)

        val signedKickEnc = sessionIdentity?.let { hostId ->
            roomCipher?.encrypt(
                TunnelProtocol.buildSignedKick(
                    hostIdentity = hostId,
                    sessionId = peer.sessionId,
                    targetNodeId = targetNode,
                    targetAccountUid = peer.accountUid,
                    reason = reason
                )
            )
        }

        repeat(3) {
            peer.activeDirectEndpoint?.let { ep -> sendUdpRaw(kickPairwise, ep) }
            mqttClient?.publish("$topicRoot/h2c/pkt/$targetNode", kickPairwise)
            if (it < 2 && signedKickEnc != null) {
                mqttClient?.publish("$topicRoot/h2c/ctl", signedKickEnc)
            }
        }

        broadcastRosterFromHost()
        refreshSnapshotUi()
        val msgHost = if (blockInSession) {
            "${peer.displayName} foi expulso e bloqueado da sala."
        } else {
            "${peer.displayName} foi expulso da sala."
        }
        onModerationEventToast?.invoke(msgHost)
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
        val identity = sessionIdentity ?: return
        val members = buildHostMembersList()
        val enc = roomCipher?.encrypt(TunnelProtocol.buildRoster(identity, maxTotalPlayers, members)) ?: return
        mqttClient?.publish("$topicRoot/h2c/ctl", enc)
        if (isPublicRoom) {
            publishPublicLobbyBeacon(currentCount = members.size)
        }
    }

    private fun publishPublicLobbyBeacon(currentCount: Int = -1) {
        if (!isHostRole || !isPublicRoom || !sessionRunning.get()) return
        val identity = sessionIdentity ?: return
        val count = if (currentCount > 0) {
            currentCount
        } else {
            1 + peersByNode.values.count { it.isAlive() }
        }
        val beacon = PublicLobbyBrowser.buildOpenBeaconEncrypted(
            identity = identity,
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
        val identity = sessionIdentity ?: return
        val closeBeacon = PublicLobbyBrowser.buildCloseBeaconEncrypted(identity, activeRoomCode)
        mqttClient?.publish(PublicLobbyBrowser.LOBBY_TOPIC, closeBeacon)
    }

    // =========================================================================
    // LEITURA TUN0 E UDP
    // =========================================================================

    private fun startTunReaderLoop(pfd: ParcelFileDescriptor, gen: Long) {
        Thread({
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(2048)
            while (sessionRunning.get() && sessionGeneration.get() == gen && tunInterface === pfd) {
                try {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) {
                        Thread.sleep(5L)
                        continue
                    }

                    val sender = myNodeId
                    if (sender <= 0) continue

                    val dstNode = TunnelProtocol.inspectOutgoingIpv4Target(buf, n, sender)
                    if (dstNode < 0 || dstNode == sender) continue

                    val plainFrame = TunnelProtocol.buildIpv4Frame(sender, dstNode, buf, n)
                    txBytesTotal.addAndGet(n.toLong())

                    if (isHostRole) {
                        if (dstNode == TunnelProtocol.BROADCAST_NODE_ID) {
                            sendPairwiseToAllAliveClients(plainFrame, isGamePacket = true)
                        } else {
                            sendPairwiseToSpecificClient(dstNode, plainFrame, isGamePacket = true)
                        }
                    } else {
                        sendPairwiseFromClientToHost(plainFrame, isGamePacket = true)
                    }
                } catch (_: Exception) {
                    if (!sessionRunning.get() || sessionGeneration.get() != gen) break
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

    private fun startUdpReaderLoop(sock: DatagramSocket, gen: Long) {
        Thread({
            val buf = ByteArray(2048)
            val dp = DatagramPacket(buf, buf.size)
            while (sessionRunning.get() && sessionGeneration.get() == gen && udpSocket === sock && !sock.isClosed) {
                try {
                    dp.length = buf.size
                    sock.receive(dp)
                    val len = dp.length
                    if (len <= 0) continue

                    if (len >= 20 && buf[0] == 0x01.toByte() && buf[1] == 0x01.toByte()) {
                        val mapped = StunClient.tryParseBindingResponse(buf, len)
                        if (mapped != null && mapped != myPublicEndpoint) {
                            myPublicEndpoint = mapped
                            if (myNodeId > 0) broadcastMyCandidates()
                        }
                        continue
                    }

                    val copy = buf.copyOfRange(0, len)
                    val remoteAddr = InetSocketAddress(dp.address, dp.port)
                    handleEncryptedNetworkPacket(copy, viaUdpFrom = remoteAddr)
                } catch (_: Exception) {
                    if (!sessionRunning.get() || sessionGeneration.get() != gen) break
                }
            }
        }, "gdroid-udp-rx").apply { isDaemon = true }.start()
    }

    private fun handleEncryptedNetworkPacket(encrypted: ByteArray, viaUdpFrom: InetSocketAddress?) {
        if (!sessionRunning.get() || encrypted.isEmpty()) return

        when (encrypted[0]) {
            RoomCrypto.HEADER_PAIRWISE_CIPHER -> {
                if (encrypted.size < 2 + 12 + 17) return
                val claimedSender = encrypted[1].toInt() and 0xFF
                if (isHostRole) {
                    if (claimedSender !in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) return
                    val peer = peersByNode[claimedSender] ?: return
                    val plain = peer.pairwiseCipher.decrypt(claimedSender, encrypted) ?: return
                    handlePairwiseFrame(plain, authenticatedSender = claimedSender, viaUdpFrom = viaUdpFrom)
                } else {
                    if (claimedSender != TunnelProtocol.HOST_NODE_ID) return
                    val pw = clientToHostPairwise ?: return
                    val plain = pw.decrypt(TunnelProtocol.HOST_NODE_ID, encrypted) ?: return
                    handlePairwiseFrame(plain, authenticatedSender = TunnelProtocol.HOST_NODE_ID, viaUdpFrom = viaUdpFrom)
                }
            }

            else -> {
                if (viaUdpFrom != null) return
                val plain = roomCipher?.decrypt(encrypted) ?: return
                handleRoomBroadcastFrame(plain)
            }
        }
    }

    // =========================================================================
    // PROCESSAMENTO DE FRAMES DE SINALIZAÇÃO E PAR-A-PAR (ECDH)
    // =========================================================================

    private fun handleRoomBroadcastFrame(plain: ByteArray) {
        if (plain.isEmpty() || !sessionRunning.get()) return
        val now = System.currentTimeMillis()

        when (plain[0]) {
            TunnelProtocol.TYPE_PROBE_REQ -> {
                if (!isHostRole || !allowHandshakeRate(now)) return
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
                val ack = roomCipher?.encrypt(
                    TunnelProtocol.buildProbeAck(probe.probeId, status, aliveTotal, maxTotalPlayers)
                ) ?: return
                mqttClient?.publish("$topicRoot/h2c/ctl", ack)
            }

            TunnelProtocol.TYPE_JOIN_REQ -> {
                if (!isHostRole || !allowHandshakeRate(now)) return
                val hostId = sessionIdentity ?: return
                val req = TunnelProtocol.parseJoinReq(plain) ?: return
                val clientPub = RoomCrypto.decodeEcPublicKey(req.clientPubKeyB64) ?: return

                if (blockedSessions.contains(req.sessionId) ||
                    (req.accountUid != 0L && blockedAccountUids.contains(req.accountUid))
                ) {
                    val rej = roomCipher?.encrypt(
                        TunnelProtocol.buildJoinReject(
                            req.sessionId,
                            "Entrada recusada: você foi expulso e bloqueado desta sala pelo anfitrião."
                        )
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                val existingBySession = sessionToNode[req.sessionId]?.takeIf { peersByNode[it]?.isAlive(now) == true }
                val existingByUid = if (existingBySession == null && req.accountUid != 0L) {
                    peersByNode.values.firstOrNull { it.accountUid == req.accountUid }?.nodeId
                } else {
                    null
                }
                val existingNode = existingBySession ?: existingByUid

                val maxGuests = (maxTotalPlayers - 1).coerceIn(1, 7)
                val aliveGuests = peersByNode.values.count { it.isAlive(now) }
                if (existingNode == null && aliveGuests >= maxGuests) {
                    val rej = roomCipher?.encrypt(
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
                    val rej = roomCipher?.encrypt(
                        TunnelProtocol.buildJoinReject(req.sessionId, "Sala cheia ($maxTotalPlayers/$maxTotalPlayers jogadores).")
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                if (existingByUid != null) {
                    peersByNode[existingByUid]?.sessionId?.let { oldSid ->
                        if (oldSid != req.sessionId) sessionToNode.remove(oldSid)
                    }
                    sessionToNode[req.sessionId] = assigned
                }

                val pairwise = hostId.derivePairwiseCipher(clientPub, activeRoomCode, assigned)
                val isBrandNewJoin = (existingNode == null)
                val peer = peersByNode[assigned]
                if (peer == null) {
                    peersByNode[assigned] = PeerNode(assigned, req.sessionId, req.accountUid, req.displayName, pairwise)
                } else {
                    peer.sessionId = req.sessionId
                    peer.accountUid = req.accountUid
                    peer.displayName = req.displayName
                    peer.pairwiseCipher = pairwise
                    peer.lastAnyRxAt = now
                }

                val ackPlain = TunnelProtocol.buildJoinAck(
                    hostIdentity = hostId,
                    sessionId = req.sessionId,
                    assignedNodeId = assigned,
                    maxPlayers = maxTotalPlayers,
                    hostName = myDisplayName,
                    clientPubKeyB64 = req.clientPubKeyB64
                )
                val ackEnc = roomCipher?.encrypt(ackPlain) ?: return
                mqttClient?.publish("$topicRoot/h2c/ctl", ackEnc)

                broadcastMyCandidates()
                sendImmediatePingToPeers()
                broadcastRosterFromHost()
                refreshSnapshotUi()

                if (isBrandNewJoin) {
                    onModerationEventToast?.invoke("${req.displayName} entrou na sala.")
                }
            }

            TunnelProtocol.TYPE_JOIN_ACK -> {
                if (isHostRole) return
                val myId = sessionIdentity ?: return
                val verified = TunnelProtocol.parseAndVerifyJoinAck(plain, myId.publicKeyBase64) ?: return
                val ack = verified.first
                val hostPub = verified.second
                if (ack.sessionId != clientSessionId) return
                if (ack.assignedNodeId !in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) return

                val currentPinned = pinnedHostPubKey
                if (currentPinned != null && !currentPinned.encoded.contentEquals(hostPub.encoded)) {
                    return
                }
                pinnedHostPubKey = hostPub

                val pairwise = myId.derivePairwiseCipher(hostPub, activeRoomCode, ack.assignedNodeId)
                clientToHostPairwise = pairwise
                maxTotalPlayers = ack.maxPlayers
                hostDisplayNameOnClient = ack.hostName

                val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
                if (hostPeer == null) {
                    peersByNode[TunnelProtocol.HOST_NODE_ID] = PeerNode(
                        TunnelProtocol.HOST_NODE_ID,
                        0L,
                        0L,
                        ack.hostName,
                        pairwise
                    )
                } else {
                    hostPeer.displayName = ack.hostName
                    hostPeer.pairwiseCipher = pairwise
                    hostPeer.lastAnyRxAt = now
                }

                if (myNodeId == 0) {
                    myNodeId = ack.assignedNodeId
                    mqttClient?.subscribe("$topicRoot/h2c/pkt/${ack.assignedNodeId}")
                    if (!establishTunInterface(ack.assignedNodeId, sessionGeneration.get())) {
                        failAndStop("Não foi possível criar a interface VPN no Android.")
                        return
                    }
                    broadcastMyCandidates()
                    sendImmediatePingToPeers()
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_JOIN_REJECT -> {
                if (isHostRole || myNodeId != 0) return
                val rej = TunnelProtocol.parseRejectOrKick(plain) ?: return
                if (rej.sessionId == clientSessionId) {
                    failAndStop(rej.reason)
                }
            }

            TunnelProtocol.TYPE_KICK -> {
                if (isHostRole) return
                val kick = TunnelProtocol.parseAndVerifySignedKick(plain, pinnedHostPubKey) ?: return
                val matchesMe = kick.sessionId == clientSessionId ||
                    (myAccountUid != 0L && kick.targetAccountUid == myAccountUid) ||
                    (myNodeId > 0 && kick.targetNodeId == myNodeId)
                if (matchesMe) {
                    failAndStop(kick.reason)
                }
            }

            TunnelProtocol.TYPE_ROSTER -> {
                if (isHostRole || myNodeId == 0) return
                val hostPub = pinnedHostPubKey ?: return
                val roster = TunnelProtocol.parseRoster(plain, expectedHostKey = hostPub) ?: return
                maxTotalPlayers = roster.maxPlayers
                remoteRosterCache = roster.members
                peersByNode[TunnelProtocol.HOST_NODE_ID]?.lastAnyRxAt = now
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_BYE -> {
                if (isHostRole || myNodeId == 0) return
                if (TunnelProtocol.verifySignedHostBye(plain, pinnedHostPubKey)) {
                    failAndStop("O anfitrião encerrou a sala $activeRoomCode.")
                }
            }
        }
    }

    private fun handlePairwiseFrame(
        plain: ByteArray,
        authenticatedSender: Int,
        viaUdpFrom: InetSocketAddress?
    ) {
        if (plain.isEmpty() || !sessionRunning.get()) return
        val now = System.currentTimeMillis()

        val peer = peersByNode[authenticatedSender] ?: return
        peer.lastAnyRxAt = now
        if (viaUdpFrom != null) {
            peer.activeDirectEndpoint = viaUdpFrom
            peer.lastDirectRxAt = now
        }

        when (plain[0]) {
            TunnelProtocol.TYPE_KICK -> {
                if (isHostRole || authenticatedSender != TunnelProtocol.HOST_NODE_ID) return
                val kick = TunnelProtocol.parseRejectOrKick(plain) ?: return
                if (kick.sessionId == clientSessionId || (myNodeId > 0 && kick.targetNodeId == myNodeId)) {
                    failAndStop(kick.reason)
                }
            }

            TunnelProtocol.TYPE_CANDIDATES -> {
                if (plain.size < 3) return
                val frameSender = plain[1].toInt() and 0xFF
                if (frameSender != authenticatedSender) return
                val endpoints = TunnelProtocol.parseCandidatesEndpoints(plain.copyOfRange(2, plain.size))
                for (ep in endpoints) {
                    if (ep !in peer.candidateEndpoints && peer.candidateEndpoints.size < 8) {
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
                if (senderNode != authenticatedSender) return
                if (targetNode != myNodeId && targetNode != TunnelProtocol.BROADCAST_NODE_ID) return
                val sentAt = ByteBuffer.wrap(plain, 3, 8).long

                val pongPlain = TunnelProtocol.buildPong(myNodeId, senderNode, sentAt)
                if (isHostRole) {
                    val enc = peer.pairwiseCipher.encrypt(TunnelProtocol.HOST_NODE_ID, pongPlain)
                    if (viaUdpFrom != null) {
                        sendUdpRaw(enc, viaUdpFrom)
                    } else {
                        mqttClient?.publish("$topicRoot/h2c/pkt/$senderNode", enc)
                    }
                } else {
                    sendPairwiseFromClientToHost(pongPlain, isGamePacket = false)
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_PONG -> {
                if (plain.size < 11 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val targetNode = plain[2].toInt() and 0xFF
                if (senderNode != authenticatedSender || targetNode != myNodeId) return
                val sentAt = ByteBuffer.wrap(plain, 3, 8).long
                val rtt = (now - sentAt).coerceIn(1L, 9_999L)
                peer.lastRttMs = rtt

                if (manualPingPending.compareAndSet(true, false)) {
                    val mode = if (peer.isDirectActive(now)) "P2P Direto" else "Ponte TLS"
                    onManualPongToast?.invoke(rtt, mode)
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_IPV4_PKT -> {
                if (plain.size <= 23 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val dstNode = plain[2].toInt() and 0xFF
                if (senderNode == myNodeId) return
                if (isHostRole) {
                    if (senderNode != authenticatedSender) return
                } else {
                    if (authenticatedSender != TunnelProtocol.HOST_NODE_ID) return
                    if (senderNode !in TunnelProtocol.HOST_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) return
                }

                val payloadLen = plain.size - 3
                if (!TunnelProtocol.validateIncomingTunnelIpv4(plain, 3, payloadLen, senderNode)) {
                    return
                }

                if (isHostRole) {
                    when {
                        dstNode == TunnelProtocol.HOST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                        }
                        dstNode == TunnelProtocol.BROADCAST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                            for ((otherId, otherPeer) in peersByNode) {
                                if (otherId != senderNode && otherPeer.isAlive(now)) {
                                    sendPairwiseToSpecificClient(otherId, plain, isGamePacket = true)
                                }
                            }
                        }
                        dstNode in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID -> {
                            if (peersByNode.containsKey(dstNode)) {
                                sendPairwiseToSpecificClient(dstNode, plain, isGamePacket = true)
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
                if (senderNode != authenticatedSender) return
                if (isHostRole) {
                    val removed = peersByNode.remove(senderNode)
                    if (removed != null) {
                        sessionToNode.remove(removed.sessionId)
                        broadcastRosterFromHost()
                        refreshSnapshotUi()
                        onModerationEventToast?.invoke("${removed.displayName} saiu da sala.")
                    }
                } else if (senderNode == TunnelProtocol.HOST_NODE_ID) {
                    failAndStop("O anfitrião encerrou a sala $activeRoomCode.")
                }
            }
        }
    }

    // =========================================================================
    // ENVIO HÍBRIDO AUTENTICADO (UDP P2P -> PONTE TLS)
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

    private fun sendPairwiseToSpecificClient(clientNode: Int, plainFrame: ByteArray, isGamePacket: Boolean) {
        val peer = peersByNode[clientNode] ?: return
        val encrypted = peer.pairwiseCipher.encrypt(TunnelProtocol.HOST_NODE_ID, plainFrame)
        val directEp = peer.activeDirectEndpoint
        if (directEp != null && peer.isDirectActive()) {
            if (sendUdpRaw(encrypted, directEp)) return
        }
        mqttClient?.publish("$topicRoot/h2c/pkt/$clientNode", encrypted)
    }

    private fun sendPairwiseToAllAliveClients(plainFrame: ByteArray, isGamePacket: Boolean) {
        val now = System.currentTimeMillis()
        for ((nodeId, peer) in peersByNode) {
            if (peer.isAlive(now)) {
                sendPairwiseToSpecificClient(nodeId, plainFrame, isGamePacket)
            }
        }
    }

    private fun sendPairwiseFromClientToHost(plainFrame: ByteArray, isGamePacket: Boolean) {
        val me = myNodeId
        if (me <= 0) return
        val pw = clientToHostPairwise ?: return
        val encrypted = pw.encrypt(me, plainFrame)

        val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
        val directEp = hostPeer?.activeDirectEndpoint
        if (directEp != null && hostPeer.isDirectActive()) {
            if (sendUdpRaw(encrypted, directEp)) return
        }
        val sub = if (isGamePacket) "pkt" else "ctl"
        mqttClient?.publish("$topicRoot/c2h/$sub", encrypted)
    }

    private fun sendClientJoinRequest() {
        val myId = sessionIdentity ?: return
        val frame = TunnelProtocol.buildJoinReq(
            sessionId = clientSessionId,
            accountUid = myAccountUid,
            displayName = myDisplayName,
            clientPubKeyB64 = myId.publicKeyBase64
        )
        val enc = roomCipher?.encrypt(frame) ?: return
        mqttClient?.publish("$topicRoot/c2h/ctl", enc)
    }

    private fun broadcastMyCandidates() {
        val me = myNodeId
        if (me <= 0) return
        udpSocket?.let { myLocalEndpoint = StunClient.findLocalLanAddress(it.localPort) }
        val plainFrame = TunnelProtocol.buildCandidates(me, myPublicEndpoint, myLocalEndpoint)
        if (isHostRole) {
            sendPairwiseToAllAliveClients(plainFrame, isGamePacket = false)
        } else {
            sendPairwiseFromClientToHost(plainFrame, isGamePacket = false)
        }
    }

    private fun punchUdpTowardPeer(peer: PeerNode) {
        val me = myNodeId
        if (me <= 0) return
        val pingPlain = TunnelProtocol.buildPing(me, peer.nodeId, System.currentTimeMillis())
        val encrypted = peer.pairwiseCipher.encrypt(me, pingPlain)
        for (ep in peer.candidateEndpoints) {
            sendUdpRaw(encrypted, ep)
        }
    }

    private fun sendImmediatePingToPeers() {
        val me = myNodeId
        if (me <= 0) return
        val now = System.currentTimeMillis()
        if (isHostRole) {
            for (p in peersByNode.values.filter { it.isAlive(now) }) {
                val pingPlain = TunnelProtocol.buildPing(me, p.nodeId, now)
                if (!p.isDirectActive(now)) punchUdpTowardPeer(p)
                sendPairwiseToSpecificClient(p.nodeId, pingPlain, isGamePacket = false)
            }
        } else {
            val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
            val pingPlain = TunnelProtocol.buildPing(me, TunnelProtocol.HOST_NODE_ID, now)
            if (hostPeer != null && !hostPeer.isDirectActive(now)) {
                punchUdpTowardPeer(hostPeer)
            }
            sendPairwiseFromClientToHost(pingPlain, isGamePacket = false)
        }
    }

    // =========================================================================
    // LOOP DE MANUTENÇÃO E UI
    // =========================================================================

    private fun startMaintenanceLoop(gen: Long) {
        Thread({
            val startedAt = System.currentTimeMillis()
            var tick = 0
            while (sessionRunning.get() && sessionGeneration.get() == gen) {
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!sessionRunning.get() || sessionGeneration.get() != gen) break
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
                routeLabel = if (anyDirect) "P2P" else "Ponte TLS"
                detail = "Sala ativa e protegida. No GDroid, todos entram no IP 10.10.0.1."
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
                detail = "Registrando sala segura $activeRoomCode..."
            }
        } else {
            if (myNodeId > 0 && alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = if (anyDirect) "P2P" else "Ponte TLS"
                detail = "Conectado na sala de $hostDisplayNameOnClient. Entre no IP 10.10.0.1 no GDroid."
                membersList = if (remoteRosterCache.isNotEmpty()) {
                    remoteRosterCache.map { m ->
                        when {
                            m.isHost -> m.copy(pingMs = bestPing, routeLabel = routeLabel)
                            m.nodeId == myNodeId -> m.copy(pingMs = bestPing, routeLabel = "Você")
                            else -> m
                        }
                    }
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
                            pingMs = bestPing,
                            routeLabel = "Você"
                        )
                    )
                }
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "..."
                detail = "Autenticando na sala $activeRoomCode..."
                membersList = emptyList()
            }
        }

        val totalInRoom = membersList.size.coerceAtLeast(if (isHostRole) 1 else 0)
        val blockedNamesSorted = blockedPlayerNames.values.sorted()
        val totalBlocked = maxOf(blockedSessions.size, blockedNamesSorted.size)

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
                blockedCount = totalBlocked,
                blockedNames = blockedNamesSorted,
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
        sessionGeneration.incrementAndGet()

        if (wasRunning && myNodeId > 0) {
            try {
                if (isHostRole) {
                    if (isPublicRoom) {
                        publishPublicLobbyClose()
                    }
                    sessionIdentity?.let { id ->
                        val signedBye = roomCipher?.encrypt(TunnelProtocol.buildSignedHostBye(id))
                        if (signedBye != null) {
                            mqttClient?.publish("$topicRoot/h2c/ctl", signedBye)
                        }
                    }
                    sendPairwiseToAllAliveClients(
                        TunnelProtocol.buildClientBye(TunnelProtocol.HOST_NODE_ID),
                        isGamePacket = false
                    )
                } else {
                    sendPairwiseFromClientToHost(
                        TunnelProtocol.buildClientBye(myNodeId),
                        isGamePacket = false
                    )
                }
            } catch (_: Exception) { }
        }

        mqttClient?.stop()
        mqttClient = null
        sessionIdentity = null
        clientToHostPairwise = null
        pinnedHostPubKey = null

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
            Intent(this, GdroidLanVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_CALLER_TOKEN, INTERNAL_CALLER_TOKEN)
            },
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
