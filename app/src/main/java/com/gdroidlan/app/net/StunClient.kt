package com.gdroidlan.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Cliente STUN leve (RFC 5389) e detector de IP local na rede Wi-Fi para
 * habilitar UDP Hole Punching direto (menor ping possível entre os dois celulares).
 */
object StunClient {

    private const val MAGIC_COOKIE = 0x2112A442
    private val rng = SecureRandom()

    private val STUN_SERVERS = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun2.l.google.com" to 19302
    )

    /** Envia requisição STUN pelo próprio socket UDP do túnel para descobrir o IP:porta público real no NAT. */
    fun sendBindingRequests(udpSocket: DatagramSocket) {
        val txId = ByteArray(12).also { rng.nextBytes(it) }
        val req = ByteBuffer.allocate(20)
            .putShort(0x0001)
            .putShort(0x0000)
            .putInt(MAGIC_COOKIE)
            .put(txId)
            .array()

        for ((host, port) in STUN_SERVERS) {
            try {
                val addr = InetAddress.getByName(host)
                udpSocket.send(DatagramPacket(req, req.size, addr, port))
            } catch (_: Exception) { }
        }
    }

    /** Verifica se o pacote recebido é uma resposta STUN (0x0101) e extrai o IP:porta público (XOR-MAPPED-ADDRESS). */
    fun tryParseBindingResponse(buf: ByteArray, len: Int): InetSocketAddress? {
        if (len < 20) return null
        val bb = ByteBuffer.wrap(buf, 0, len)
        val msgType = bb.short.toInt() and 0xFFFF
        val msgLen = bb.short.toInt() and 0xFFFF
        val cookie = bb.int
        if (msgType != 0x0101 || cookie != MAGIC_COOKIE) return null
        bb.position(20)

        val end = minOf(len, 20 + msgLen)
        var fallbackMapped: InetSocketAddress? = null

        while (bb.position() + 4 <= end) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            if (bb.position() + attrLen > end) break
            val attrStart = bb.position()

            if ((attrType == 0x0020 || attrType == 0x0001) && attrLen >= 8) {
                bb.get() // reserved
                val family = bb.get().toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val rawPort = bb.short.toInt() and 0xFFFF
                    val rawIp = bb.int
                    val isXor = (attrType == 0x0020)
                    val port = if (isXor) rawPort xor (MAGIC_COOKIE ushr 16) else rawPort
                    val ipInt = if (isXor) rawIp xor MAGIC_COOKIE else rawIp
                    val ipBytes = byteArrayOf(
                        ((ipInt ushr 24) and 0xFF).toByte(),
                        ((ipInt ushr 16) and 0xFF).toByte(),
                        ((ipInt ushr 8) and 0xFF).toByte(),
                        (ipInt and 0xFF).toByte()
                    )
                    val addr = InetSocketAddress(InetAddress.getByAddress(ipBytes), port)
                    if (isXor) return addr else fallbackMapped = addr
                }
            }
            val padded = (attrLen + 3) and 3.inv()
            if (attrStart + padded > end) break
            bb.position(attrStart + padded)
        }
        return fallbackMapped
    }

    /** Descobre o IPv4 físico local (Wi-Fi) ignorando loopback e a interface virtual 10.10.0.x. */
    fun findLocalLanAddress(localPort: Int): InetSocketAddress? {
        if (localPort <= 0) return null
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                val name = nif.name.lowercase()
                if (!nif.isUp || nif.isLoopback || name.startsWith("tun") || name.startsWith("ppp")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (!ip.startsWith("10.10.0.") && !ip.startsWith("169.254.")) {
                            return InetSocketAddress(a, localPort)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
