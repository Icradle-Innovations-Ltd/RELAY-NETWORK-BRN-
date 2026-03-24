package com.brn.client.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
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

/**
 * Result of a successful WiFi Direct handshake with the gateway.
 */
data class WifiDirectSession(
    val gatewayWireguardPublicKey: String,
    val gatewayTunnelIp: String,
    val clientTunnelIp: String,
    val gatewayDirectIp: String,   // WiFi Direct IP (e.g. 192.168.49.1)
    val wireguardPort: Int,
    val mtu: Int,
    val dnsServers: List<String>,
    val keepaliveSec: Int
)

interface WifiDirectClientListener {
    fun onPeersFound(peers: List<WifiP2pDevice>)
    fun onConnectedToGroup(groupOwnerIp: String)
    fun onSessionReady(session: WifiDirectSession)
    fun onDisconnected()
    fun onError(message: String)
}

/**
 * Manages WiFi Direct peer discovery and handshake for the client.
 *
 * Flow:
 * 1. discoverPeers() → finds nearby WiFi Direct groups
 * 2. connectToPeer(device) → joins a gateway's WiFi Direct group
 * 3. On connection, performs UDP handshake to exchange WG keys
 * 4. Returns WifiDirectSession with all params needed for direct WireGuard
 */
class WifiDirectDiscovery(
    private val context: Context,
    private val clientWireguardPublicKey: String,
    private val scope: CoroutineScope,
    private val listener: WifiDirectClientListener
) {
    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, Looper.getMainLooper(), null)

    @Volatile
    var isConnected = false
        private set

    private var handshakeJob: Job? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                        val devices = peers?.deviceList?.toList() ?: emptyList()
                        Log.i(TAG, "Found ${devices.size} WiFi Direct peers")
                        listener.onPeersFound(devices)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        info ?: return@requestConnectionInfo
                        if (info.groupFormed) {
                            val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            isConnected = true
                            Log.i(TAG, "Connected to group, GO at $goIp")
                            listener.onConnectedToGroup(goIp)
                            performHandshake(goIp)
                        } else if (isConnected) {
                            isConnected = false
                            listener.onDisconnected()
                        }
                    }
                }
            }
        }
    }

    fun registerReceiver() {
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
    }

    fun unregisterReceiver() {
        runCatching { context.unregisterReceiver(p2pReceiver) }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started")
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
                    WifiP2pManager.BUSY -> "WiFi Direct busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown error ($reason)"
                }
                Log.e(TAG, "Discovery failed: $msg")
                listener.onError("Discovery failed: $msg")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connection initiated to ${device.deviceName}")
            }

            override fun onFailure(reason: Int) {
                listener.onError("Failed to connect: error $reason")
            }
        })
    }

    private fun performHandshake(groupOwnerIp: String) {
        handshakeJob?.cancel()
        handshakeJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket().apply { soTimeout = 5000 }
                val request = JSONObject().apply {
                    put("type", "WIFI_DIRECT_HELLO")
                    put("wireguardPublicKey", clientWireguardPublicKey)
                }
                val requestBytes = request.toString().toByteArray(StandardCharsets.UTF_8)
                val target = InetSocketAddress(groupOwnerIp, HANDSHAKE_PORT)

                // Retry handshake up to 3 times
                var response: JSONObject? = null
                for (attempt in 1..3) {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, target))
                    val buffer = ByteArray(4096)
                    val recvPacket = DatagramPacket(buffer, buffer.size)
                    runCatching {
                        socket.receive(recvPacket)
                        val data = String(recvPacket.data, 0, recvPacket.length, StandardCharsets.UTF_8)
                        response = JSONObject(data)
                    }.onFailure {
                        if (attempt < 3) {
                            Log.w(TAG, "Handshake attempt $attempt failed, retrying...")
                            delay(1000)
                        }
                    }
                    if (response != null) break
                }
                socket.close()

                val resp = response ?: run {
                    listener.onError("Handshake failed after 3 attempts")
                    return@launch
                }

                if (resp.optString("type") != "WIFI_DIRECT_ACK") {
                    listener.onError("Unexpected handshake response")
                    return@launch
                }

                val dnsString = resp.optString("dnsServers", "1.1.1.1,8.8.8.8")
                val session = WifiDirectSession(
                    gatewayWireguardPublicKey = resp.getString("gatewayWireguardPublicKey"),
                    gatewayTunnelIp = resp.getString("gatewayTunnelIp"),
                    clientTunnelIp = resp.getString("clientTunnelIp"),
                    gatewayDirectIp = groupOwnerIp,
                    wireguardPort = resp.getInt("wireguardPort"),
                    mtu = resp.optInt("mtu", 1280),
                    dnsServers = dnsString.split(",").map { it.trim() },
                    keepaliveSec = resp.optInt("keepaliveSec", 25)
                )

                Log.i(TAG, "Handshake complete: tunnel ${session.clientTunnelIp} → ${session.gatewayTunnelIp}")
                withContext(Dispatchers.Main) {
                    listener.onSessionReady(session)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Handshake error: ${e.message}")
                withContext(Dispatchers.Main) {
                    listener.onError("Handshake failed: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        handshakeJob?.cancel()
        isConnected = false
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Disconnected from WiFi Direct group")
                listener.onDisconnected()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Disconnect failed: $reason")
                listener.onDisconnected()
            }
        })
    }

    companion object {
        private const val TAG = "WifiDirectDiscovery"
        private const val HANDSHAKE_PORT = 19000
    }
}
