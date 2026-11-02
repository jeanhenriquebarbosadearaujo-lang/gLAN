package com.gdroidlan.app.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente MQTT 3.1.1 sobre TLS 1.2/1.3 (porta 8883) em malha redundante Dual-Broker.
 *
 * Correções estruturais v4.0:
 *  1. Zero I/O na thread chamadora (elimina 100% de NetworkOnMainThreadException ao clicar em
 *     Atualizar, Encerrar Servidor, Sair da Sala ou trocar #TAG na MainActivity).
 *  2. Conexão simultânea redundante aos 2 clusters TLS públicos (broker.emqx.io + broker.hivemq.com),
 *     eliminando Split-Brain entre jogadores em operadoras diferentes ou após troca Wi-Fi <-> 4G.
 *  3. Drenagem assíncrona no stop() para garantir entrega de BYE, fechamento de sala pública
 *     e limpeza de slots retidos antes de encerrar o socket TLS.
 */
class MqttLightClient(
    private val socketProtector: (Socket) -> Boolean,
    private val onPacketReceived: (topic: String, payload: ByteArray) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, brokerHost: String) -> Unit
) {
    private data class BrokerEndpoint(val host: String, val port: Int)

    private data class OutboundFrame(
        val bytes: ByteArray,
        val highPriority: Boolean
    )

    private val brokers = listOf(
        BrokerEndpoint("broker.emqx.io", 8883),
        BrokerEndpoint("broker.hivemq.com", 8883)
    )

    private val running = AtomicBoolean(false)
    private val subscribedTopics: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val packetIdGen = AtomicInteger(1)
    private val rng = SecureRandom()
    private val connectedHosts: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var workers: List<BrokerWorker> = emptyList()

    fun isConnected(): Boolean = connectedHosts.isNotEmpty()

    fun start(initialTopics: Collection<String>) {
        subscribedTopics.addAll(initialTopics)
        if (running.getAndSet(true)) {
            if (initialTopics.isNotEmpty()) {
                val subFrame = buildSubscribePacket(initialTopics.toList())
                for (w in workers) {
                    w.enqueue(subFrame, highPriority = true)
                }
            }
            return
        }
        connectedHosts.clear()
        val list = brokers.mapIndexed { idx, ep -> BrokerWorker(idx, ep) }
        workers = list
        for (w in list) {
            w.start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val activeWorkers = workers
        workers = emptyList()
        connectedHosts.clear()
        for (w in activeWorkers) {
            w.stopGracefully()
        }
    }

    /**
     * Força reconexão imediata em ambos os brokers após troca física Wi-Fi <-> 4G/5G.
     */
    fun forceReconnectNow() {
        if (!running.get()) return
        for (w in workers) {
            w.forceReconnectNow()
        }
    }

    fun subscribe(topic: String) {
        val added = subscribedTopics.add(topic)
        if (added) {
            val frame = buildSubscribePacket(listOf(topic))
            for (w in workers) {
                w.enqueue(frame, highPriority = true)
            }
        }
    }

    fun forceResubscribe(topic: String) {
        subscribedTopics.add(topic)
        val frame = buildSubscribePacket(listOf(topic))
        for (w in workers) {
            w.enqueue(frame, highPriority = true)
        }
    }

    fun unsubscribe(topic: String) {
        val removed = subscribedTopics.remove(topic)
        if (removed) {
            val frame = buildUnsubscribePacket(listOf(topic))
            for (w in workers) {
                w.enqueue(frame, highPriority = true)
            }
        }
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean = false): Boolean {
        if (!running.get()) return false
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        if (topicBytes.isEmpty() || topicBytes.size > 512 || payload.size > 65_535) return false

        val pkt = buildPublishPacket(topicBytes, payload, retain)
        var queuedAny = false
        for (w in workers) {
            if (w.enqueue(pkt, highPriority = retain)) {
                queuedAny = true
            }
        }
        return queuedAny
    }

    private fun onWorkerStateChanged(host: String, isNowConnected: Boolean) {
        if (!running.get()) return
        if (isNowConnected) {
            val added = connectedHosts.add(host)
            if (added) {
                onConnectionStateChanged(true, host)
            }
        } else {
            val removed = connectedHosts.remove(host)
            if (removed && connectedHosts.isEmpty()) {
                onConnectionStateChanged(false, host)
            }
        }
    }

    private inner class BrokerWorker(
        private val workerIndex: Int,
        private val endpoint: BrokerEndpoint
    ) {
        private val workerRunning = AtomicBoolean(false)
        private val workerConnected = AtomicBoolean(false)
        private val outboundQueue = LinkedBlockingQueue<OutboundFrame>(512)

        @Volatile private var activeSocket: Socket? = null
        @Volatile private var readerThread: Thread? = null

        fun isConnected(): Boolean = workerConnected.get()

        fun start() {
            if (workerRunning.getAndSet(true)) return
            readerThread = Thread({ runLoop() }, "glan-mqtt-rx-$workerIndex").apply {
                isDaemon = true
                start()
            }
        }

        fun enqueue(bytes: ByteArray, highPriority: Boolean): Boolean {
            if (!workerRunning.get() || !workerConnected.get()) return false
            val frame = OutboundFrame(bytes, highPriority)
            if (outboundQueue.offer(frame)) return true
            if (highPriority) {
                outboundQueue.poll()
                return outboundQueue.offer(frame)
            }
            return false
        }

        fun forceReconnectNow() {
            if (!workerRunning.get()) return
            val sock = activeSocket
            Thread({
                try { sock?.close() } catch (_: Exception) { }
            }, "glan-mqtt-cycle-$workerIndex").apply { isDaemon = true }.start()
        }

        fun stopGracefully() {
            if (!workerRunning.getAndSet(false)) return
            val sock = activeSocket
            val wasConn = workerConnected.getAndSet(false)
            if (!wasConn || sock == null) {
                outboundQueue.clear()
                try { sock?.close() } catch (_: Exception) { }
                readerThread?.interrupt()
                return
            }
            // Enfileira DISCONNECT após quaisquer pacotes BYE / close / retain=vazio já pendentes
            outboundQueue.offer(OutboundFrame(byteArrayOf(0xE0.toByte(), 0x00), highPriority = true))
            Thread({
                val deadline = System.currentTimeMillis() + 360L
                while (outboundQueue.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(15L) } catch (_: Exception) { break }
                }
                try { sock.close() } catch (_: Exception) { }
                readerThread?.interrupt()
            }, "glan-mqtt-drain-$workerIndex").apply { isDaemon = true }.start()
        }

        private fun runLoop() {
            var failStreak = 0
            while (workerRunning.get() && running.get()) {
                try {
                    connectAndLoopOnce()
                    failStreak = 0
                } catch (_: Exception) {
                    failStreak = (failStreak + 1).coerceAtMost(6)
                } finally {
                    val wasConn = workerConnected.getAndSet(false)
                    closeActiveSocketQuietly()
                    outboundQueue.clear()
                    if (wasConn) {
                        onWorkerStateChanged(endpoint.host, false)
                    }
                }
                if (workerRunning.get() && running.get()) {
                    val delayMs = when (failStreak) {
                        0, 1 -> 1_100L
                        2 -> 2_200L
                        3 -> 3_800L
                        else -> 6_500L
                    }
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) { }
                }
            }
        }

        private fun connectAndLoopOnce() {
            outboundQueue.clear()

            val rawSocket = Socket()
            socketProtector(rawSocket)
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), 7_500)
            rawSocket.soTimeout = 28_000

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(rawSocket, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.enabledProtocols = ssl.supportedProtocols.filter {
                it == "TLSv1.3" || it == "TLSv1.2"
            }.toTypedArray()

            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()

            val hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!hostnameVerifier.verify(endpoint.host, ssl.session)) {
                ssl.close()
                throw SSLHandshakeException("Certificado TLS não corresponde a ${endpoint.host}")
            }

            activeSocket = ssl
            val input = ssl.inputStream
            val out = ssl.outputStream

            val clientId = "gl40_" + ByteArray(6).also { rng.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            out.write(buildConnectPacket(clientId, keepAliveSec = 20))
            out.flush()

            val connAckType = input.read()
            if (connAckType != 0x20) throw IllegalStateException("CONNACK inválido: $connAckType")
            val remLen = readRemainingLength(input)
            val connAckBody = readExact(input, remLen)
            if (connAckBody.size < 2 || connAckBody[1].toInt() != 0) {
                throw IllegalStateException("Broker recusou MQTT CONNECT")
            }

            val initialTopics = subscribedTopics.toList()
            if (initialTopics.isNotEmpty()) {
                out.write(buildSubscribePacket(initialTopics))
                out.flush()
            }

            workerConnected.set(true)
            onWorkerStateChanged(endpoint.host, true)

            // Thread dedicada de escrita e keepalive (nunca bloqueia nem roda na UI thread)
            val writerThread = Thread({
                runWriterLoop(ssl, out)
            }, "glan-mqtt-tx-$workerIndex").apply {
                isDaemon = true
                start()
            }

            try {
                while (workerRunning.get() && running.get() && activeSocket === ssl) {
                    val header = input.read()
                    if (header < 0) throw EOFException("Broker fechou o stream")
                    val packetType = (header ushr 4) and 0x0F
                    val flags = header and 0x0F
                    val length = readRemainingLength(input)
                    if (length > 131_072) {
                        throw IllegalStateException("Pacote MQTT excedeu limite seguro: $length")
                    }
                    val body = readExact(input, length)

                    if (packetType == 3 && body.size >= 2) {
                        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                        var idx = 2 + topicLen
                        if (idx <= body.size) {
                            val topic = String(body, 2, topicLen, Charsets.UTF_8)
                            val qos = (flags ushr 1) and 0x03
                            var pktId = 0
                            if (qos > 0) {
                                if (idx + 2 > body.size) continue
                                pktId = ((body[idx].toInt() and 0xFF) shl 8) or (body[idx + 1].toInt() and 0xFF)
                                idx += 2
                            }
                            val payload = body.copyOfRange(idx, body.size)
                            if (qos == 1) {
                                val pubAck = byteArrayOf(
                                    0x40.toByte(),
                                    0x02,
                                    ((pktId ushr 8) and 0xFF).toByte(),
                                    (pktId and 0xFF).toByte()
                                )
                                enqueue(pubAck, highPriority = true)
                            }
                            try {
                                onPacketReceived(topic, payload)
                            } catch (_: Exception) { }
                        }
                    }
                }
            } finally {
                writerThread.interrupt()
            }
        }

        private fun runWriterLoop(ssl: SSLSocket, out: OutputStream) {
            var lastWriteAtMs = System.currentTimeMillis()
            while (activeSocket === ssl && !ssl.isClosed) {
                try {
                    val next = outboundQueue.poll(5_000L, TimeUnit.MILLISECONDS)
                    if (next != null) {
                        out.write(next.bytes)
                        // Drena em lote pacotes já na fila antes do flush para menor latência em jogo
                        var batched = 0
                        while (batched < 16) {
                            val extra = outboundQueue.poll() ?: break
                            out.write(extra.bytes)
                            batched++
                        }
                        out.flush()
                        lastWriteAtMs = System.currentTimeMillis()
                        if (next.bytes.size == 2 && next.bytes[0] == 0xE0.toByte()) {
                            break
                        }
                    } else if (workerRunning.get() && running.get()) {
                        val now = System.currentTimeMillis()
                        if (now - lastWriteAtMs >= 13_500L) {
                            out.write(byteArrayOf(0xC0.toByte(), 0x00))
                            out.flush()
                            lastWriteAtMs = now
                        }
                    } else if (outboundQueue.isEmpty()) {
                        break
                    }
                } catch (_: InterruptedException) {
                    if (!workerRunning.get() && outboundQueue.isEmpty()) break
                } catch (_: Exception) {
                    closeActiveSocketQuietly()
                    break
                }
            }
        }

        private fun closeActiveSocketQuietly() {
            val s = activeSocket
            activeSocket = null
            try { s?.close() } catch (_: Exception) { }
        }
    }

    private fun buildSubscribePacket(topics: List<String>): ByteArray {
        val valid = topics.filter { it.isNotBlank() }
        if (valid.isEmpty()) return ByteArray(0)
        val pid = nextPacketId()
        val body = ByteArrayOutputStream()
        body.write((pid ushr 8) and 0xFF)
        body.write(pid and 0xFF)
        for (t in valid) {
            writeUtf8(body, t)
            body.write(0) // QoS 0
        }
        return wrapFixedHeader(0x82, body.toByteArray())
    }

    private fun buildUnsubscribePacket(topics: List<String>): ByteArray {
        val valid = topics.filter { it.isNotBlank() }
        if (valid.isEmpty()) return ByteArray(0)
        val pid = nextPacketId()
        val body = ByteArrayOutputStream()
        body.write((pid ushr 8) and 0xFF)
        body.write(pid and 0xFF)
        for (t in valid) {
            writeUtf8(body, t)
        }
        return wrapFixedHeader(0xA2, body.toByteArray())
    }

    private fun buildConnectPacket(clientId: String, keepAliveSec: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeUtf8(body, "MQTT")
        body.write(4) // MQTT 3.1.1
        body.write(0x02) // Clean Session
        body.write((keepAliveSec ushr 8) and 0xFF)
        body.write(keepAliveSec and 0xFF)
        writeUtf8(body, clientId)
        return wrapFixedHeader(0x10, body.toByteArray())
    }

    private fun buildPublishPacket(topicBytes: ByteArray, payload: ByteArray, retain: Boolean): ByteArray {
        val remLen = 2 + topicBytes.size + payload.size
        val out = ByteArrayOutputStream(5 + remLen)
        val headerByte = if (retain) 0x31 else 0x30 // QoS 0 (+ RETAIN opcional)
        out.write(headerByte)
        writeRemainingLength(out, remLen)
        out.write((topicBytes.size ushr 8) and 0xFF)
        out.write(topicBytes.size and 0xFF)
        out.write(topicBytes)
        out.write(payload)
        return out.toByteArray()
    }

    private fun wrapFixedHeader(headerByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(5 + body.size)
        out.write(headerByte)
        writeRemainingLength(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeUtf8(out: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write((b.size ushr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun writeRemainingLength(out: OutputStream, length: Int) {
        var x = length
        do {
            var encoded = x % 128
            x /= 128
            if (x > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (x > 0)
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var count = 0
        while (true) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException("EOF lendo Remaining Length")
            value += (encoded and 127) * multiplier
            if ((encoded and 128) == 0) break
            multiplier *= 128
            count++
            if (count > 4) throw IllegalStateException("Remaining Length malformado")
        }
        return value
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("EOF lendo corpo MQTT")
            off += n
        }
        return buf
    }

    private fun nextPacketId(): Int {
        val id = packetIdGen.getAndUpdate { cur -> if (cur >= 65530) 1 else cur + 1 }
        return id.coerceIn(1, 65535)
    }
}
