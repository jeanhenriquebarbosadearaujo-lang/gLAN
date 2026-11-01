package com.gdroidlan.app.vpn

import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.update.OfficialVersionCert
import com.gdroidlan.app.update.UpdateNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.PublicKey
import kotlin.math.abs

object TunnelProtocol {

    const val HOST_NODE_ID = 1
    const val MIN_CLIENT_NODE_ID = 2
    const val MAX_CLIENT_NODE_ID = 8 // Nós 1..8 = máximo de 8 jogadores na partida
    const val MIN_TOTAL_PLAYERS = 2
    const val MAX_TOTAL_PLAYERS = 8
    const val BROADCAST_NODE_ID = 255
    const val HOST_VIRTUAL_IP = "10.10.0.1"
    const val SOURCE_GAME_PORT = 27015
    const val HOST_CONNECT_CMD = "connect 10.10.0.1:27015"
    const val VPN_MTU = 1360

    const val TYPE_JOIN_REQ: Byte = 0x01
    const val TYPE_JOIN_ACK: Byte = 0x02
    const val TYPE_CANDIDATES: Byte = 0x03
    const val TYPE_PING: Byte = 0x04
    const val TYPE_PONG: Byte = 0x05
    const val TYPE_IPV4_PKT: Byte = 0x06
    const val TYPE_BYE: Byte = 0x07
    const val TYPE_JOIN_REJECT: Byte = 0x08
    const val TYPE_KICK: Byte = 0x09
    const val TYPE_ROSTER: Byte = 0x0A
    const val TYPE_PROBE_REQ: Byte = 0x0B
    const val TYPE_PROBE_ACK: Byte = 0x0C

    const val PROBE_STATUS_OK = 0
    const val PROBE_STATUS_FULL = 1
    const val PROBE_STATUS_BLOCKED = 2

    private const val MAX_SIGN_SKEW_MS = 180_000L

    // Portas sensíveis do sistema Android bloqueadas no firewall interno da rede virtual
    private val BLOCKED_SYSTEM_PORTS = setOf(
        21, 22, 23, 135, 137, 138, 139, 445, 5037, 5555, 5556, 8022
    )

    data class RoomMemberInfo(
        val nodeId: Int,
        val sessionId: Long,
        val accountUid: Long,
        val displayName: String,
        val virtualIp: String,
        val isHost: Boolean,
        val pingMs: Long,
        val routeLabel: String
    )

    data class JoinRequestData(
        val sessionId: Long,
        val accountUid: Long,
        val displayName: String,
        val clientPubKeyB64: String,
        val preferredNodeId: Int = 0
    )

    data class JoinAckData(
        val sessionId: Long,
        val assignedNodeId: Int,
        val maxPlayers: Int,
        val hostName: String,
        val hostPubKeyB64: String,
        val signatureB64: String
    )

    data class RejectOrKickData(
        val sessionId: Long,
        val targetNodeId: Int,
        val targetAccountUid: Long = 0L,
        val reason: String
    )

    data class RosterPayload(
        val maxPlayers: Int,
        val members: List<RoomMemberInfo>,
        val gamePortOpen: Boolean = false,
        val mapMode: String = ""
    )

    data class ProbeReqData(
        val probeId: Long,
        val accountUid: Long
    )

    data class ProbeAckData(
        val probeId: Long,
        val status: Int,
        val curPlayers: Int,
        val maxPlayers: Int
    )

    fun nodeIp(nodeId: Int): String = "10.10.0.${nodeId and 0xFF}"

    private fun joinAckSignPayload(
        sessionId: Long,
        assignedNodeId: Int,
        maxPlayers: Int,
        hostName: String,
        clientPubKeyB64: String
    ): ByteArray = "v26|ack|$sessionId|$assignedNodeId|$maxPlayers|$hostName|$clientPubKeyB64"
        .toByteArray(Charsets.UTF_8)

    private fun rosterSignPayload(maxPlayers: Int, ts: Long, arrJson: String): ByteArray =
        "v26|roster|$maxPlayers|$ts|$arrJson".toByteArray(Charsets.UTF_8)

    private fun rosterSignPayloadV36(
        maxPlayers: Int,
        ts: Long,
        arrJson: String,
        gamePortOpen: Boolean,
        mapMode: String
    ): ByteArray = "v36|roster|$maxPlayers|$ts|$arrJson|${if (gamePortOpen) 1 else 0}|$mapMode"
        .toByteArray(Charsets.UTF_8)

    private fun hostByeSignPayload(ts: Long): ByteArray =
        "v26|hostbye|$ts".toByteArray(Charsets.UTF_8)

    private fun kickSignPayload(
        sessionId: Long,
        node: Int,
        accountUid: Long,
        reason: String,
        ts: Long
    ): ByteArray = "v29|kick|$sessionId|$node|$accountUid|$reason|$ts".toByteArray(Charsets.UTF_8)

    fun buildJoinReq(
        sessionId: Long,
        accountUid: Long,
        displayName: String,
        clientPubKeyB64: String,
        preferredNodeId: Int = 0
    ): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("uid", accountUid)
            put("name", RoomCrypto.cleanSafeText(displayName, 24).ifBlank { "Jogador" })
            put("cpub", clientPubKeyB64)
            if (preferredNodeId in MIN_CLIENT_NODE_ID..MAX_CLIENT_NODE_ID) {
                put("prefNode", preferredNodeId)
            }
            UpdateNotifier.getBuiltInVerifiedRelease()?.let { rel ->
                put("vCode", rel.versionCode)
                put("vName", rel.versionName)
                put("vSig", rel.signatureB64)
            }
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_REQ).put(jb).array()
    }

    fun parseJoinReq(plain: ByteArray): JoinRequestData? {
        if (plain.size < 2 || plain[0] != TYPE_JOIN_REQ) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            val cpub = j.optString("cpub", "").trim()
            if (cpub.isBlank()) return null
            val vCode = j.optInt("vCode", 0)
            if (vCode > 0) {
                UpdateNotifier.verifyFields(vCode, j.optString("vName", ""), j.optString("vSig", ""))?.let { rel ->
                    UpdateNotifier.onVerifiedReleaseObserved(rel, OfficialVersionCert.BUILT_VERSION_CODE)
                }
            }
            val prefNode = j.optInt("prefNode", 0)
            JoinRequestData(
                sessionId = j.getLong("sid"),
                accountUid = j.optLong("uid", 0L),
                displayName = RoomCrypto.cleanSafeText(j.optString("name", ""), 24).ifBlank { "Jogador" },
                clientPubKeyB64 = cpub,
                preferredNodeId = if (prefNode in MIN_CLIENT_NODE_ID..MAX_CLIENT_NODE_ID) prefNode else 0
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildJoinAck(
        hostIdentity: RoomCrypto.IdentityKeyBox,
        sessionId: Long,
        assignedNodeId: Int,
        maxPlayers: Int,
        hostName: String,
        clientPubKeyB64: String
    ): ByteArray {
        val cleanHost = RoomCrypto.cleanSafeText(hostName, 24).ifBlank { "Anfitrião" }
        val max = maxPlayers.coerceIn(MIN_TOTAL_PLAYERS, MAX_TOTAL_PLAYERS)
        val node = assignedNodeId and 0xFF
        val sig = hostIdentity.signToBase64(
            joinAckSignPayload(sessionId, node, max, cleanHost, clientPubKeyB64)
        )
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("node", node)
            put("max", max)
            put("hostName", cleanHost)
            put("hpub", hostIdentity.publicKeyBase64)
            put("sig", sig)
            UpdateNotifier.getBuiltInVerifiedRelease()?.let { rel ->
                put("vCode", rel.versionCode)
                put("vName", rel.versionName)
                put("vSig", rel.signatureB64)
            }
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_ACK).put(jb).array()
    }

    fun parseAndVerifyJoinAck(plain: ByteArray, expectedClientPubB64: String): Pair<JoinAckData, PublicKey>? {
        if (plain.size < 2 || plain[0] != TYPE_JOIN_ACK) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            val sid = j.getLong("sid")
            val node = j.getInt("node")
            val max = j.optInt("max", MAX_TOTAL_PLAYERS).coerceIn(MIN_TOTAL_PLAYERS, MAX_TOTAL_PLAYERS)
            val hostName = RoomCrypto.cleanSafeText(j.optString("hostName", ""), 24).ifBlank { "Anfitrião" }
            val hpubB64 = j.optString("hpub", "")
            val sigB64 = j.optString("sig", "")
            val hostPub = RoomCrypto.decodeEcPublicKey(hpubB64) ?: return null

            if (!RoomCrypto.verifySignature(
                    hostPub,
                    joinAckSignPayload(sid, node, max, hostName, expectedClientPubB64),
                    sigB64
                )
            ) {
                return null
            }

            val vCode = j.optInt("vCode", 0)
            if (vCode > 0) {
                UpdateNotifier.verifyFields(vCode, j.optString("vName", ""), j.optString("vSig", ""))?.let { rel ->
                    UpdateNotifier.onVerifiedReleaseObserved(rel, OfficialVersionCert.BUILT_VERSION_CODE)
                }
            }

            JoinAckData(
                sessionId = sid,
                assignedNodeId = node,
                maxPlayers = max,
                hostName = hostName,
                hostPubKeyB64 = hpubB64,
                signatureB64 = sigB64
            ) to hostPub
        } catch (_: Exception) {
            null
        }
    }

    fun buildJoinReject(sessionId: Long, reason: String): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("reason", RoomCrypto.cleanSafeText(reason, 120))
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_REJECT).put(jb).array()
    }

    fun buildKick(sessionId: Long, targetNodeId: Int, reason: String): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("node", targetNodeId and 0xFF)
            put("reason", RoomCrypto.cleanSafeText(reason, 120))
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_KICK).put(jb).array()
    }

    fun buildSignedKick(
        hostIdentity: RoomCrypto.IdentityKeyBox,
        sessionId: Long,
        targetNodeId: Int,
        targetAccountUid: Long,
        reason: String
    ): ByteArray {
        val node = targetNodeId and 0xFF
        val cleanReason = RoomCrypto.cleanSafeText(reason, 120).ifBlank {
            "Você foi expulso da sala pelo anfitrião."
        }
        val ts = System.currentTimeMillis()
        val sig = hostIdentity.signToBase64(
            kickSignPayload(sessionId, node, targetAccountUid, cleanReason, ts)
        )
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("node", node)
            put("uid", targetAccountUid)
            put("reason", cleanReason)
            put("ts", ts)
            put("sig", sig)
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_KICK).put(jb).array()
    }

    fun parseAndVerifySignedKick(plain: ByteArray, hostPubKey: PublicKey?): RejectOrKickData? {
        if (hostPubKey == null || plain.size < 2 || plain[0] != TYPE_KICK) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            val ts = j.optLong("ts", 0L)
            if (ts <= 0L || abs(System.currentTimeMillis() - ts) > MAX_SIGN_SKEW_MS) return null
            val sid = j.optLong("sid", 0L)
            val node = j.optInt("node", 0)
            val uid = j.optLong("uid", 0L)
            val reason = RoomCrypto.cleanSafeText(
                j.optString("reason", "Você foi expulso da sala pelo anfitrião."),
                120
            ).ifBlank { "Você foi expulso da sala pelo anfitrião." }
            val sig = j.optString("sig", "")
            if (!RoomCrypto.verifySignature(
                    hostPubKey,
                    kickSignPayload(sid, node, uid, reason, ts),
                    sig
                )
            ) {
                return null
            }
            RejectOrKickData(
                sessionId = sid,
                targetNodeId = node,
                targetAccountUid = uid,
                reason = reason
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseRejectOrKick(plain: ByteArray): RejectOrKickData? {
        if (plain.size < 2) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            RejectOrKickData(
                sessionId = j.optLong("sid", 0L),
                targetNodeId = j.optInt("node", 0),
                targetAccountUid = j.optLong("uid", 0L),
                reason = RoomCrypto.cleanSafeText(
                    j.optString("reason", "Conexão encerrada pelo anfitrião."),
                    120
                ).ifBlank { "Conexão encerrada pelo anfitrião." }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildRoster(
        hostIdentity: RoomCrypto.IdentityKeyBox,
        maxPlayers: Int,
        members: List<RoomMemberInfo>,
        gamePortOpen: Boolean = false,
        mapMode: String = ""
    ): ByteArray {
        val arr = JSONArray()
        for (m in members) {
            arr.put(
                JSONObject().apply {
                    put("node", m.nodeId)
                    put("name", RoomCrypto.cleanSafeText(m.displayName, 24).ifBlank { "Jogador" })
                    put("host", m.isHost)
                    put("ping", m.pingMs)
                    put("route", RoomCrypto.cleanSafeText(m.routeLabel, 16))
                }
            )
        }
        val max = maxPlayers.coerceIn(MIN_TOTAL_PLAYERS, MAX_TOTAL_PLAYERS)
        val cleanMap = RoomCrypto.cleanSafeText(mapMode, 28)
        val ts = System.currentTimeMillis()
        val arrStr = arr.toString()
        val sig = hostIdentity.signToBase64(rosterSignPayload(max, ts, arrStr))
        val sig36 = hostIdentity.signToBase64(rosterSignPayloadV36(max, ts, arrStr, gamePortOpen, cleanMap))

        val root = JSONObject().apply {
            put("max", max)
            put("ts", ts)
            put("members", arr)
            put("pub", hostIdentity.publicKeyBase64)
            put("sig", sig)
            put("gOpen", gamePortOpen)
            put("map", cleanMap)
            put("sig36", sig36)
        }
        val jb = root.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_ROSTER).put(jb).array()
    }

    fun parseRoster(plain: ByteArray, expectedHostKey: PublicKey?): RosterPayload? {
        if (plain.size < 2 || plain[0] != TYPE_ROSTER) return null
        return try {
            val now = System.currentTimeMillis()
            val root = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            val max = root.optInt("max", MAX_TOTAL_PLAYERS).coerceIn(MIN_TOTAL_PLAYERS, MAX_TOTAL_PLAYERS)
            val ts = root.optLong("ts", 0L)
            if (ts <= 0L || abs(now - ts) > MAX_SIGN_SKEW_MS) return null

            val arr = root.optJSONArray("members") ?: return null
            val arrStr = arr.toString()
            val sigB64 = root.optString("sig", "")
            val verifyKey = expectedHostKey ?: RoomCrypto.decodeEcPublicKey(root.optString("pub", "")) ?: return null
            if (!RoomCrypto.verifySignature(verifyKey, rosterSignPayload(max, ts, arrStr), sigB64)) {
                return null
            }

            val rawGameOpen = root.optBoolean("gOpen", false)
            val rawMap = RoomCrypto.cleanSafeText(root.optString("map", ""), 28)
            val sig36B64 = root.optString("sig36", "")
            val verifiedV36 = sig36B64.isNotBlank() && RoomCrypto.verifySignature(
                verifyKey,
                rosterSignPayloadV36(max, ts, arrStr, rawGameOpen, rawMap),
                sig36B64
            )

            val list = ArrayList<RoomMemberInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nId = o.optInt("node", 0)
                if (nId !in HOST_NODE_ID..MAX_CLIENT_NODE_ID) continue
                list.add(
                    RoomMemberInfo(
                        nodeId = nId,
                        sessionId = 0L,
                        accountUid = 0L,
                        displayName = RoomCrypto.cleanSafeText(o.optString("name", ""), 24).ifBlank { "Jogador" },
                        virtualIp = nodeIp(nId),
                        isHost = o.optBoolean("host", nId == HOST_NODE_ID),
                        pingMs = o.optLong("ping", -1L),
                        routeLabel = RoomCrypto.cleanSafeText(o.optString("route", "Ativo"), 16)
                    )
                )
            }
            RosterPayload(
                maxPlayers = max,
                members = list,
                gamePortOpen = if (verifiedV36) rawGameOpen else false,
                mapMode = if (verifiedV36) rawMap else ""
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildCandidates(
        senderNodeId: Int,
        publicEndpoint: InetSocketAddress?,
        localEndpoint: InetSocketAddress?
    ): ByteArray {
        val json = JSONObject()
        if (publicEndpoint != null) {
            publicEndpoint.address?.hostAddress?.let { json.put("pubIp", it) }
            json.put("pubPort", publicEndpoint.port)
        }
        if (localEndpoint != null) {
            localEndpoint.address?.hostAddress?.let { json.put("locIp", it) }
            json.put("locPort", localEndpoint.port)
        }
        val jb = json.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(2 + jb.size)
            .put(TYPE_CANDIDATES)
            .put((senderNodeId and 0xFF).toByte())
            .put(jb)
            .array()
    }

    private fun isSafePeerEndpoint(ipStr: String, port: Int): InetSocketAddress? {
        if (ipStr.isBlank() || port !in 1024..65535 || port in BLOCKED_SYSTEM_PORTS) return null
        return try {
            val addr = InetAddress.getByName(ipStr)
            if (addr !is Inet4Address) return null
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isMulticastAddress
            ) {
                return null
            }
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            val b3 = b[3].toInt() and 0xFF
            // Bloqueia broadcast e a sub-rede virtual 10.10.0.0/24 como endpoint físico UDP
            if (b3 == 255 || (b0 == 10 && b1 == 10 && b2 == 0)) return null
            InetSocketAddress(addr, port)
        } catch (_: Exception) {
            null
        }
    }

    fun parseCandidatesEndpoints(jsonBytes: ByteArray): List<InetSocketAddress> {
        val out = ArrayList<InetSocketAddress>(2)
        try {
            val j = JSONObject(String(jsonBytes, Charsets.UTF_8))
            isSafePeerEndpoint(j.optString("locIp", ""), j.optInt("locPort", 0))?.let { out.add(it) }
            isSafePeerEndpoint(j.optString("pubIp", ""), j.optInt("pubPort", 0))?.let { pub ->
                if (pub !in out) out.add(pub)
            }
        } catch (_: Exception) { }
        return out
    }

    fun buildPing(senderNodeId: Int, targetNodeId: Int, sentAtMs: Long): ByteArray {
        return ByteBuffer.allocate(11)
            .put(TYPE_PING)
            .put((senderNodeId and 0xFF).toByte())
            .put((targetNodeId and 0xFF).toByte())
            .putLong(sentAtMs)
            .array()
    }

    fun buildPong(senderNodeId: Int, targetNodeId: Int, origSentAtMs: Long): ByteArray {
        return ByteBuffer.allocate(11)
            .put(TYPE_PONG)
            .put((senderNodeId and 0xFF).toByte())
            .put((targetNodeId and 0xFF).toByte())
            .putLong(origSentAtMs)
            .array()
    }

    fun buildIpv4Frame(senderNodeId: Int, dstNodeId: Int, ipv4Buf: ByteArray, length: Int): ByteArray {
        val out = ByteArray(3 + length)
        out[0] = TYPE_IPV4_PKT
        out[1] = (senderNodeId and 0xFF).toByte()
        out[2] = (dstNodeId and 0xFF).toByte()
        System.arraycopy(ipv4Buf, 0, out, 3, length)
        return out
    }

    fun buildClientBye(senderNodeId: Int): ByteArray =
        byteArrayOf(TYPE_BYE, (senderNodeId and 0xFF).toByte())

    fun buildSignedHostBye(hostIdentity: RoomCrypto.IdentityKeyBox): ByteArray {
        val ts = System.currentTimeMillis()
        val sig = hostIdentity.signToBase64(hostByeSignPayload(ts))
        val j = JSONObject().apply {
            put("ts", ts)
            put("sig", sig)
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(2 + jb.size)
            .put(TYPE_BYE)
            .put(HOST_NODE_ID.toByte())
            .put(jb)
            .array()
    }

    fun verifySignedHostBye(plain: ByteArray, hostPubKey: PublicKey?): Boolean {
        if (hostPubKey == null || plain.size < 3 || plain[0] != TYPE_BYE) return false
        if ((plain[1].toInt() and 0xFF) != HOST_NODE_ID) return false
        return try {
            val j = JSONObject(String(plain, 2, plain.size - 2, Charsets.UTF_8))
            val ts = j.optLong("ts", 0L)
            if (ts <= 0L || abs(System.currentTimeMillis() - ts) > MAX_SIGN_SKEW_MS) return false
            val sig = j.optString("sig", "")
            RoomCrypto.verifySignature(hostPubKey, hostByeSignPayload(ts), sig)
        } catch (_: Exception) {
            false
        }
    }

    fun buildProbeReq(probeId: Long, accountUid: Long): ByteArray {
        return ByteBuffer.allocate(17)
            .put(TYPE_PROBE_REQ)
            .putLong(probeId)
            .putLong(accountUid)
            .array()
    }

    fun parseProbeReq(plain: ByteArray): ProbeReqData? {
        if (plain.size < 17 || plain[0] != TYPE_PROBE_REQ) return null
        val buf = ByteBuffer.wrap(plain, 1, 16)
        return ProbeReqData(
            probeId = buf.long,
            accountUid = buf.long
        )
    }

    fun buildProbeAck(probeId: Long, status: Int, curPlayers: Int, maxPlayers: Int): ByteArray {
        return ByteBuffer.allocate(12)
            .put(TYPE_PROBE_ACK)
            .putLong(probeId)
            .put((status and 0xFF).toByte())
            .put((curPlayers and 0xFF).toByte())
            .put((maxPlayers and 0xFF).toByte())
            .array()
    }

    fun parseProbeAck(plain: ByteArray): ProbeAckData? {
        if (plain.size < 12 || plain[0] != TYPE_PROBE_ACK) return null
        val probeId = ByteBuffer.wrap(plain, 1, 8).long
        val status = plain[9].toInt() and 0xFF
        val cur = plain[10].toInt() and 0xFF
        val max = plain[11].toInt() and 0xFF
        return ProbeAckData(probeId, status, cur, max)
    }

    /**
     * Verifica cabeçalho IPv4 e bloqueia portas sensíveis do sistema (ADB, SSH, SMB, Telnet).
     */
    private fun passesPortFirewall(pkt: ByteArray, offset: Int, len: Int): Boolean {
        if (len < 20 || offset + len > pkt.size) return false
        val ver = (pkt[offset].toInt() ushr 4) and 0x0F
        val ihl = pkt[offset].toInt() and 0x0F
        val hdrLen = ihl * 4
        if (ver != 4 || ihl < 5 || hdrLen > len) return false

        val totalLen = ((pkt[offset + 2].toInt() and 0xFF) shl 8) or (pkt[offset + 3].toInt() and 0xFF)
        if (totalLen != len) return false

        val flagsAndFrag = ((pkt[offset + 6].toInt() and 0xFF) shl 8) or (pkt[offset + 7].toInt() and 0xFF)
        val fragOffset = flagsAndFrag and 0x1FFF
        val proto = pkt[offset + 9].toInt() and 0xFF

        if (fragOffset == 0 && (proto == 6 || proto == 17)) {
            if (hdrLen + 4 > len) return false
            val srcPort = ((pkt[offset + hdrLen].toInt() and 0xFF) shl 8) or
                (pkt[offset + hdrLen + 1].toInt() and 0xFF)
            val dstPort = ((pkt[offset + hdrLen + 2].toInt() and 0xFF) shl 8) or
                (pkt[offset + hdrLen + 3].toInt() and 0xFF)
            if (srcPort in BLOCKED_SYSTEM_PORTS || dstPort in BLOCKED_SYSTEM_PORTS) {
                return false
            }
        }
        return true
    }

    fun inspectOutgoingIpv4Target(pkt: ByteArray, len: Int, expectedSenderNode: Int): Int {
        if (!passesPortFirewall(pkt, 0, len)) return -1

        val s0 = pkt[12].toInt() and 0xFF
        val s1 = pkt[13].toInt() and 0xFF
        val s2 = pkt[14].toInt() and 0xFF
        val s3 = pkt[15].toInt() and 0xFF
        if (s0 != 10 || s1 != 10 || s2 != 0 || s3 != expectedSenderNode) return -1

        val d0 = pkt[16].toInt() and 0xFF
        val d1 = pkt[17].toInt() and 0xFF
        val d2 = pkt[18].toInt() and 0xFF
        val d3 = pkt[19].toInt() and 0xFF

        if ((d0 == 10 && d1 == 10 && d2 == 0 && d3 == 255) ||
            (d0 == 255 && d1 == 255 && d2 == 255 && d3 == 255)
        ) {
            return BROADCAST_NODE_ID
        }

        if (d0 == 10 && d1 == 10 && d2 == 0 && d3 in HOST_NODE_ID..MAX_CLIENT_NODE_ID) {
            return d3
        }
        return -1
    }

    fun validateIncomingTunnelIpv4(
        pkt: ByteArray,
        offset: Int,
        len: Int,
        authenticatedSenderNode: Int
    ): Boolean {
        if (authenticatedSenderNode !in HOST_NODE_ID..MAX_CLIENT_NODE_ID) return false
        if (!passesPortFirewall(pkt, offset, len)) return false

        val s0 = pkt[offset + 12].toInt() and 0xFF
        val s1 = pkt[offset + 13].toInt() and 0xFF
        val s2 = pkt[offset + 14].toInt() and 0xFF
        val s3 = pkt[offset + 15].toInt() and 0xFF
        if (s0 != 10 || s1 != 10 || s2 != 0 || s3 != authenticatedSenderNode) return false

        val d0 = pkt[offset + 16].toInt() and 0xFF
        val d1 = pkt[offset + 17].toInt() and 0xFF
        val d2 = pkt[offset + 18].toInt() and 0xFF
        val d3 = pkt[offset + 19].toInt() and 0xFF

        val validUnicast = (d0 == 10 && d1 == 10 && d2 == 0 && d3 in HOST_NODE_ID..MAX_CLIENT_NODE_ID)
        val validBroadcast = (d0 == 10 && d1 == 10 && d2 == 0 && d3 == 255) ||
            (d0 == 255 && d1 == 255 && d2 == 255 && d3 == 255)
        return validUnicast || validBroadcast
    }

    fun isOutgoingFromHostGamePort(pkt: ByteArray, len: Int): Boolean {
        if (len < 28 || len > pkt.size) return false
        val ver = (pkt[0].toInt() ushr 4) and 0x0F
        val ihl = pkt[0].toInt() and 0x0F
        val hdrLen = ihl * 4
        if (ver != 4 || ihl < 5 || hdrLen + 4 > len) return false
        val proto = pkt[9].toInt() and 0xFF
        if (proto != 17 && proto != 6) return false
        val srcPort = ((pkt[hdrLen].toInt() and 0xFF) shl 8) or (pkt[hdrLen + 1].toInt() and 0xFF)
        return srcPort == SOURCE_GAME_PORT
    }
}
