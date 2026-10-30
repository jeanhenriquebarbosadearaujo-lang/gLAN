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
        val roomCode: String = "",
        val myVirtualIp: String = "—",
        val hostVirtualIp: String = TunnelProtocol.HOST_VIRTUAL_IP,
        val statusText: String = "VPN desligada. Escolha Criar Servidor ou Entrar na Sala.",
        val routeMode: String = "—",
        val pingMs: Long = -1L,
        val connectedPeersCount: Int = 0,
        val txBytes: Long = 0L,
        val rxBytes: Long = 0L
    )

    private class PeerNode(val nodeId: Int, val sessionId: Long) {
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
        const val EXTRA_ROOM_CODE = "room_code"

        private const val CHANNEL_ID = "gdroid_lan_vpn_channel"
        private const val NOTIF_ID = 4101

        @Volatile
        var currentSnapshot: TunnelSnapshot = TunnelSnapshot()
            private set

        private val listeners = CopyOnWriteArrayList<(TunnelSnapshot) -> Unit>()
        @Volatile var onManualPongToast: ((Long, String) -> Unit)? = null

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

    @Volatile private var myNodeId: Int = 0
    private val clientSessionId: Long = SecureRandom().nextLong()
    @Volatile private var myPublicEndpoint: InetSocketAddress? = null
    @Volatile private var myLocalEndpoint: InetSocketAddress? = null

    private val peersByNode = ConcurrentHashMap<Int, PeerNode>()
    private val sessionToNode = ConcurrentHashMap<Long, Int>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnelSession(userRequested = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_PING -> {
                if (sessionRunning.get()) {
                    manualPingPending.set(true)
                    Thread({ sendImmediatePingToPeers() }, "gdroid-manual-ping").start()
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
                            statusText = "Código da sala inválido (mínimo 4 caracteres)."
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                val wantHost = (intent.action == ACTION_START_HOST)
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
    // CICLO DE VIDA DA SESSÃO VPN + SINALIZAÇÃO + UDP P2P
    // =========================================================================

    private fun startTunnelSession(asHost: Boolean, roomCode: String) {
        stopTunnelSession(userRequested = false)

        isHostRole = asHost
        activeRoomCode = roomCode
        topicRoot = "gdroidlan/v1/${RoomCrypto.deriveTopicHash(roomCode)}"
        cipher = RoomCrypto.SessionCipher(roomCode)
        peersByNode.clear()
        sessionToNode.clear()
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
                roomCode = roomCode,
                myVirtualIp = if (asHost) TunnelProtocol.HOST_VIRTUAL_IP else "Negociando...",
                statusText = if (asHost) "Iniciando Servidor LAN..." else "Procurando sala $roomCode..."
            )
        )

        Thread({
            try {
                // 1) Abre socket UDP protegido fora da VPN para P2P direto + STUN
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

                // 2) Se for Servidor (Host), já levanta a interface tun0 10.10.0.1 imediatamente
                if (asHost) {
                    if (!establishTunInterface(TunnelProtocol.HOST_NODE_ID)) {
                        failAndStop("Permissão de VPN recusada ou indisponível no aparelho.")
                        return@Thread
                    }
                }

                // 3) Inicia canal criptografado de sinalização e ponte CGNAT (MQTT TLS)
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

                // 4) Loop de manutenção: handshake do cliente, hole punching UDP, ping e limpeza
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
            .setSession("GDroid LAN ($activeRoomCode)")
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
    // LEITURA DA INTERFACE TUN0 (PACOTES DO JOGO GDROID -> TÚNEL)
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

    // =========================================================================
    // LEITURA UDP DIRETA (HOLE PUNCHING P2P + STUN)
    // =========================================================================

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

                    // 1) Verifica se é resposta STUN (0x0101)
                    if (buf[0] == 0x01.toByte() && buf[1] == 0x01.toByte()) {
                        val mapped = StunClient.tryParseBindingResponse(buf, len)
                        if (mapped != null && mapped != myPublicEndpoint) {
                            myPublicEndpoint = mapped
                            if (myNodeId > 0) broadcastMyCandidates()
                        }
                        continue
                    }

                    // 2) Pacote criptografado P2P do túnel (0x47)
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
    // PROCESSAMENTO UNIFICADO DE FRAMES (CONTROLE, PING E PACOTES IPv4)
    // =========================================================================

    private fun handleDecryptedFrame(plain: ByteArray, viaUdpFrom: InetSocketAddress?) {
        if (plain.isEmpty() || !sessionRunning.get()) return
        val now = System.currentTimeMillis()

        when (plain[0]) {
            TunnelProtocol.TYPE_JOIN_REQ -> {
                if (!isHostRole || plain.size < 9) return
                val reqSessionId = ByteBuffer.wrap(plain, 1, 8).long
                val assigned = assignNodeForClient(reqSessionId)
                val peer = peersByNode.getOrPut(assigned) { PeerNode(assigned, reqSessionId) }
                peer.lastAnyRxAt = now
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                val ack = cipher?.encrypt(TunnelProtocol.buildJoinAck(reqSessionId, assigned)) ?: return
                mqttClient?.publish("$topicRoot/h2c/ctl", ack)
                broadcastMyCandidates()
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_JOIN_ACK -> {
                if (isHostRole || plain.size < 10) return
                val ackSessionId = ByteBuffer.wrap(plain, 1, 8).long
                if (ackSessionId != clientSessionId) return
                val assigned = plain[9].toInt() and 0xFF
                if (assigned !in 2..9) return

                val hostPeer = peersByNode.getOrPut(TunnelProtocol.HOST_NODE_ID) {
                    PeerNode(TunnelProtocol.HOST_NODE_ID, 0L)
                }
                hostPeer.lastAnyRxAt = now

                if (myNodeId == 0) {
                    myNodeId = assigned
                    mqttClient?.subscribe("$topicRoot/h2c/pkt/$assigned")
                    if (!establishTunInterface(assigned)) {
                        failAndStop("Não foi possível criar a interface VPN no Android.")
                        return
                    }
                    broadcastMyCandidates()
                    sendImmediatePingToPeers()
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_CANDIDATES -> {
                if (plain.size < 3) return
                val senderNode = plain[1].toInt() and 0xFF
                if (senderNode == myNodeId || senderNode <= 0) return
                if (!isHostRole && senderNode != TunnelProtocol.HOST_NODE_ID) return

                val peer = peersByNode.getOrPut(senderNode) { PeerNode(senderNode, 0L) }
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

                val peer = peersByNode.getOrPut(senderNode) { PeerNode(senderNode, 0L) }
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

                val peer = peersByNode.getOrPut(senderNode) { PeerNode(senderNode, 0L) }
                peer.lastAnyRxAt = now
                peer.lastRttMs = rtt
                if (viaUdpFrom != null) {
                    peer.activeDirectEndpoint = viaUdpFrom
                    peer.lastDirectRxAt = now
                }

                if (manualPingPending.compareAndSet(true, false)) {
                    val mode = if (peer.isDirectActive(now)) "Direto P2P" else "Ponte CGNAT"
                    onManualPongToast?.invoke(rtt, mode)
                }
                refreshSnapshotUi()
            }

            TunnelProtocol.TYPE_IPV4_PKT -> {
                if (plain.size <= 23 || myNodeId <= 0) return
                val senderNode = plain[1].toInt() and 0xFF
                val dstNode = plain[2].toInt() and 0xFF
                if (senderNode == myNodeId) return

                val peer = peersByNode.getOrPut(senderNode) { PeerNode(senderNode, 0L) }
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
                        dstNode in 2..9 -> {
                            val reEnc = cipher?.encrypt(plain) ?: return
                            sendToSpecificClient(dstNode, reEnc, isGamePacket = true)
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
                    peersByNode.remove(senderNode)
                    refreshSnapshotUi()
                } else if (senderNode == TunnelProtocol.HOST_NODE_ID) {
                    failAndStop("O servidor encerrou a sala $activeRoomCode.")
                }
            }
        }
    }

    // =========================================================================
    // ENVIO HÍBRIDO INTELIGENTE (UDP P2P DIRETO -> FALLBACK PONTE CGNAT)
    // =========================================================================

    private fun assignNodeForClient(sessionId: Long): Int {
        sessionToNode[sessionId]?.let { return it }
        val now = System.currentTimeMillis()
        for (candidate in 2..9) {
            val existing = peersByNode[candidate]
            if (existing == null || !existing.isAlive(now)) {
                sessionToNode[sessionId] = candidate
                return candidate
            }
        }
        return 2
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
        val peer = peersByNode[clientNode]
        val direct = peer?.activeDirectEndpoint
        if (peer != null && direct != null && peer.isDirectActive() && sendUdpRaw(encrypted, direct)) {
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
        val enc = cipher?.encrypt(TunnelProtocol.buildJoinReq(clientSessionId)) ?: return
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
    // LOOP DE MANUTENÇÃO, PING AUTOMÁTICO E ATUALIZAÇÃO DE UI
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

                // Cliente aguardando JOIN_ACK do servidor
                if (!isHostRole && myNodeId == 0) {
                    if (now - startedAt > 20_000L) {
                        failAndStop("Sala '$activeRoomCode' não encontrada. Confirme se seu amigo ligou o Servidor!")
                        break
                    }
                    sendClientJoinRequest()
                    continue
                }

                // Reenvia STUN periodicamente caso a porta NAT mude
                if (tick % 8 == 1) {
                    udpSocket?.let { StunClient.sendBindingRequests(it) }
                }

                // Remove clientes inativos no servidor
                if (isHostRole) {
                    peersByNode.entries.removeIf { !it.value.isAlive(now) }
                }

                // Envia candidatos e pings periódicos para manter NAT aberto e medir latência
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

        if (isHostRole) {
            if (alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = if (anyDirect) "Direto P2P (UDP)" else "Ponte CGNAT (TLS)"
                detail = "Amigo conectado! Abra o GDroid — ele deve entrar no IP 10.10.0.1."
            } else if (mqttOk) {
                phase = StatusPhase.WAITING_FRIEND
                routeLabel = "Pronto p/ receber"
                detail = "Servidor ativo! Mande o código $activeRoomCode para seu amigo conectar."
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "Conectando..."
                detail = "Registrando sala $activeRoomCode na rede..."
            }
        } else {
            if (myNodeId > 0 && alivePeers.isNotEmpty()) {
                phase = StatusPhase.ONLINE
                routeLabel = if (anyDirect) "Direto P2P (UDP)" else "Ponte CGNAT (TLS)"
                detail = "Conectado ao Servidor! Abra o GDroid e entre no IP 10.10.0.1."
            } else {
                phase = StatusPhase.CONNECTING
                routeLabel = "Buscando sala..."
                detail = "Procurando servidor da sala $activeRoomCode..."
            }
        }

        publishState(
            TunnelSnapshot(
                phase = phase,
                isHost = isHostRole,
                roomCode = activeRoomCode,
                myVirtualIp = myIp,
                hostVirtualIp = TunnelProtocol.HOST_VIRTUAL_IP,
                statusText = detail,
                routeMode = routeLabel,
                pingMs = bestPing,
                connectedPeersCount = if (isHostRole) alivePeers.size else if (phase == StatusPhase.ONLINE) 1 else 0,
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
                statusText = errorMsg
            )
        )
        stopSelf()
    }

    private fun stopTunnelSession(userRequested: Boolean) {
        val wasRunning = sessionRunning.getAndSet(false)
        if (wasRunning && myNodeId > 0) {
            try {
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
            publishState(TunnelSnapshot())
        }
    }

    // =========================================================================
    // NOTIFICAÇÃO DE PRIMEIRO PLANO E WAKELOCK
    // =========================================================================

    private fun startForegroundSafe(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GDroid LAN VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a rede virtual LAN ativa durante o jogo"
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
            .setContentTitle("GDroid LAN Ativo")
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
