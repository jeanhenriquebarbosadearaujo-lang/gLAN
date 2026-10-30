package com.gdroidlan.app.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

object TunnelProtocol {

    const val HOST_NODE_ID = 1
    const val MIN_CLIENT_NODE_ID = 2
    const val MAX_CLIENT_NODE_ID = 250
    const val BROADCAST_NODE_ID = 255
    const val HOST_VIRTUAL_IP = "10.10.0.1"
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
        val displayName: String
    )

    data class JoinAckData(
        val sessionId: Long,
        val assignedNodeId: Int,
        val maxClients: Int,
        val hostName: String
    )

    data class RejectOrKickData(
        val sessionId: Long,
        val targetNodeId: Int,
        val reason: String
    )

    data class RosterPayload(
        val maxClients: Int,
        val members: List<RoomMemberInfo>
    )

    fun nodeIp(nodeId: Int): String = "10.10.0.${nodeId and 0xFF}"

    fun buildJoinReq(sessionId: Long, accountUid: Long, displayName: String): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("uid", accountUid)
            put("name", displayName.take(24))
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_REQ).put(jb).array()
    }

    fun parseJoinReq(plain: ByteArray): JoinRequestData? {
        if (plain.size < 2 || plain[0] != TYPE_JOIN_REQ) return null
        // Compatibilidade caso receba formato binário antigo de 9 bytes
        if (plain.size == 9 && plain[1] != '{'.code.toByte()) {
            val sid = ByteBuffer.wrap(plain, 1, 8).long
            return JoinRequestData(sid, 0L, "Jogador")
        }
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            JoinRequestData(
                sessionId = j.getLong("sid"),
                accountUid = j.optLong("uid", 0L),
                displayName = j.optString("name", "Jogador").trim().ifBlank { "Jogador" }.take(24)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildJoinAck(
        sessionId: Long,
        assignedNodeId: Int,
        maxClients: Int,
        hostName: String
    ): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("node", assignedNodeId and 0xFF)
            put("max", maxClients)
            put("hostName", hostName.take(24))
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_ACK).put(jb).array()
    }

    fun parseJoinAck(plain: ByteArray): JoinAckData? {
        if (plain.size < 2 || plain[0] != TYPE_JOIN_ACK) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            JoinAckData(
                sessionId = j.getLong("sid"),
                assignedNodeId = j.getInt("node"),
                maxClients = j.optInt("max", 8),
                hostName = j.optString("hostName", "Anfitrião").ifBlank { "Anfitrião" }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildJoinReject(sessionId: Long, reason: String): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("reason", reason)
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_JOIN_REJECT).put(jb).array()
    }

    fun buildKick(sessionId: Long, targetNodeId: Int, reason: String): ByteArray {
        val j = JSONObject().apply {
            put("sid", sessionId)
            put("node", targetNodeId and 0xFF)
            put("reason", reason)
        }
        val jb = j.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_KICK).put(jb).array()
    }

    fun parseRejectOrKick(plain: ByteArray): RejectOrKickData? {
        if (plain.size < 2) return null
        return try {
            val j = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            RejectOrKickData(
                sessionId = j.optLong("sid", 0L),
                targetNodeId = j.optInt("node", 0),
                reason = j.optString("reason", "Conexão encerrada pelo anfitrião.")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildRoster(maxClients: Int, members: List<RoomMemberInfo>): ByteArray {
        val arr = JSONArray()
        for (m in members) {
            arr.put(
                JSONObject().apply {
                    put("node", m.nodeId)
                    put("name", m.displayName.take(24))
                    put("host", m.isHost)
                    put("ping", m.pingMs)
                    put("route", m.routeLabel)
                }
            )
        }
        val root = JSONObject().apply {
            put("max", maxClients)
            put("members", arr)
        }
        val jb = root.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + jb.size).put(TYPE_ROSTER).put(jb).array()
    }

    fun parseRoster(plain: ByteArray): RosterPayload? {
        if (plain.size < 2 || plain[0] != TYPE_ROSTER) return null
        return try {
            val root = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
            val max = root.optInt("max", 8)
            val arr = root.optJSONArray("members") ?: JSONArray()
            val list = ArrayList<RoomMemberInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nId = o.optInt("node", 0)
                if (nId <= 0) continue
                list.add(
                    RoomMemberInfo(
                        nodeId = nId,
                        sessionId = 0L,
                        accountUid = 0L,
                        displayName = o.optString("name", "Jogador"),
                        virtualIp = nodeIp(nId),
                        isHost = o.optBoolean("host", nId == HOST_NODE_ID),
                        pingMs = o.optLong("ping", -1L),
                        routeLabel = o.optString("route", "Ativo")
                    )
                )
            }
            RosterPayload(max, list)
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

    fun parseCandidatesEndpoints(jsonBytes: ByteArray): List<InetSocketAddress> {
        val out = ArrayList<InetSocketAddress>(2)
        try {
            val j = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val locIp = j.optString("locIp", "")
            val locPort = j.optInt("locPort", 0)
            if (locIp.isNotBlank() && locPort in 1..65535) {
                out.add(InetSocketAddress(InetAddress.getByName(locIp), locPort))
            }
            val pubIp = j.optString("pubIp", "")
            val pubPort = j.optInt("pubPort", 0)
            if (pubIp.isNotBlank() && pubPort in 1..65535) {
                val addr = InetSocketAddress(InetAddress.getByName(pubIp), pubPort)
                if (addr !in out) out.add(addr)
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

    fun buildBye(senderNodeId: Int): ByteArray =
        byteArrayOf(TYPE_BYE, (senderNodeId and 0xFF).toByte())

    fun inspectOutgoingIpv4Target(pkt: ByteArray, len: Int): Int {
        if (len < 20) return -1
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version != 4) return -1

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
}
