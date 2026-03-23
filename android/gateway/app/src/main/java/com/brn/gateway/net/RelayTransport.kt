package com.brn.gateway.net

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class SessionBridgeStatus(
    val sessionId: String,
    val localPort: Int,
    val relayEndpoint: String
)

class RelayTransport(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val sessionPorts = ConcurrentHashMap<String, Int>()

    fun ensureSession(nodeId: String, session: AssignedSession): SessionBridgeStatus {
        val localPort = sessionPorts.computeIfAbsent(session.sessionId) { allocateLocalPort(session.sessionId) }
        sessionJobs.computeIfAbsent(session.sessionId) {
            scope.launch(Dispatchers.IO) {
                runSessionLoop(nodeId, session, localPort)
            }
        }
        return SessionBridgeStatus(session.sessionId, localPort, session.relay.udpEndpoint)
    }

    fun reconnectAll() {
        sessionJobs.values.forEach { job ->
            job.cancel()
        }
        sessionJobs.clear()
    }

    fun pruneSessions(activeSessionIds: Set<String>) {
        sessionJobs.entries
            .filter { (sessionId, _) -> sessionId !in activeSessionIds }
            .forEach { (sessionId, job) ->
                job.cancel()
                sessionJobs.remove(sessionId)
                sessionPorts.remove(sessionId)
            }
    }

    fun stop() {
        reconnectAll()
    }

    fun activeSessionCount(): Int = sessionJobs.size

    fun activeBridgeStatuses(): List<SessionBridgeStatus> = sessionPorts.entries.map { (sessionId, localPort) ->
        SessionBridgeStatus(sessionId = sessionId, localPort = localPort, relayEndpoint = "reconnect-pending")
    }

    private suspend fun runSessionLoop(nodeId: String, session: AssignedSession, localPort: Int) {
        while (currentCoroutineContext().isActive) {
            var relaySocket: DatagramSocket? = null
            var localSocket: DatagramSocket? = null
            try {
                val (host, port) = parseEndpoint(session.relay.udpEndpoint)
                relaySocket = DatagramSocket().apply {
                    soTimeout = 10_000
                }
                vpnService.protect(relaySocket)
                localSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2_000
                    bind(InetSocketAddress("127.0.0.1", localPort))
                }

                val hello = buildHello(session.relayToken, nodeId)
                val target = InetSocketAddress(host, port)
                relaySocket.send(DatagramPacket(hello, hello.size, target))
                Log.i("RelayTransport", "session ${session.sessionId} bridged on 127.0.0.1:$localPort")

                var localEngineAddr: InetSocketAddress? = null
                coroutineScope {
                    launch {
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            runCatching {
                                val packet = DatagramPacket(buffer, buffer.size)
                                localSocket.receive(packet)
                                localEngineAddr = packet.socketAddress as InetSocketAddress
                                relaySocket.send(
                                    DatagramPacket(
                                        packet.data,
                                        packet.length,
                                        target
                                    )
                                )
                            }.onFailure { error ->
                                if (localSocket.isClosed || relaySocket.isClosed) return@launch
                                if (!isTimeout(error)) throw error
                            }
                        }
                    }

                    launch {
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            runCatching {
                                val packet = DatagramPacket(buffer, buffer.size)
                                relaySocket.receive(packet)
                                val engineAddr = localEngineAddr ?: return@runCatching
                                localSocket.send(
                                    DatagramPacket(
                                        packet.data,
                                        packet.length,
                                        engineAddr
                                    )
                                )
                            }.onFailure { error ->
                                if (localSocket.isClosed || relaySocket.isClosed) return@launch
                                if (!isTimeout(error)) throw error
                            }
                        }
                    }

                    launch {
                        while (isActive) {
                            delay(20_000)
                            relaySocket.send(DatagramPacket(hello, hello.size, target))
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                Log.w("RelayTransport", "session ${session.sessionId} failed: ${error.message}")
                delay(5_000)
            } finally {
                relaySocket?.close()
                localSocket?.close()
            }
        }
    }

    private fun buildHello(token: String, nodeId: String): ByteArray {
        val payload = JSONObject().apply {
            put("token", token)
            put("role", "gateway")
            put("nodeId", nodeId)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val packet = ByteArray(5 + payload.size)
        "BRN1".toByteArray(StandardCharsets.UTF_8).copyInto(packet, 0)
        packet[4] = 0x01
        payload.copyInto(packet, 5)
        return packet
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val parts = endpoint.split(":")
        require(parts.size >= 2) { "invalid endpoint: $endpoint" }
        val port = parts.last().toInt()
        val host = parts.dropLast(1).joinToString(":")
        return host to port
    }

    private fun allocateLocalPort(sessionId: String): Int {
        val hash = sessionId.fold(0) { acc, ch -> (acc * 31) + ch.code }
        val normalized = (hash and Int.MAX_VALUE) % 5000
        return 42000 + normalized
    }

    private fun isTimeout(error: Throwable): Boolean {
        return error is java.net.SocketTimeoutException
    }
}
