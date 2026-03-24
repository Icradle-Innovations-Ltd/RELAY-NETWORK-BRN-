package com.brn.client.net

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

class ClientRelayTransport(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private var bridgeJob: Job? = null
    private var localPort: Int = 0

    @Volatile
    var isConnected: Boolean = false
        private set

    fun start(
        nodeId: String,
        relayToken: String,
        relayEndpoint: String,
        onLocalPortReady: (Int) -> Unit
    ) {
        stop()
        localPort = 42100 + (nodeId.hashCode() and Int.MAX_VALUE) % 1000
        onLocalPortReady(localPort)

        bridgeJob = scope.launch(Dispatchers.IO) {
            runBridgeLoop(nodeId, relayToken, relayEndpoint, localPort)
        }
    }

    fun stop() {
        bridgeJob?.cancel()
        bridgeJob = null
        isConnected = false
    }

    private suspend fun runBridgeLoop(
        nodeId: String,
        relayToken: String,
        relayEndpoint: String,
        localPort: Int
    ) {
        while (currentCoroutineContext().isActive) {
            var relaySocket: DatagramSocket? = null
            var localSocket: DatagramSocket? = null
            try {
                val (host, port) = parseEndpoint(relayEndpoint)
                relaySocket = DatagramSocket().apply { soTimeout = 10_000 }
                vpnService.protect(relaySocket)
                localSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2_000
                    bind(InetSocketAddress("127.0.0.1", localPort))
                }

                val hello = buildHello(relayToken, nodeId)
                val target = InetSocketAddress(host, port)
                relaySocket.send(DatagramPacket(hello, hello.size, target))
                isConnected = true
                Log.i("ClientRelayTransport", "bridge active on 127.0.0.1:$localPort -> $relayEndpoint")

                var localEngineAddr: InetSocketAddress? = null
                coroutineScope {
                    // local WireGuard -> relay
                    launch {
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            runCatching {
                                val packet = DatagramPacket(buffer, buffer.size)
                                localSocket.receive(packet)
                                localEngineAddr = packet.socketAddress as InetSocketAddress
                                relaySocket.send(DatagramPacket(packet.data, packet.length, target))
                            }.onFailure { error ->
                                if (localSocket.isClosed || relaySocket.isClosed) return@launch
                                if (error !is java.net.SocketTimeoutException) throw error
                            }
                        }
                    }

                    // relay -> local WireGuard
                    launch {
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            runCatching {
                                val packet = DatagramPacket(buffer, buffer.size)
                                relaySocket.receive(packet)
                                val engineAddr = localEngineAddr ?: return@runCatching
                                localSocket.send(DatagramPacket(packet.data, packet.length, engineAddr))
                            }.onFailure { error ->
                                if (localSocket.isClosed || relaySocket.isClosed) return@launch
                                if (error !is java.net.SocketTimeoutException) throw error
                            }
                        }
                    }

                    // keepalive
                    launch {
                        while (isActive) {
                            delay(20_000)
                            relaySocket.send(DatagramPacket(hello, hello.size, target))
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                isConnected = false
                Log.w("ClientRelayTransport", "bridge failed: ${error.message}")
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
            put("role", "client")
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
