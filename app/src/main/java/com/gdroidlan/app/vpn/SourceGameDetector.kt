package com.gdroidlan.app.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException

/**
 * Detector local de servidor Source Engine / GDroid na porta UDP 27015.
 * Compatível com Android 10+ (onde /proc/net/udp é restrito pelo SELinux):
 * envia sonda A2S_INFO em socket UDP conectado no loopback (127.0.0.1:27015).
 * - Se não houver processo ouvindo na 27015, o kernel Linux retorna ICMP Port Unreachable
 *   imediatamente (PortUnreachableException -> false).
 * - Se o GDroid estiver com mapa aberto na 27015 (respondendo A2S_INFO ou absorvendo o pacote),
 *   retorna true.
 */
object SourceGameDetector {

    private val A2S_INFO_QUERY: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0x54.toByte(), // 'T'
        'S'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(),
        ' '.code.toByte(),
        'E'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
        ' '.code.toByte(),
        'Q'.code.toByte(), 'u'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 'y'.code.toByte(),
        0x00
    )

    fun probeLocalSourceServer(port: Int = TunnelProtocol.SOURCE_GAME_PORT): Boolean {
        var sock: DatagramSocket? = null
        return try {
            val s = DatagramSocket()
            sock = s
            s.soTimeout = 150
            s.connect(InetAddress.getByName("127.0.0.1"), port)
            s.send(DatagramPacket(A2S_INFO_QUERY, A2S_INFO_QUERY.size))
            val rx = ByteArray(128)
            val dp = DatagramPacket(rx, rx.size)
            s.receive(dp)
            dp.length > 0
        } catch (_: PortUnreachableException) {
            false
        } catch (_: SocketTimeoutException) {
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) { }
        }
    }
}
