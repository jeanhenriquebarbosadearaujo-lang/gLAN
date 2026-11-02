package com.gdroidlan.app.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException

/**
 * Detector silencioso de servidor Source Engine / GDroid na porta UDP 27015.
 *
 * IMPORTANTE (Arquitetura Source Engine / GDroid):
 * Nunca envie pacotes connectionless (0xFFFFFFFF / A2S_INFO 'T') para 127.0.0.1:27015!
 * No Source Engine (engine/net_ws.cpp: NET_SendPacket), respostas destinadas a 127.x.x.x
 * quando net_usesocketsforloopback=0 são desviadas para s_LoopBacks[NS_CLIENT] (fila interna
 * do jogador local). Como o desafio A2S_INFO (ReplyServerChallenge) usa o opcode 'A'
 * (S2C_CHALLENGE) sem S2C_MAGICVERSION (0x5A4F4933), o cliente local durante o carregamento
 * do mapa interpreta esse pacote como versão incompatível e aborta a conexão com:
 * "#GameUI_ServerConnectOutOfDate" ("O servidor não foi atualizado para a versão mais recente.").
 *
 * Solução: sonda inerte de 1 byte (0x00), estritamente menor que NET_MIN_MESSAGE (5 bytes).
 * - Porta fechada: o kernel Linux retorna ICMP Port Unreachable imediatamente (<1 ms -> false).
 * - Porta aberta: o kernel aceita o datagrama sem ICMP (SocketTimeoutException em 35 ms -> true),
 *   e NET_ReceiveDatagram no GDroid descarta pacotes < 5 bytes na primeira instrução (CMP W0, #5),
 *   sem processar protocolo, sem gerar resposta e sem tocar em s_LoopBacks[NS_CLIENT].
 */
object SourceGameDetector {

    private val INERT_SUB_MIN_PROBE: ByteArray = byteArrayOf(0x00)

    fun probeLocalSourceServer(port: Int = TunnelProtocol.SOURCE_GAME_PORT): Boolean {
        var sock: DatagramSocket? = null
        return try {
            val s = DatagramSocket()
            sock = s
            s.soTimeout = 35
            s.connect(InetAddress.getByName("127.0.0.1"), port)
            s.send(DatagramPacket(INERT_SUB_MIN_PROBE, INERT_SUB_MIN_PROBE.size))
            val rx = ByteArray(8)
            val dp = DatagramPacket(rx, rx.size)
            s.receive(dp)
            true
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
