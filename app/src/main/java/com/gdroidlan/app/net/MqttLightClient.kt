package com.gdroidlan.app.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente MQTT 3.1.1 binário endurecido com TLS 1.2/1.3 obrigatório,
 * verificação estrita de hostname do certificado X.509 (anti-MITM),
 * limite máximo de frame (anti-OOM) e suporte a VpnService.protect(socket).
 */
class MqttLightClient(
    private val socketProtector: (Socket) -> Boolean,
    private val onPacketReceived: (topic: String, payload: ByteArray) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, brokerLabel: String) -> Unit
) {
    private data class BrokerEndpoint(val host: String, val port: Int)

    // Exclusivamente TLS (porta 8883) — sem fallback em texto aberto
    private val brokers = listOf(
        BrokerEndpoint("broker.emqx.io", 8883),
        BrokerEndpoint("broker.hivemq.com", 8883)
    )

    companion object {
        private const val MAX_MQTT_FRAME_BYTES = 65_536
    }

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val packetIdGen = AtomicInteger(1)
    private val writeLock = Any()
    private val subscribedTopics = CopyOnWriteArraySet<String>()

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeOut: OutputStream? = null

    fun isConnected(): Boolean = connected.get()

    fun start(initialTopics: List<String>) {
        subscribedTopics.addAll(initialTopics)
        if (!running.compareAndSet(false, true)) return

        Thread({
            var brokerIdx = 0
            while (running.get()) {
                val ep = brokers[brokerIdx % brokers.size]
                try {
                    runSession(ep)
                } catch (_: Throwable) {
                } finally {
                    val wasConn = connected.getAndSet(false)
                    closeQuietly()
                    if (wasConn && running.get()) {
                        onConnectionStateChanged(false, ep.host)
                    }
                }
                if (!running.get()) break
                brokerIdx++
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "gdroid-mqtt-loop").apply { isDaemon = true }.start()
    }

    fun subscribe(topic: String) {
        val added = subscribedTopics.add(topic)
        if (added && connected.get()) {
            try {
                sendSubscribe(listOf(topic))
            } catch (_: Exception) { }
        }
    }

    fun forceResubscribe(topic: String) {
        subscribedTopics.add(topic)
        if (connected.get()) {
            try {
                sendSubscribe(listOf(topic))
            } catch (_: Exception) { }
        }
    }

    fun unsubscribe(topic: String) {
        val removed = subscribedTopics.remove(topic)
        if (removed && connected.get()) {
            try {
                sendUnsubscribe(listOf(topic))
            } catch (_: Exception) { }
        }
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean = false): Boolean {
        if (!connected.get() || payload.size > MAX_MQTT_FRAME_BYTES) return false
        return try {
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val bodyLen = 2 + topicBytes.size + payload.size
            val frame = ByteArrayOutputStream(5 + bodyLen)
            frame.write(if (retain) 0x31 else 0x30) // PUBLISH QoS 0 (+ RETAIN opcional)
            writeVarLen(frame, bodyLen)
            frame.write((topicBytes.size ushr 8) and 0xFF)
            frame.write(topicBytes.size and 0xFF)
            frame.write(topicBytes)
            frame.write(payload)
            val raw = frame.toByteArray()

            synchronized(writeLock) {
                val out = activeOut ?: return false
                out.write(raw)
                out.flush()
            }
            true
        } catch (_: Exception) {
            closeQuietly()
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            synchronized(writeLock) {
                activeOut?.write(byteArrayOf(0xE0.toByte(), 0x00))
                activeOut?.flush()
            }
        } catch (_: Exception) { }
        closeQuietly()
        connected.set(false)
    }

    private fun runSession(ep: BrokerEndpoint) {
        val rawSocket = Socket()
        socketProtector(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        rawSocket.connect(InetSocketAddress(ep.host, ep.port), 9_000)
        rawSocket.soTimeout = 45_000

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = sslFactory.createSocket(rawSocket, ep.host, ep.port, true) as SSLSocket
        ssl.useClientMode = true

        // Endurece protocolos permitidos (apenas TLSv1.2 / TLSv1.3) e identificação HTTPS do host
        val supported = ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (supported.isNotEmpty()) {
            ssl.enabledProtocols = supported.toTypedArray()
        }
        val sslParams = ssl.sslParameters
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = sslParams

        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(ep.host, ssl.session)) {
            ssl.close()
            throw SSLHandshakeException("Certificado TLS não corresponde ao host ${ep.host}")
        }

        activeSocket = ssl
        val input = BufferedInputStream(ssl.getInputStream(), 32 * 1024)
        val output = BufferedOutputStream(ssl.getOutputStream(), 32 * 1024)
        activeOut = output

        val clientId = ("gdl_" + UUID.randomUUID().toString().replace("-", "").take(14)).toByteArray(Charsets.UTF_8)
        val varHeader = byteArrayOf(0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 0x04, 0x02, 0x00, 0x28)
        val payloadLen = 2 + clientId.size
        val connectPacket = ByteArrayOutputStream()
        connectPacket.write(0x10)
        writeVarLen(connectPacket, varHeader.size + payloadLen)
        connectPacket.write(varHeader)
        connectPacket.write((clientId.size ushr 8) and 0xFF)
        connectPacket.write(clientId.size and 0xFF)
        connectPacket.write(clientId)

        synchronized(writeLock) {
            output.write(connectPacket.toByteArray())
            output.flush()
        }

        val firstByte = input.read()
        if (firstByte != 0x20) throw IllegalStateException("CONNACK inválido: $firstByte")
        val remLen = readVarLen(input)
        val connAck = readExact(input, remLen)
        if (connAck.size < 2 || connAck[1].toInt() != 0) {
            throw IllegalStateException("Broker recusou conexão: ${connAck.getOrNull(1)}")
        }

        connected.set(true)
        if (subscribedTopics.isNotEmpty()) {
            sendSubscribe(subscribedTopics.toList())
        }
        onConnectionStateChanged(true, "${ep.host} (TLS)")

        val keepaliveThread = Thread({
            while (running.get() && connected.get() && activeSocket === ssl) {
                try {
                    Thread.sleep(18_000L)
                    if (!running.get() || !connected.get()) break
                    var shouldStop = false
                    synchronized(writeLock) {
                        val out = activeOut
                        if (out == null) {
                            shouldStop = true
                        } else {
                            out.write(byteArrayOf(0xC0.toByte(), 0x00))
                            out.flush()
                        }
                    }
                    if (shouldStop) break
                } catch (_: Exception) {
                    break
                }
            }
        }, "gdroid-mqtt-ping").apply { isDaemon = true }
        keepaliveThread.start()

        while (running.get()) {
            val header = input.read()
            if (header < 0) throw EOFException("Conexão encerrada")
            val packetType = (header ushr 4) and 0x0F
            val flags = header and 0x0F
            val length = readVarLen(input)
            if (length < 0 || length > MAX_MQTT_FRAME_BYTES) {
                throw IllegalStateException("Frame MQTT excedeu limite seguro: $length")
            }
            val body = if (length > 0) readExact(input, length) else ByteArray(0)

            if (packetType == 3 && body.size >= 2) {
                val tLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                var pos = 2 + tLen
                if (tLen in 1..256 && pos <= body.size) {
                    val topic = String(body, 2, tLen, Charsets.UTF_8)
                    val qos = (flags ushr 1) and 0x03
                    if (qos > 0 && pos + 2 <= body.size) {
                        pos += 2
                    }
                    val data = body.copyOfRange(pos, body.size)
                    try {
                        onPacketReceived(topic, data)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun sendSubscribe(topics: List<String>) {
        if (topics.isEmpty()) return
        val pid = packetIdGen.getAndUpdate { if (it >= 65000) 1 else it + 1 }
        val body = ByteArrayOutputStream()
        body.write((pid ushr 8) and 0xFF)
        body.write(pid and 0xFF)
        for (t in topics) {
            val tb = t.toByteArray(Charsets.UTF_8)
            body.write((tb.size ushr 8) and 0xFF)
            body.write(tb.size and 0xFF)
            body.write(tb)
            body.write(0x00)
        }
        val bodyBytes = body.toByteArray()
        val pkt = ByteArrayOutputStream(5 + bodyBytes.size)
        pkt.write(0x82)
        writeVarLen(pkt, bodyBytes.size)
        pkt.write(bodyBytes)
        val raw = pkt.toByteArray()

        synchronized(writeLock) {
            val out = activeOut ?: return
            out.write(raw)
            out.flush()
        }
    }

    private fun sendUnsubscribe(topics: List<String>) {
        if (topics.isEmpty()) return
        val pid = packetIdGen.getAndUpdate { if (it >= 65000) 1 else it + 1 }
        val body = ByteArrayOutputStream()
        body.write((pid ushr 8) and 0xFF)
        body.write(pid and 0xFF)
        for (t in topics) {
            val tb = t.toByteArray(Charsets.UTF_8)
            body.write((tb.size ushr 8) and 0xFF)
            body.write(tb.size and 0xFF)
            body.write(tb)
        }
        val bodyBytes = body.toByteArray()
        val pkt = ByteArrayOutputStream(5 + bodyBytes.size)
        pkt.write(0xA2)
        writeVarLen(pkt, bodyBytes.size)
        pkt.write(bodyBytes)
        val raw = pkt.toByteArray()

        synchronized(writeLock) {
            val out = activeOut ?: return
            out.write(raw)
            out.flush()
        }
    }

    private fun writeVarLen(out: ByteArrayOutputStream, value: Int) {
        var x = value
        do {
            var encoded = x % 128
            x /= 128
            if (x > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (x > 0)
    }

    private fun readVarLen(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("EOF lendo varlen")
            value += (b and 0x7F) * multiplier
            if ((b and 0x80) == 0) return value
            multiplier *= 128
            count++
            if (count > 4) throw IllegalStateException("Varlen MQTT malformado")
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var readTotal = 0
        while (readTotal < n) {
            val r = input.read(out, readTotal, n - readTotal)
            if (r < 0) throw EOFException("EOF lendo corpo ($readTotal/$n)")
            readTotal += r
        }
        return out
    }

    private fun closeQuietly() {
        try {
            activeSocket?.close()
        } catch (_: Exception) { }
        activeSocket = null
        activeOut = null
    }
}
