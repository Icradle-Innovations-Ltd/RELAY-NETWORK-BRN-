package com.brn.gateway.net

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class RelayTransport(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private val sessionJobs = ConcurrentHashMap<String, Job>()

    fun ensureSession(nodeId: String, session: AssignedSession) {
        sessionJobs.computeIfAbsent(session.sessionId) {
            scope.launch(Dispatchers.IO) {
                runSessionLoop(nodeId, session)
            }
        }
    }

    fun reconnectAll() {
        sessionJobs.values.forEach { job ->
            job.cancel()
        }
        sessionJobs.clear()
    }

    fun stop() {
        reconnectAll()
    }

    private suspend fun runSessionLoop(nodeId: String, session: AssignedSession) {
        while (scope.isActive) {
            runCatching {
                val (host, port) = parseEndpoint(session.relay.udpEndpoint)
                val socket = DatagramSocket().apply {
                    soTimeout = 10_000
                }
                vpnService.protect(socket)

                val hello = buildHello(session.relayToken, nodeId)
                val target = InetSocketAddress(host, port)
                socket.send(DatagramPacket(hello, hello.size, target))

                while (scope.isActive && !socket.isClosed) {
                    delay(20_000)
                    socket.send(DatagramPacket(hello, hello.size, target))
                }
                socket.close()
            }.onFailure { error ->
                Log.w("RelayTransport", "session ${session.sessionId} failed: ${error.message}")
                delay(5_000)
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
}
