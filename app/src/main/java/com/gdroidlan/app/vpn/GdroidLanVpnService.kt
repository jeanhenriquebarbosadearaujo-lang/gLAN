package com.gdroidlan.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gdroidlan.app.MainActivity
import com.gdroidlan.app.R
import com.gdroidlan.app.admin.AdminBridge
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
        RECONNECTING,
        ERROR
    }

    data class TunnelSnapshot(
        val phase: StatusPhase = StatusPhase.OFFLINE,
        val isHost: Boolean = true,
        val isPublicRoom: Boolean = false,
        val ipProtected: Boolean = false,
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
        val rxBytes: Long = 0L,
        val gamePortOpen: Boolean = false,
        val mapMode: String = ""
    )

    private class PeerNode(
        val nodeId: Int,
        @Volatile var sessionId: Long,
        @Volatile var accountUid: Long,
        @Volatile var displayName: String,
        @Volatile var pairwiseCipher: RoomCrypto.PairwiseCipher,
        @Volatile var accountTag: String = ""
    ) {
        val candidateEndpoints = CopyOnWriteArrayList<InetSocketAddress>()
        @Volatile var activeDirectEndpoint: InetSocketAddress? = null
        @Volatile var lastDirectRxAt: Long = 0L
        @Volatile var lastAnyRxAt: Long = System.currentTimeMillis()
        @Volatile var lastRttMs: Long = -1L

        private var rateWindowSec: Long = 0L
        private var unicastPktsInSec: Int = 0
        private var broadcastPktsInSec: Int = 0
        private var bytesInSec: Int = 0

        @Synchronized
        fun allowIncomingTunnelPacket(nowMs: Long, payloadBytes: Int, isBroadcast: Boolean): Boolean {
            val sec = nowMs / 1000L
            if (sec != rateWindowSec) {
                rateWindowSec = sec
                unicastPktsInSec = 0
                broadcastPktsInSec = 0
                bytesInSec = 0
            }
            if (isBroadcast) {
                broadcastPktsInSec++
                if (broadcastPktsInSec > 24) return false
            } else {
                unicastPktsInSec++
                if (unicastPktsInSec > 480) return false
            }
            bytesInSec += payloadBytes
            return bytesInSec <= 524_288
        }

        fun isDirectActive(now: Long = System.currentTimeMillis()): Boolean =
            activeDirectEndpoint != null && (now - lastDirectRxAt) < 8_500L

        fun isOscillating(now: Long = System.currentTimeMillis()): Boolean =
            (now - lastAnyRxAt) >= 12_000L

        fun isAlive(now: Long = System.currentTimeMillis(), extendedGrace: Boolean = true): Boolean {
            val timeoutMs = if (extendedGrace) 75_000L else 38_000L
            return (now - lastAnyRxAt) < timeoutMs
        }
    }

    companion object {
        const val ACTION_START_HOST = "com.gdroidlan.app.START_HOST"
        const val ACTION_START_CLIENT = "com.gdroidlan.app.START_CLIENT"
        const val ACTION_STOP = "com.gdroidlan.app.STOP"
        const val ACTION_MANUAL_PING = "com.gdroidlan.app.MANUAL_PING"
        const val ACTION_KICK_PEER = "com.gdroidlan.app.KICK_PEER"
        const val ACTION_CLEAR_BLOCKS = "com.gdroidlan.app.CLEAR_BLOCKS"
        const val ACTION_SET_AUTO_RECONNECT = "com.gdroidlan.app.SET_AUTO_RECONNECT"

        const val EXTRA_CALLER_TOKEN = "caller_token"
        const val EXTRA_ROOM_CODE = "room_code"
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_ACCOUNT_UID = "account_uid"
        const val EXTRA_ACCOUNT_TAG = "account_tag"
        const val EXTRA_MAX_PLAYERS = "max_players"
        const val EXTRA_IS_PUBLIC = "is_public"
        const val EXTRA_ROOM_TITLE = "room_title"
        const val EXTRA_MAP_MODE = "map_mode"
        const val EXTRA_PROTECT_IP = "protect_ip"
        const val EXTRA_EXPECTED_HOST_PUBKEY = "expected_host_pubkey"
        const val EXTRA_TARGET_NODE_ID = "target_node_id"
        const val EXTRA_BLOCK_SESSION = "block_session"
        const val EXTRA_AUTO_RECONNECT = "auto_reconnect"

        val INTERNAL_CALLER_TOKEN: String = UUID.randomUUID().toString()

        private const val CHANNEL_ID = "gdroid_lan_vpn_channel"
        private const val NOTIF_ID = 4101
        private const val MAX_HANDSHAKE_PACKETS_PER_SEC = 18
        private const val CLIENT_RECONNECT_MAX_MS = 90_000L

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
    private val lastHandoverElapsedMs = AtomicLong(0L)

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var tunBoundNodeId: Int = 0
    @Volatile private var tunOutStream: FileOutputStream? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var mqttClient: MqttLightClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activePhysicalNetHandle: Long = 0L
    @Volatile private var hasPhysicalInternet: Boolean = true

    private var isHostRole = true
    private var activeRoomCode = ""
    private var topicRoot = ""
    private var roomCipher: RoomCrypto.SessionCipher? = null
    @Volatile private var sessionIdentity: RoomCrypto.IdentityKeyBox? = null
    @Volatile private var clientToHostPairwise: RoomCrypto.PairwiseCipher? = null
    @Volatile private var pinnedHostPubKey: PublicKey? = null

    @Volatile private var autoReconnectEnabled = true
    @Volatile private var wasReconnectingToastShown = false
    @Volatile private var myDisplayName = "Jogador"
    @Volatile private var myAccountUid = 0L
    @Volatile private var myAccountTag = ""
    @Volatile private var maxTotalPlayers = TunnelProtocol.MAX_TOTAL_PLAYERS
    @Volatile private var isPublicRoom = false
    @Volatile private var ipProtectionActive = false
    @Volatile private var expectedHostPubKeyFromLobbyB64 = ""
    @Volatile private var publicRoomTitle = ""
    @Volatile private var activeMapMode = ""
    @Volatile private var hostGamePortOpen = false
    @Volatile private var lastHostGamePortTrafficAtMs = 0L
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var lastNotifSignature = ""
    @Volatile private var hostDisplayNameOnClient = "Anfitrião"
    @Volatile private var remoteRosterCache: List<TunnelProtocol.RoomMemberInfo> = emptyList()

    private val adminStateListener: (AdminBridge.VerifiedAdminState) -> Unit = { _ ->
        if (sessionRunning.get()) {
            val banReason = AdminBridge.checkAccountBanReason(myAccountTag)
            if (banReason != null) {
                failAndStop("Conta suspensa pela administração: $banReason")
            } else if (AdminBridge.isRoomRecentlyKilledByAdmin(activeRoomCode)) {
                failAndStop("Esta sala foi encerrada pela administração do gLAN.")
            } else if (isHostRole) {
                // Se algum convidado na sala acabou de ter a #TAG suspensa, remove-o automaticamente
                val now = System.currentTimeMillis()
                val bannedPeers = peersByNode.values.filter { peer ->
                    peer.isAlive(now, autoReconnectEnabled) &&
                        peer.accountTag.isNotBlank() &&
                        AdminBridge.checkAccountBanReason(peer.accountTag, now) != null
                }
                for (bp in bannedPeers) {
                    Thread({
                        kickPeerFromServer(
                            targetNode = bp.nodeId,
                            blockInSession = true,
                            customReason = "Sua conta foi suspensa pela administração do gLAN."
                        )
                    }, "gdroid-ban-kick").start()
                }
            }
        }
    }

    private val adminKillListener: (AdminBridge.VerifiedKillCommand) -> Unit = { cmd ->
        if (sessionRunning.get() && cmd.roomCode == activeRoomCode) {
            failAndStop(cmd.reason)
        }
    }

    @Volatile private var lastRetainedTelemetrySig = ""
    @Volatile private var lastRetainedTelemetryAtMs = 0L

    private val adminSyncListener: () -> Unit = {
        if (sessionRunning.get() && isHostRole) {
            Thread({
                publishAdminServerTelemetry(forceRetain = true)
                if (isPublicRoom) {
                    publishPublicLobbyBeacon()
                }
            }, "gdroid-admin-sync").apply { isDaemon = true }.start()
        }
    }

    private val adminRoomNoticeListener: (AdminBridge.VerifiedRoomNotice) -> Unit = { notice ->
        if (sessionRunning.get() && notice.roomCode == activeRoomCode) {
            onModerationEventToast?.invoke("${notice.title}: ${notice.message}")
        }
    }

    private val adminMemberKickListener: (AdminBridge.VerifiedMemberKickCommand) -> Unit = { cmd ->
        if (sessionRunning.get() && isHostRole && cmd.roomCode == activeRoomCode) {
            val match = peersByNode.values.firstOrNull { p ->
                (cmd.targetTag.isNotBlank() && p.accountTag.equals(cmd.targetTag, ignoreCase = true)) ||
                    (cmd.targetNodeId in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID &&
                        p.nodeId == cmd.targetNodeId)
            }
            if (match != null) {
                Thread({
                    kickPeerFromServer(
                        targetNode = match.nodeId,
                        blockInSession = cmd.blockInSession,
                        customReason = cmd.reason
                    )
                }, "gdroid-admin-rkick").start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AdminBridge.addStateListener(adminStateListener)
        AdminBridge.addKillListener(adminKillListener)
        AdminBridge.addSyncListener(adminSyncListener)
        AdminBridge.addRoomNoticeListener(adminRoomNoticeListener)
        AdminBridge.addMemberKickListener(adminMemberKickListener)
    }

    @Volatile private var myNodeId: Int = 0
    @Volatile private var clientSessionId: Long = rng.nextLong()
    @Volatile private var myPublicEndpoint: InetSocketAddress? = null
    @Volatile private var myLocalEndpoint: InetSocketAddress? = null
    @Volatile private var lastManualPingAtMs: Long = 0L

    private val peersByNode = ConcurrentHashMap<Int, PeerNode>()
    private val sessionToNode = ConcurrentHashMap<Long, Int>()
    private val reservedNodeByUid = ConcurrentHashMap<Long, Int>()
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

            ACTION_SET_AUTO_RECONNECT -> {
                autoReconnectEnabled = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, true)
                if (sessionRunning.get()) {
                    refreshSnapshotUi()
                }
                return START_STICKY
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
                myAccountTag = AdminBridge.normalizeTag(intent.getStringExtra(EXTRA_ACCOUNT_TAG).orEmpty())

                val banReason = AdminBridge.checkAccountBanReason(myAccountTag)
                if (banReason != null) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    publishState(
                        TunnelSnapshot(
                            phase = StatusPhase.ERROR,
                            statusText = "Conta suspensa pela administração: $banReason"
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (AdminBridge.isRoomRecentlyKilledByAdmin(cleanCode)) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    publishState(
                        TunnelSnapshot(
                            phase = StatusPhase.ERROR,
                            statusText = "Esta sala foi encerrada pela administração do gLAN."
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                maxTotalPlayers = intent.getIntExtra(EXTRA_MAX_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
                    .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
                autoReconnectEnabled = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, true)

                val wantHost = (intent.action == ACTION_START_HOST)
                isPublicRoom = wantHost && intent.getBooleanExtra(EXTRA_IS_PUBLIC, false)

                val gateErr = AdminBridge.checkNetworkGateError(
                    installedVersionCode = OfficialVersionCert.BUILT_VERSION_CODE,
                    creatingPublicRoom = isPublicRoom
                )
                if (gateErr != null) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    publishState(
                        TunnelSnapshot(
                            phase = StatusPhase.ERROR,
                            statusText = gateErr.second
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                ipProtectionActive = intent.getBooleanExtra(EXTRA_PROTECT_IP, false)
                expectedHostPubKeyFromLobbyB64 = if (!wantHost) {
                    intent.getStringExtra(EXTRA_EXPECTED_HOST_PUBKEY).orEmpty().trim()
                } else {
                    ""
                }
                publicRoomTitle = RoomCrypto.cleanSafeText(
                    intent.getStringExtra(EXTRA_ROOM_TITLE).orEmpty(),
                    28
                )
                activeMapMode = if (wantHost) {
                    RoomCrypto.cleanSafeText(intent.getStringExtra(EXTRA_MAP_MODE).orEmpty(), 28)
                } else {
                    ""
                }
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
        AdminBridge.removeMemberKickListener(adminMemberKickListener)
        AdminBridge.removeRoomNoticeListener(adminRoomNoticeListener)
        AdminBridge.removeSyncListener(adminSyncListener)
        AdminBridge.removeStateListener(adminStateListener)
        AdminBridge.removeKillListener(adminKillListener)
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
    // MONITORAMENTO DE REDE FÍSICA (ROAMING AUTOMÁTICO WI-FI <-> 4G/5G)
    // =========================================================================

    private fun registerPhysicalNetworkMonitor(gen: Long) {
        unregisterPhysicalNetworkMonitor()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!sessionRunning.get() || sessionGeneration.get() != gen) return
                val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null }
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return
                }

                hasPhysicalInternet = true
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (_: Exception) { }

                val newHandle = network.networkHandle
                val prevHandle = activePhysicalNetHandle
                activePhysicalNetHandle = newHandle

                if (prevHandle != 0L && prevHandle != newHandle && autoReconnectEnabled) {
                    triggerSeamlessNetworkHandover(gen)
                } else {
                    refreshSnapshotUi()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!sessionRunning.get() || sessionGeneration.get() != gen) return
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (validated && !hasPhysicalInternet) {
                    hasPhysicalInternet = true
                    try {
                        setUnderlyingNetworks(arrayOf(network))
                    } catch (_: Exception) { }
                    if (autoReconnectEnabled) {
                        triggerSeamlessNetworkHandover(gen)
                    } else {
                        refreshSnapshotUi()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (!sessionRunning.get() || sessionGeneration.get() != gen) return
                if (network.networkHandle == activePhysicalNetHandle) {
                    val activeNow = try { cm.activeNetwork } catch (_: Exception) { null }
                    val activeCaps = try {
                        if (activeNow != null) cm.getNetworkCapabilities(activeNow) else null
                    } catch (_: Exception) {
                        null
                    }
                    val stillHasNonVpn = activeNow != null &&
                        activeCaps != null &&
                        !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

                    hasPhysicalInternet = stillHasNonVpn
                    if (!stillHasNonVpn) {
                        activePhysicalNetHandle = 0L
                        try {
                            setUnderlyingNetworks(null)
                        } catch (_: Exception) { }
                    } else {
                        activePhysicalNetHandle = activeNow!!.networkHandle
                        try {
                            setUnderlyingNetworks(arrayOf(activeNow))
                        } catch (_: Exception) { }
                        if (autoReconnectEnabled) {
                            triggerSeamlessNetworkHandover(gen)
                        }
                    }
                    refreshSnapshotUi()
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) { }
    }

    private fun unregisterPhysicalNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        activePhysicalNetHandle = 0L
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
    }

    private fun triggerSeamlessNetworkHandover(gen: Long) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val prev = lastHandoverElapsedMs.get()
        if (nowElapsed - prev < 900L) return
        if (!lastHandoverElapsedMs.compareAndSet(prev, nowElapsed)) return

        Thread({
            if (!sessionRunning.get() || sessionGeneration.get() != gen) return@Thread
            try {
                // 1. Limpa endpoints P2P antigos para rotear imediatamente pela ponte TLS
                for (peer in peersByNode.values) {
                    peer.activeDirectEndpoint = null
                }
                myPublicEndpoint = null

                // 2. Recria socket UDP protegido somente se o modo Proteção de IP não estiver ativo
                if (!ipProtectionActive) {
                    val newSock = DatagramSocket(0).apply {
                        reuseAddress = true
                        receiveBufferSize = 256 * 1024
                        sendBufferSize = 256 * 1024
                    }
                    protect(newSock)
                    val oldSock = udpSocket
                    udpSocket = newSock
                    try {
                        oldSock?.close()
                    } catch (_: Exception) { }

                    myLocalEndpoint = StunClient.findLocalLanAddress(newSock.localPort)
                    startUdpReaderLoop(newSock, gen)
                    StunClient.sendBindingRequests(newSock)
                }

                // 3. Reconecta imediatamente o canal TLS MQTT na nova rede sem esperar timeout TCP
                mqttClient?.forceReconnectNow()

                refreshSnapshotUi()
            } catch (_: Exception) { }
        }, "gdroid-net-handover").apply { isDaemon = true }.start()
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
        pinnedHostPubKey = if (!asHost && expectedHostPubKeyFromLobbyB64.isNotBlank()) {
            RoomCrypto.decodeEcPublicKey(expectedHostPubKeyFromLobbyB64)
        } else {
            null
        }
        clientSessionId = rng.nextLong()
        wasReconnectingToastShown = false
        hasPhysicalInternet = true

        peersByNode.clear()
        sessionToNode.clear()
        reservedNodeByUid.clear()
        blockedSessions.clear()
        blockedAccountUids.clear()
        blockedPlayerNames.clear()
        remoteRosterCache = emptyList()
        hostGamePortOpen = false
        lastHostGamePortTrafficAtMs = 0L
        sessionStartedAtMs = System.currentTimeMillis()
        lastRetainedTelemetrySig = ""
        lastRetainedTelemetryAtMs = 0L
        lastNotifSignature = ""
        txBytesTotal.set(0L)
        rxBytesTotal.set(0L)
        myPublicEndpoint = null
        myNodeId = if (asHost) TunnelProtocol.HOST_NODE_ID else 0
        sessionRunning.set(true)

        acquireWakeLockSafe()
        registerPhysicalNetworkMonitor(gen)
        startForegroundSafe(
            if (asHost) "Servidor seguro • Sala $roomCode (10.10.0.1)"
            else "Conectando na sala $roomCode..."
        )

        publishState(
            TunnelSnapshot(
                phase = StatusPhase.CONNECTING,
                isHost = asHost,
                isPublicRoom = isPublicRoom,
                ipProtected = ipProtectionActive,
                publicRoomTitle = publicRoomTitle,
                roomCode = roomCode,
                myNodeId = myNodeId,
                myVirtualIp = if (asHost) TunnelProtocol.HOST_VIRTUAL_IP else "Negociando...",
                totalPlayersInRoom = if (asHost) 1 else 0,
                maxTotalPlayers = maxTotalPlayers,
                statusText = if (asHost) "Iniciando servidor seguro..." else "Autenticando na sala $roomCode...",
                gamePortOpen = hostGamePortOpen,
                mapMode = activeMapMode
            )
        )

        Thread({
            try {
                if (sessionGeneration.get() != gen || !sessionRunning.get()) return@Thread

                if (!ipProtectionActive) {
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
                } else {
                    udpSocket = null
                    myLocalEndpoint = null
                    myPublicEndpoint = null
                }

                if (asHost) {
                    if (!ensureTunInterfaceForNode(TunnelProtocol.HOST_NODE_ID, gen)) {
                        failAndStop("Permissão de VPN recusada ou indisponível no aparelho.")
                        return@Thread
                    }
                    hostGamePortOpen = SourceGameDetector.probeLocalSourceServer()
                }

                val initialTopics = ArrayList<String>(6).apply {
                    if (asHost) {
                        add("$topicRoot/c2h/ctl")
                        add("$topicRoot/c2h/pkt")
                    } else {
                        add("$topicRoot/h2c/ctl")
                    }
                    add(UpdateNotifier.VERSION_TOPIC)
                    add(AdminBridge.STATE_TOPIC)
                    add(AdminBridge.CMD_TOPIC)
                    AdminBridge.replyTopicForTag(myAccountTag)?.let { add(it) }
                }

                val mqtt = MqttLightClient(
                    socketProtector = { sock -> protect(sock) },
                    onPacketReceived = { topic, encrypted ->
                        if (sessionGeneration.get() == gen && sessionRunning.get()) {
                            when {
                                topic == UpdateNotifier.VERSION_TOPIC -> {
                                    UpdateNotifier.parseEncryptedAnnouncement(encrypted)?.let { rel ->
                                        UpdateNotifier.onVerifiedReleaseObserved(
                                            rel,
                                            OfficialVersionCert.BUILT_VERSION_CODE
                                        )
                                    }
                                }
                                topic == AdminBridge.STATE_TOPIC ||
                                    topic == AdminBridge.CMD_TOPIC ||
                                    topic.startsWith("glan/v36/admin/reply/") -> {
                                    AdminBridge.onAdminPacketReceived(topic, encrypted)
                                }
                                else -> {
                                    handleEncryptedNetworkPacket(encrypted, viaUdpFrom = null)
                                }
                            }
                        }
                    },
                    onConnectionStateChanged = { isConn, _ ->
                        if (sessionGeneration.get() != gen || !sessionRunning.get()) return@MqttLightClient
                        if (isConn) {
                            if (!ipProtectionActive) {
                                udpSocket?.let { StunClient.sendBindingRequests(it) }
                            }
                            if (asHost) {
                                if (!ipProtectionActive) broadcastMyCandidates()
                                sendImmediatePingToPeers()
                                broadcastRosterFromHost()
                                refreshSnapshotUi()
                            } else if (myNodeId == 0) {
                                sendClientJoinRequest()
                            } else {
                                // Reconectou após oscilação mantendo a mesma sessão e o mesmo IP 10.10.0.x
                                sendClientJoinRequest()
                                if (!ipProtectionActive) broadcastMyCandidates()
                                sendImmediatePingToPeers()
                                refreshSnapshotUi()
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

    private fun ensureTunInterfaceForNode(nodeId: Int, gen: Long): Boolean {
        synchronized(tunWriteLock) {
            if (tunInterface != null && tunBoundNodeId == nodeId) {
                return true
            }
            try {
                tunOutStream?.close()
            } catch (_: Exception) { }
            tunOutStream = null
            try {
                tunInterface?.close()
            } catch (_: Exception) { }
            tunInterface = null
            tunBoundNodeId = 0

            val virtualIp = TunnelProtocol.nodeIp(nodeId)
            val builder = Builder()
                .setSession("gLAN ($activeRoomCode)")
                .setMtu(TunnelProtocol.VPN_MTU)
                .setBlocking(true)
                .addAddress(virtualIp, 24)
                .addRoute("10.10.0.0", 24)
            try {
                // Captura broadcasts 255.255.255.255 da aba LAN nativa dentro do GMod / GDroid
                builder.addRoute("255.255.255.255", 32)
            } catch (_: Exception) { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            val pfd = builder.establish() ?: return false

            tunInterface = pfd
            tunBoundNodeId = nodeId
            tunOutStream = FileOutputStream(pfd.fileDescriptor)
            startTunReaderLoop(pfd, gen)
            return true
        }
    }

    // =========================================================================
    // MODERAÇÃO (EXPULSAR / BLOQUEAR) E LISTA DE PARTICIPANTES
    // =========================================================================

    private fun kickPeerFromServer(
        targetNode: Int,
        blockInSession: Boolean,
        customReason: String? = null
    ) {
        val peer = peersByNode.remove(targetNode) ?: return
        sessionToNode.remove(peer.sessionId)
        if (peer.accountUid != 0L) {
            reservedNodeByUid.remove(peer.accountUid)
        }
        if (blockInSession) {
            blockedSessions.add(peer.sessionId)
            if (peer.accountUid != 0L) {
                blockedAccountUids.add(peer.accountUid)
            }
            val blockKey = if (peer.accountUid != 0L) "uid:${peer.accountUid}" else "sid:${peer.sessionId}"
            blockedPlayerNames[blockKey] = peer.displayName
        }

        val reason = customReason?.takeIf { it.isNotBlank() } ?: if (blockInSession) {
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
        val msgHost = if (customReason != null) {
            "${peer.displayName} foi removido da sala."
        } else if (blockInSession) {
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
                routeLabel = "Anfitrião",
                accountTag = myAccountTag
            )
        )
        val sortedClients = peersByNode.values
            .filter { it.isAlive(now, autoReconnectEnabled) }
            .sortedBy { it.nodeId }
        for (p in sortedClients) {
            val route = when {
                p.isOscillating(now) -> "Reconectando"
                p.isDirectActive(now) -> "P2P"
                ipProtectionActive -> "IP Oculto"
                else -> "Ponte"
            }
            list.add(
                TunnelProtocol.RoomMemberInfo(
                    nodeId = p.nodeId,
                    sessionId = p.sessionId,
                    accountUid = p.accountUid,
                    displayName = p.displayName,
                    virtualIp = TunnelProtocol.nodeIp(p.nodeId),
                    isHost = false,
                    pingMs = p.lastRttMs,
                    routeLabel = route,
                    accountTag = p.accountTag
                )
            )
        }
        return list
    }

    private fun broadcastRosterFromHost() {
        if (!isHostRole || !sessionRunning.get()) return
        val identity = sessionIdentity ?: return
        val members = buildHostMembersList()
        val enc = roomCipher?.encrypt(
            TunnelProtocol.buildRoster(
                hostIdentity = identity,
                maxPlayers = maxTotalPlayers,
                members = members,
                gamePortOpen = hostGamePortOpen,
                mapMode = activeMapMode
            )
        ) ?: return
        mqttClient?.publish("$topicRoot/h2c/ctl", enc)
        if (isPublicRoom) {
            publishPublicLobbyBeacon(currentCount = members.size)
        }
        publishAdminServerTelemetry(members)
    }

    private fun publishAdminServerTelemetry(
        precomputedMembers: List<TunnelProtocol.RoomMemberInfo>? = null,
        forceRetain: Boolean = false
    ) {
        if (!isHostRole || !sessionRunning.get() || activeRoomCode.isBlank()) return
        val identity = sessionIdentity ?: return
        val client = mqttClient ?: return
        if (!client.isConnected()) return

        val now = System.currentTimeMillis()
        val members = precomputedMembers ?: buildHostMembersList(now)
        val pkt = AdminBridge.buildEncryptedServerReport(
            hostIdentity = identity,
            isOpen = true,
            roomCode = activeRoomCode,
            isPublicRoom = isPublicRoom,
            roomTitle = if (isPublicRoom) publicRoomTitle else "Sala Privada de $myDisplayName",
            hostName = myDisplayName,
            hostTag = myAccountTag,
            hostUid = myAccountUid,
            members = members,
            maxPlayers = maxTotalPlayers,
            ipProtected = ipProtectionActive,
            gamePortOpen = hostGamePortOpen,
            mapMode = activeMapMode,
            startedAtMs = sessionStartedAtMs
        ) ?: return

        val stateSig = "$activeRoomCode|${members.size}|$maxTotalPlayers|$hostGamePortOpen|$activeMapMode|$isPublicRoom"
        val shouldWriteRetained = forceRetain ||
            stateSig != lastRetainedTelemetrySig ||
            (now - lastRetainedTelemetryAtMs) >= 18_000L
        if (shouldWriteRetained) {
            lastRetainedTelemetrySig = stateSig
            lastRetainedTelemetryAtMs = now
            client.publish(AdminBridge.serverSlotTopic(activeRoomCode), pkt, retain = true)
        }
        client.publish(AdminBridge.SRV_LIVE_TOPIC, pkt, retain = false)
    }

    private fun publishAdminServerClose() {
        if (activeRoomCode.isBlank()) return
        val client = mqttClient ?: return
        val identity = sessionIdentity
        // Limpa imediatamente o slot retido para não deixar fantasma no broker
        client.publish(AdminBridge.serverSlotTopic(activeRoomCode), ByteArray(0), retain = true)
        if (identity != null) {
            val closePkt = AdminBridge.buildEncryptedServerReport(
                hostIdentity = identity,
                isOpen = false,
                roomCode = activeRoomCode,
                isPublicRoom = isPublicRoom,
                roomTitle = "",
                hostName = myDisplayName,
                hostTag = myAccountTag,
                hostUid = myAccountUid,
                members = emptyList(),
                maxPlayers = maxTotalPlayers,
                ipProtected = ipProtectionActive,
                gamePortOpen = false,
                mapMode = "",
                startedAtMs = sessionStartedAtMs
            )
            if (closePkt != null) {
                client.publish(AdminBridge.SRV_LIVE_TOPIC, closePkt, retain = false)
            }
        }
    }

    private fun publishPublicLobbyBeacon(currentCount: Int = -1) {
        if (!isHostRole || !isPublicRoom || !sessionRunning.get()) return
        val identity = sessionIdentity ?: return
        val now = System.currentTimeMillis()
        val count = if (currentCount > 0) {
            currentCount
        } else {
            1 + peersByNode.values.count { it.isAlive(now, autoReconnectEnabled) }
        }
        val beacon = PublicLobbyBrowser.buildOpenBeaconEncrypted(
            identity = identity,
            roomCode = activeRoomCode,
            roomTitle = publicRoomTitle,
            hostName = myDisplayName,
            currentPlayers = count,
            maxPlayers = maxTotalPlayers,
            ipProtected = ipProtectionActive,
            gamePortOpen = hostGamePortOpen,
            mapMode = activeMapMode
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

                    if (isHostRole && TunnelProtocol.isOutgoingFromHostGamePort(buf, n)) {
                        lastHostGamePortTrafficAtMs = System.currentTimeMillis()
                        if (!hostGamePortOpen) {
                            hostGamePortOpen = true
                            broadcastRosterFromHost()
                            refreshSnapshotUi()
                        }
                    }

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
                    if (!sessionRunning.get() || sessionGeneration.get() != gen || tunInterface !== pfd) break
                }
            }
        }, "gdroid-tun-rx").start()
    }

    private fun writeIpv4ToTun(rawIpv4: ByteArray, offset: Int, length: Int) {
        if (length < 20) return
        val localNode = myNodeId
        if (localNode <= 0) return
        val prepared = TunnelProtocol.prepareIncomingIpv4ForLocalTun(rawIpv4, offset, length, localNode)
            ?: return
        synchronized(tunWriteLock) {
            try {
                tunOutStream?.write(prepared, 0, prepared.size)
                rxBytesTotal.addAndGet(prepared.size.toLong())
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
                    if (!sessionRunning.get() || sessionGeneration.get() != gen || udpSocket !== sock) break
                }
            }
        }, "gdroid-udp-rx").apply { isDaemon = true }.start()
    }

    private fun handleEncryptedNetworkPacket(encrypted: ByteArray, viaUdpFrom: InetSocketAddress?) {
        if (!sessionRunning.get() || encrypted.isEmpty()) return

        when (encrypted[0]) {
            RoomCrypto.HEADER_PAIRWISE_CIPHER -> {
                if (ipProtectionActive && viaUdpFrom != null) return
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
                val aliveTotal = 1 + peersByNode.values.count { it.isAlive(now, autoReconnectEnabled) }
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

                if (req.accountTag.isNotBlank()) {
                    val guestBanReason = AdminBridge.checkAccountBanReason(req.accountTag, now)
                    if (guestBanReason != null) {
                        val rej = roomCipher?.encrypt(
                            TunnelProtocol.buildJoinReject(
                                req.sessionId,
                                "Conta suspensa pela administração: $guestBanReason"
                            )
                        ) ?: return
                        mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                        return
                    }
                }

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

                val existingBySession = sessionToNode[req.sessionId]?.takeIf {
                    peersByNode[it]?.isAlive(now, autoReconnectEnabled) == true
                }
                val existingByUid = if (existingBySession == null && req.accountUid != 0L) {
                    peersByNode.values.firstOrNull { it.accountUid == req.accountUid }?.nodeId
                } else {
                    null
                }
                val existingNode = existingBySession ?: existingByUid

                val maxGuests = (maxTotalPlayers - 1).coerceIn(1, 7)
                val aliveGuests = peersByNode.values.count { it.isAlive(now, autoReconnectEnabled) }
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

                val assigned = existingNode ?: assignNodeForClient(
                    sessionId = req.sessionId,
                    accountUid = req.accountUid,
                    preferredNodeId = req.preferredNodeId
                )
                if (assigned < 0) {
                    val rej = roomCipher?.encrypt(
                        TunnelProtocol.buildJoinReject(
                            req.sessionId,
                            "Sala cheia ($maxTotalPlayers/$maxTotalPlayers jogadores)."
                        )
                    ) ?: return
                    mqttClient?.publish("$topicRoot/h2c/ctl", rej)
                    return
                }

                if (req.accountUid != 0L) {
                    reservedNodeByUid[req.accountUid] = assigned
                }
                if (existingByUid != null) {
                    val oldPeer = peersByNode[existingByUid]
                    if (oldPeer != null && oldPeer.sessionId != req.sessionId) {
                        sessionToNode.remove(oldPeer.sessionId)
                    }
                    sessionToNode[req.sessionId] = assigned
                }

                val pairwise = hostId.derivePairwiseCipher(clientPub, activeRoomCode, assigned)
                val isBrandNewJoin = (existingNode == null && peersByNode[assigned] == null)
                val peer = peersByNode[assigned]
                if (peer == null) {
                    peersByNode[assigned] = PeerNode(
                        assigned,
                        req.sessionId,
                        req.accountUid,
                        req.displayName,
                        pairwise,
                        req.accountTag
                    )
                } else {
                    peer.sessionId = req.sessionId
                    peer.accountUid = req.accountUid
                    peer.displayName = req.displayName
                    peer.pairwiseCipher = pairwise
                    if (req.accountTag.isNotBlank()) {
                        peer.accountTag = req.accountTag
                    }
                    peer.activeDirectEndpoint = null
                    peer.candidateEndpoints.clear()
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

                val prevNode = myNodeId
                if (prevNode != ack.assignedNodeId) {
                    if (prevNode > 0) {
                        mqttClient?.unsubscribe("$topicRoot/h2c/pkt/$prevNode")
                    }
                    myNodeId = ack.assignedNodeId
                    mqttClient?.subscribe("$topicRoot/h2c/pkt/${ack.assignedNodeId}")
                    if (!ensureTunInterfaceForNode(ack.assignedNodeId, sessionGeneration.get())) {
                        failAndStop("Não foi possível criar a interface VPN no Android.")
                        return
                    }
                } else {
                    mqttClient?.forceResubscribe("$topicRoot/h2c/pkt/${ack.assignedNodeId}")
                }

                if (wasReconnectingToastShown) {
                    wasReconnectingToastShown = false
                    onModerationEventToast?.invoke("Conexão restabelecida automaticamente (${TunnelProtocol.nodeIp(myNodeId)}).")
                }

                broadcastMyCandidates()
                sendImmediatePingToPeers()
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
                val wasGameOpen = hostGamePortOpen
                maxTotalPlayers = roster.maxPlayers
                remoteRosterCache = roster.members
                hostGamePortOpen = roster.gamePortOpen
                activeMapMode = roster.mapMode
                peersByNode[TunnelProtocol.HOST_NODE_ID]?.lastAnyRxAt = now
                refreshSnapshotUi()
                if (!wasGameOpen && roster.gamePortOpen) {
                    onModerationEventToast?.invoke(
                        "Partida aberta! Entre pela aba LAN do GMod ou ${TunnelProtocol.HOST_CONNECT_CMD}"
                    )
                }
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

        if (!isHostRole && wasReconnectingToastShown) {
            wasReconnectingToastShown = false
            onModerationEventToast?.invoke("Conexão restabelecida automaticamente (${TunnelProtocol.nodeIp(myNodeId)}).")
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
                if (ipProtectionActive) return
                if (plain.size < 3) return
                val frameSender = plain[1].toInt() and 0xFF
                if (frameSender != authenticatedSender) return
                val endpoints = TunnelProtocol.parseCandidatesEndpoints(plain.copyOfRange(2, plain.size))
                for (ep in endpoints) {
                    if (ep !in peer.candidateEndpoints) {
                        if (peer.candidateEndpoints.size >= 8) {
                            peer.candidateEndpoints.removeAt(0)
                        }
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
                    val mode = when {
                        peer.isDirectActive(now) -> "P2P Direto"
                        ipProtectionActive -> "Ponte TLS • IP Oculto"
                        else -> "Ponte TLS"
                    }
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
                    val isBcast = (dstNode == TunnelProtocol.BROADCAST_NODE_ID)
                    if (!peer.allowIncomingTunnelPacket(now, payloadLen, isBcast)) {
                        return
                    }
                    when {
                        dstNode == TunnelProtocol.HOST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                        }
                        dstNode == TunnelProtocol.BROADCAST_NODE_ID -> {
                            writeIpv4ToTun(plain, 3, payloadLen)
                            for ((otherId, otherPeer) in peersByNode) {
                                if (otherId != senderNode && otherPeer.isAlive(now, autoReconnectEnabled)) {
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
                        if (removed.accountUid != 0L) {
                            reservedNodeByUid.remove(removed.accountUid)
                        }
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

    private fun assignNodeForClient(
        sessionId: Long,
        accountUid: Long,
        preferredNodeId: Int
    ): Int {
        sessionToNode[sessionId]?.let { return it }
        val now = System.currentTimeMillis()

        // 1. Tenta preservar o slot reservado da mesma conta para não alterar o IP 10.10.0.x na reconexão
        if (accountUid != 0L) {
            val reserved = reservedNodeByUid[accountUid]
            if (reserved != null && reserved in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) {
                val occ = peersByNode[reserved]
                if (occ == null || occ.accountUid == accountUid || !occ.isAlive(now, autoReconnectEnabled)) {
                    sessionToNode[sessionId] = reserved
                    return reserved
                }
            }
        }

        // 2. Tenta honrar o nó preferido informado pelo cliente em reconexão
        if (preferredNodeId in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) {
            val occ = peersByNode[preferredNodeId]
            if (occ == null || !occ.isAlive(now, autoReconnectEnabled)) {
                sessionToNode[sessionId] = preferredNodeId
                return preferredNodeId
            }
        }

        // 3. Aloca o primeiro slot livre de 2 a 8
        for (candidate in TunnelProtocol.MIN_CLIENT_NODE_ID..TunnelProtocol.MAX_CLIENT_NODE_ID) {
            val existing = peersByNode[candidate]
            if (existing == null || !existing.isAlive(now, autoReconnectEnabled)) {
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
            if (peer.isAlive(now, autoReconnectEnabled)) {
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
            clientPubKeyB64 = myId.publicKeyBase64,
            preferredNodeId = myNodeId,
            accountTag = myAccountTag
        )
        val enc = roomCipher?.encrypt(frame) ?: return
        mqttClient?.publish("$topicRoot/c2h/ctl", enc)
    }

    private fun broadcastMyCandidates() {
        if (ipProtectionActive) return
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
        if (ipProtectionActive) return
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
            for (p in peersByNode.values.filter { it.isAlive(now, autoReconnectEnabled) }) {
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
    // LOOP DE MANUTENÇÃO, DETECÇÃO DE OSCILAÇÃO E AUTO-RECONNECT
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
                    if (now - startedAt > 7_500L) {
                        failAndStop("Essa sala não existe ou já foi encerrada.")
                        break
                    }
                    sendClientJoinRequest()
                    continue
                }

                if (!isHostRole && myNodeId > 0) {
                    val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
                    val silenceMs = if (hostPeer != null) (now - hostPeer.lastAnyRxAt) else 0L

                    if (!autoReconnectEnabled) {
                        if (silenceMs > 38_000L) {
                            failAndStop("A sala foi encerrada ou a conexão caiu.")
                            break
                        }
                    } else {
                        if (silenceMs > CLIENT_RECONNECT_MAX_MS) {
                            failAndStop("Não foi possível reconectar após longo período sem resposta da sala.")
                            break
                        }
                        if (silenceMs >= 8_000L) {
                            wasReconnectingToastShown = true
                            // Re-autentica mantendo o mesmo myNodeId (10.10.0.x) sem fechar tun0
                            if (mqttClientIsOk()) {
                                sendClientJoinRequest()
                            } else if (hasPhysicalInternet && tick % 4 == 0) {
                                mqttClient?.forceReconnectNow()
                            }
                            if (silenceMs >= 16_000L && hasPhysicalInternet && tick % 6 == 0) {
                                triggerSeamlessNetworkHandover(gen)
                            }
                        }
                    }
                }

                if (!ipProtectionActive && tick % 6 == 1) {
                    udpSocket?.let { StunClient.sendBindingRequests(it) }
                }

                if (isHostRole) {
                    if (!mqttClientIsOk() && hasPhysicalInternet && autoReconnectEnabled && tick % 4 == 0) {
                        mqttClient?.forceReconnectNow()
                    }

                    val passiveGameTrafficActive = (now - lastHostGamePortTrafficAtMs < 10_000L)
                    val localGameOpen = when {
                        passiveGameTrafficActive -> true
                        tick % 2 == 0 -> SourceGameDetector.probeLocalSourceServer()
                        else -> hostGamePortOpen
                    }
                    val gameOpenChanged = (localGameOpen != hostGamePortOpen)
                    if (gameOpenChanged) {
                        hostGamePortOpen = localGameOpen
                        if (localGameOpen) {
                            onModerationEventToast?.invoke("Partida detectada no GDroid (porta 27015 ativa).")
                        }
                    }

                    var removedAny = false
                    val it = peersByNode.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        if (!e.value.isAlive(now, autoReconnectEnabled)) {
                            sessionToNode.remove(e.value.sessionId)
                            it.remove()
                            removedAny = true
                        }
                    }
                    if (gameOpenChanged || removedAny || tick % 2 == 0) {
                        broadcastRosterFromHost()
                    } else if (isPublicRoom) {
                        publishPublicLobbyBeacon()
                    }
                }

                if (!ipProtectionActive && tick % 3 == 0) {
                    broadcastMyCandidates()
                }
                sendImmediatePingToPeers()
                refreshSnapshotUi()
            }
        }, "gdroid-maint-loop").apply { isDaemon = true }.start()
    }

    private fun mqttClientIsOk(): Boolean = mqttClient?.isConnected() == true

    private fun refreshSnapshotUi() {
        if (!sessionRunning.get()) return
        val now = System.currentTimeMillis()
        val mqttOk = mqttClient?.isConnected() == true
        val alivePeers = peersByNode.values.filter { it.isAlive(now, autoReconnectEnabled) }
        val anyDirect = alivePeers.any { it.isDirectActive(now) }
        val bestPing = alivePeers.map { it.lastRttMs }.filter { it > 0 }.minOrNull() ?: -1L

        val myIp = if (myNodeId > 0) TunnelProtocol.nodeIp(myNodeId) else "Negociando..."
        val phase: StatusPhase
        val detail: String
        val routeLabel: String
        val membersList: List<TunnelProtocol.RoomMemberInfo>

        if (isHostRole) {
            membersList = buildHostMembersList(now)
            val hostOscillating = !hasPhysicalInternet || (!mqttOk && !anyDirect)

            if (hostOscillating && autoReconnectEnabled && tunInterface != null) {
                phase = StatusPhase.RECONNECTING
                routeLabel = "Reconectando"
                detail = if (!hasPhysicalInternet) {
                    "Sem sinal de internet. Aguardando Wi-Fi ou 4G/5G mantendo o servidor em 10.10.0.1..."
                } else {
                    "Oscilação de rede detectada. Reconectando servidor automaticamente sem derrubar a sala..."
                }
            } else if (alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = when {
                    anyDirect -> "P2P"
                    ipProtectionActive -> "Ponte TLS (IP Oculto)"
                    else -> "Ponte TLS"
                }
                detail = if (ipProtectionActive) {
                    "Sala ativa com Proteção de IP. Os jogadores entram pela aba LAN do GMod ou IP 10.10.0.1."
                } else {
                    "Sala ativa. Os jogadores entram pela aba LAN do GMod ou pelo IP 10.10.0.1."
                }
            } else if (mqttOk) {
                phase = StatusPhase.WAITING_FRIEND
                routeLabel = if (ipProtectionActive) "IP Oculto" else "Pronto"
                detail = if (isPublicRoom) {
                    if (ipProtectionActive) {
                        "Sala pública aberta com Proteção de IP ativa (somente Ponte TLS)."
                    } else {
                        "Sala pública aberta e visível na lista para todos."
                    }
                } else {
                    "Sala privada pronta. Envie o código $activeRoomCode para entrarem."
                }
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "..."
                detail = "Registrando sala segura $activeRoomCode..."
            }
        } else {
            val hostPeer = peersByNode[TunnelProtocol.HOST_NODE_ID]
            val silenceMs = if (hostPeer != null) (now - hostPeer.lastAnyRxAt) else 0L
            val clientReconnecting = myNodeId > 0 && autoReconnectEnabled &&
                (!hasPhysicalInternet || silenceMs >= 8_000L || (!mqttOk && !anyDirect))

            if (clientReconnecting) {
                phase = StatusPhase.RECONNECTING
                routeLabel = "Reconectando"
                detail = if (!hasPhysicalInternet) {
                    "Sem internet no momento. Aguardando Wi-Fi ou 4G/5G mantendo seu IP $myIp..."
                } else {
                    "Oscilação de rede detectada. Reconectando automaticamente na sala mantendo $myIp..."
                }
                membersList = buildClientMembersList(bestPing, routeLabel, myIp)
            } else if (myNodeId > 0 && alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = when {
                    anyDirect -> "P2P"
                    ipProtectionActive -> "Ponte TLS (IP Oculto)"
                    else -> "Ponte TLS"
                }
                detail = if (ipProtectionActive) {
                    "Conectado com IP oculto na sala de $hostDisplayNameOnClient. Entre pela aba LAN do GMod ou 10.10.0.1."
                } else {
                    "Conectado na sala de $hostDisplayNameOnClient. Entre pela aba LAN do GMod ou 10.10.0.1."
                }
                membersList = buildClientMembersList(bestPing, routeLabel, myIp)
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

        val snap = TunnelSnapshot(
            phase = phase,
            isHost = isHostRole,
            isPublicRoom = isPublicRoom,
            ipProtected = ipProtectionActive,
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
            rxBytes = rxBytesTotal.get(),
            gamePortOpen = hostGamePortOpen,
            mapMode = activeMapMode
        )
        publishState(snap)
        updateLiveNotification(snap)
    }

    private fun buildClientMembersList(
        bestPing: Long,
        routeLabel: String,
        myIp: String
    ): List<TunnelProtocol.RoomMemberInfo> {
        return if (remoteRosterCache.isNotEmpty()) {
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
        unregisterPhysicalNetworkMonitor()

        if (wasRunning && myNodeId > 0) {
            try {
                if (isHostRole) {
                    publishAdminServerClose()
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
            tunBoundNodeId = 0
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

    private fun ensureNotificationChannel(nm: NotificationManager) {
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
    }

    private fun buildRichNotification(
        titleText: String,
        shortText: String,
        expandedText: String,
        stopLabel: String
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val copyIntent = PendingIntent.getBroadcast(
            this,
            12,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_COPY_CONNECT
                putExtra(EXTRA_CALLER_TOKEN, INTERNAL_CALLER_TOKEN)
                putExtra(NotificationActionReceiver.EXTRA_CONNECT_CMD, TunnelProtocol.HOST_CONNECT_CMD)
            },
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(titleText)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Copiar connect", copyIntent)
            .addAction(0, stopLabel, stopIntent)
            .build()
    }

    private fun startForegroundSafe(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(nm)

        val stopLabel = if (isHostRole) "Encerrar" else "Sair da Sala"
        val hintLine = if (isHostRole) {
            "Abra um mapa Multiplayer/LAN no GMod (visível na aba LAN)"
        } else {
            "No GMod: aba LAN ou console ${TunnelProtocol.HOST_CONNECT_CMD}"
        }
        val notif = buildRichNotification(
            titleText = "gLAN Ativo",
            shortText = contentText,
            expandedText = "$contentText\n$hintLine",
            stopLabel = stopLabel
        )
        lastNotifSignature = "init|$contentText|$stopLabel"

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

    private fun updateLiveNotification(snap: TunnelSnapshot) {
        if (!sessionRunning.get()) return
        val roleTitle = if (snap.isHost) "Servidor" else "Sala"
        val title = "gLAN • $roleTitle ${snap.roomCode} (${snap.totalPlayersInRoom}/${snap.maxTotalPlayers})"
        val gamePart = if (snap.gamePortOpen) "Partida aberta" else "Aguardando mapa"
        val pingPart = when {
            snap.phase == StatusPhase.RECONNECTING -> "Reconectando"
            snap.pingMs > 0L -> "${snap.pingMs} ms"
            else -> snap.routeMode
        }
        val mapPart = if (snap.mapMode.isNotBlank()) " • ${snap.mapMode}" else ""
        val shortText = "$gamePart$mapPart • $pingPart"
        val roleHint = if (snap.isHost) {
            "Servidor: ${snap.myVirtualIp}:27015 (listado na aba LAN do GMod)"
        } else {
            "Meu IP: ${snap.myVirtualIp} • Aba LAN ou ${TunnelProtocol.HOST_CONNECT_CMD}"
        }
        val expandedText = "$shortText\n$roleHint"
        val stopLabel = if (snap.isHost) "Encerrar" else "Sair da Sala"

        val sig = "$title|$shortText|$expandedText|$stopLabel"
        if (sig == lastNotifSignature) return
        lastNotifSignature = sig

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureNotificationChannel(nm)
            val notif = buildRichNotification(title, shortText, expandedText, stopLabel)
            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) { }
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
