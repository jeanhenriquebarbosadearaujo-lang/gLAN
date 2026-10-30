package com.gdroidlan.app.vpn

import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Protocolo binário da LAN Virtual 10.10.0.0/24:
 * - Host = nó 1 (10.10.0.1)
 * - Amigos/Clientes = nós 2..9 (10.10.0.2 .. 10.10.0.9)
 * - Broadcast da sub-rede = 255 (10.10.0.255 / 255.255.255.255)
 */
object TunnelProtocol {

    const val HOST_NODE_ID = 1
    const val BROADCAST_NODE_ID = 255
    const val SUBNET_PREFIX = "10.10.0."
    const val HOST_VIRTUAL_IP = "10.10.0.1"
    const val VPN_MTU = 1360

    const val TYPE_JOIN_REQ: Byte = 0x01
    const val TYPE_JOIN_ACK: Byte = 0x02
    const val TYPE_CANDIDATES: Byte = 0x03
    const val TYPE_PING: Byte = 0x04
    const val TYPE_PONG: Byte = 0x05
    const val TYPE_IPV4_PKT: Byte = 0x06
    const val TYPE_BYE: Byte = 0x07

    fun nodeIp(nodeId: Int): String = "10.10.0.${nodeId and 0xFF}"

    fun buildJoinReq(clientSessionId: Long): ByteArray {
        return ByteBuffer.allocate(9)
            .put(TYPE_JOIN_REQ)
            .putLong(clientSessionId)
            .array()
    }

    fun buildJoinAck(clientSessionId: Long, assignedNodeId: Int): ByteArray {
        return ByteBuffer.allocate(10)
            .put(TYPE_JOIN_ACK)
            .putLong(clientSessionId)
            .put(assignedNodeId.toByte())
            .array()
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
            .put(senderNodeId.toByte())
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
            .put(senderNodeId.toByte())
            .put(targetNodeId.toByte())
            .putLong(sentAtMs)
            .array()
    }

    fun buildPong(senderNodeId: Int, targetNodeId: Int, origSentAtMs: Long): ByteArray {
        return ByteBuffer.allocate(11)
            .put(TYPE_PONG)
            .put(senderNodeId.toByte())
            .put(targetNodeId.toByte())
            .putLong(origSentAtMs)
            .array()
    }

    fun buildIpv4Frame(senderNodeId: Int, dstNodeId: Int, ipv4Buf: ByteArray, length: Int): ByteArray {
        val out = ByteArray(3 + length)
        out[0] = TYPE_IPV4_PKT
        out[1] = senderNodeId.toByte()
        out[2] = dstNodeId.toByte()
        System.arraycopy(ipv4Buf, 0, out, 3, length)
        return out
    }

    fun buildBye(senderNodeId: Int): ByteArray =
        byteArrayOf(TYPE_BYE, senderNodeId.toByte())

    /**
     * Valida se o pacote lido da interface tun0 é IPv4 destinado à LAN 10.10.0.x (ou broadcast de jogo LAN)
     * e retorna o ID do nó destino (1..9 ou 255 para broadcast), ou -1 para ignorar ruído do sistema.
     */
    fun inspectOutgoingIpv4Target(pkt: ByteArray, len: Int): Int {
        if (len < 20) return -1
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version != 4) return -1

        val d0 = pkt[16].toInt() and 0xFF
        val d1 = pkt[17].toInt() and 0xFF
        val d2 = pkt[18].toInt() and 0xFF
        val d3 = pkt[19].toInt() and 0xFF

        // Broadcast de descoberta de partida LAN (10.10.0.255 ou 255.255.255.255)
        if ((d0 == 10 && d1 == 10 && d2 == 0 && d3 == 255) ||
            (d0 == 255 && d1 == 255 && d2 == 255 && d3 == 255)
        ) {
            return BROADCAST_NODE_ID
        }

        // Unicast dentro da sub-rede virtual 10.10.0.1 .. 10.10.0.9
        if (d0 == 10 && d1 == 10 && d2 == 0 && d3 in 1..9) {
            return d3
        }
        return -1
    }
}
