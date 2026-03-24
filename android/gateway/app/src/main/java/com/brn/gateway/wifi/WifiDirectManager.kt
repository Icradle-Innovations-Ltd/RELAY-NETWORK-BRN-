package com.brn.gateway.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents a WiFi Direct client that has completed the handshake.
 */
data class LocalPeerInfo(
    val peerIp: String,
    val wireguardPublicKey: String,
    val tunnelIp: String
)

interface WifiDirectListener {
    fun onGroupFormed(groupOwnerIp: String, networkName: String, passphrase: String)
    fun onGroupRemoved()
    fun onPeerConnected(peer: LocalPeerInfo)
    fun onError(message: String)
}

/**
 * Manages WiFi Direct group creation and local key-exchange handshake for gateway.
 *
 * Flow:
 * 1. createGroup() → becomes WiFi Direct Group Owner (IP: 192.168.49.1)
 * 2. Starts a UDP handshake server on port HANDSHAKE_PORT
 * 3. Clients connect via WiFi Direct, send their WG public key
 * 4. Gateway responds with its WG public key + tunnel IP assignment
 * 5. Both sides spin up direct WireGuard tunnel (no relay needed)
 */
class WifiDirectManager(
    private val context: Context,
    private val gatewayWireguardPublicKey: String,
    private val scope: CoroutineScope,
    private val listener: WifiDirectListener
) {
    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, Looper.getMainLooper(), null)

    private val connectedPeers = CopyOnWriteArrayList<LocalPeerInfo>()
    private var handshakeJob: Job? = null
    private var handshakeSocket: DatagramSocket? = null
    private var nextTunnelOctet = 2 // gateway = 10.0.0.1, clients start at 10.0.0.2

    @Volatile
    var isGroupOwner = false
        private set

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestConnectionInfo()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(p2pReceiver, filter)
        }

        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WiFi Direct group creation initiated")
                // Connection info will arrive via broadcast
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
                    WifiP2pManager.BUSY -> "WiFi Direct busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown error ($reason)"
                }
                Log.e(TAG, "Group creation failed: $msg")
                listener.onError(msg)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
            info ?: return@requestConnectionInfo
            if (info.groupFormed && info.isGroupOwner) {
                isGroupOwner = true
                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                Log.i(TAG, "Group Owner at $ownerIp")

                // Get network name + passphrase
                manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                    val networkName = group?.networkName ?: "BRN-Direct"
                    val passphrase = group?.passphrase ?: ""
                    listener.onGroupFormed(ownerIp, networkName, passphrase)
                    startHandshakeServer()
                }
            }
        }
    }

    private fun startHandshakeServer() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2000
                    bind(InetSocketAddress("0.0.0.0", HANDSHAKE_PORT))
                }
                handshakeSocket = socket
                Log.i(TAG, "Handshake server listening on port $HANDSHAKE_PORT")

                val buffer = ByteArray(4096)
                while (isActive) {
                    runCatching {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        handleHandshake(socket, packet)
                    }.onFailure { error ->
                        if (error is java.net.SocketTimeoutException) return@onFailure
                        if (socket.isClosed) return@launch
                        Log.w(TAG, "Handshake receive error: ${error.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Handshake server failed: ${e.message}")
                listener.onError("Handshake server failed: ${e.message}")
            }
        }
    }

    private fun handleHandshake(socket: DatagramSocket, packet: DatagramPacket) {
        val data = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
        val request = runCatching { JSONObject(data) }.getOrNull() ?: return
        val type = request.optString("type", "")

        if (type == "WIFI_DIRECT_HELLO") {
            val clientWgPubKey = request.optString("wireguardPublicKey", "")
            if (clientWgPubKey.isBlank()) return

            val clientTunnelIp = "10.0.0.${nextTunnelOctet++}"
            val gatewayTunnelIp = "10.0.0.1"
            val peerIp = packet.address.hostAddress ?: return

            val response = JSONObject().apply {
                put("type", "WIFI_DIRECT_ACK")
                put("gatewayWireguardPublicKey", gatewayWireguardPublicKey)
                put("gatewayTunnelIp", gatewayTunnelIp)
                put("clientTunnelIp", clientTunnelIp)
                put("mtu", 1280)
                put("dnsServers", "1.1.1.1,8.8.8.8")
                put("keepaliveSec", 25)
                put("wireguardPort", WIREGUARD_PORT)
            }

            val responseBytes = response.toString().toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(responseBytes, responseBytes.size, packet.address, packet.port))

            val peer = LocalPeerInfo(
                peerIp = peerIp,
                wireguardPublicKey = clientWgPubKey,
                tunnelIp = clientTunnelIp
            )
            connectedPeers.add(peer)
            Log.i(TAG, "Peer handshake complete: $peerIp → tunnel $clientTunnelIp")
            listener.onPeerConnected(peer)
        }
    }

    fun getConnectedPeers(): List<LocalPeerInfo> = connectedPeers.toList()

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        handshakeJob?.cancel()
        handshakeSocket?.close()
        connectedPeers.clear()
        nextTunnelOctet = 2
        isGroupOwner = false

        runCatching { context.unregisterReceiver(p2pReceiver) }
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WiFi Direct group removed")
                listener.onGroupRemoved()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to remove group: $reason")
                listener.onGroupRemoved()
            }
        })
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        const val HANDSHAKE_PORT = 19000
        const val WIREGUARD_PORT = 51820
    }
}
