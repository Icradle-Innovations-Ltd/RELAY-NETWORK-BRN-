package com.brn.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brn.gateway.BuildConfig
import com.brn.gateway.MainActivity
import com.brn.gateway.crypto.KeyManager
import com.brn.gateway.net.AssignedSession
import com.brn.gateway.net.ControlPlaneApi
import com.brn.gateway.net.NetworkMonitor
import com.brn.gateway.net.RelayDescriptor
import com.brn.gateway.net.RelayTransport
import com.brn.gateway.state.GatewayStateStore
import com.brn.gateway.tunnel.GoBackendWireGuardEngine
import com.brn.gateway.tunnel.UserspaceWireGuardEngine
import com.brn.gateway.wifi.LocalPeerInfo
import com.brn.gateway.wifi.WifiDirectListener
import com.brn.gateway.wifi.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GatewayVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var stateStore: GatewayStateStore
    private lateinit var keyManager: KeyManager
    private lateinit var api: ControlPlaneApi
    private lateinit var relayTransport: RelayTransport
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var wireGuardEngine: UserspaceWireGuardEngine
    private var heartbeatJob: Job? = null
    private var wifiDirectManager: WifiDirectManager? = null
    private var localWireGuardEngine: GoBackendWireGuardEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START_WIFI_DIRECT -> startWifiDirect()
            ACTION_STOP_WIFI_DIRECT -> stopWifiDirect()
            else -> {
                if (heartbeatJob?.isActive == true) return START_STICKY
                startGateway()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        broadcastStatus("disconnected", "Gateway stopped")
        heartbeatJob?.cancel()
        stopWifiDirect()
        if (::relayTransport.isInitialized) {
            relayTransport.stop()
        }
        if (::networkMonitor.isInitialized) {
            networkMonitor.stop()
        }
        if (::wireGuardEngine.isInitialized) {
            wireGuardEngine.shutdown()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startGateway() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, foregroundNotification("Connecting to relay"))

        stateStore = GatewayStateStore(this)
        keyManager = KeyManager(this, stateStore)
        api = ControlPlaneApi(BuildConfig.CONTROL_PLANE_BASE_URL, keyManager)
        relayTransport = RelayTransport(this, serviceScope)
        val (wireGuardPrivateKey, _) = keyManager.ensureWireGuardMaterial()
        wireGuardEngine = GoBackendWireGuardEngine(
            context = this,
            packageName = packageName,
            wireGuardPrivateKey = wireGuardPrivateKey
        )
        networkMonitor = NetworkMonitor(this) {
            relayTransport.reconnectAll()
        }

        networkMonitor.start()
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            try {
                ensureRegistration()
                broadcastStatus("connected", "Gateway registered and active")
            } catch (e: Exception) {
                android.util.Log.w("GatewayVpnService", "registration failed: ${e.message}")
                broadcastStatus("error", "Registration failed: ${e.message}")
                delay(5_000)
            }
            while (isActive) {
                runCatching {
                    val token = stateStore.token ?: run {
                        ensureRegistration()
                        return@runCatching
                    }
                    val nodeId = stateStore.nodeId ?: return@runCatching
                    val heartbeat = api.heartbeat(
                        token = token,
                        networkType = networkMonitor.currentNetworkType(),
                        relayHealthy = true,
                        activeSessions = relayTransport.activeSessionCount()
                    )
                    val activeSessionIds = heartbeat.assignedSessions.mapTo(linkedSetOf()) { it.sessionId }
                    heartbeat.assignedSessions.forEach { session ->
                        val bridge = relayTransport.ensureSession(nodeId, session)
                        wireGuardEngine.ensureSession(session, bridge.localPort)
                    }
                    relayTransport.pruneSessions(activeSessionIds)
                    wireGuardEngine.pruneSessions(activeSessionIds)
                    broadcastStatus("connected", "Active with ${relayTransport.activeSessionCount()} session(s)")
                    startForeground(
                        NOTIFICATION_ID,
                        foregroundNotification("Gateway active with ${relayTransport.activeSessionCount()} session(s)")
                    )
                    val waitMs = (heartbeat.heartbeatIntervalSec.coerceAtLeast(15) * 1000).toLong()
                    delay(waitMs)
                }.onFailure { error ->
                    ensureActive()
                    android.util.Log.w("GatewayVpnService", "heartbeat loop error: ${error.message}")
                    broadcastStatus("error", "Heartbeat failed: ${error.message}")
                    delay(5_000)
                }
            }
        }
    }

    private fun broadcastStatus(state: String, detail: String) {
        sendBroadcast(Intent("com.brn.gateway.STATUS_UPDATE").apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("detail", detail)
        })
    }

    private suspend fun ensureRegistration() {
        if (stateStore.token != null && stateStore.nodeId != null) {
            return
        }
        val registration = api.registerGateway()
        stateStore.nodeId = registration.nodeId
        stateStore.token = registration.token
        startForeground(NOTIFICATION_ID, foregroundNotification("Gateway active"))
    }

    private fun foregroundNotification(content: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("BRN Gateway")
            .setContentText(content)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "BRN Gateway", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    // ── WiFi Direct Mode ──

    private fun startWifiDirect() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, foregroundNotification("Starting WiFi Direct..."))

        stateStore = GatewayStateStore(this)
        keyManager = KeyManager(this, stateStore)
        val (wgPrivateKey, wgPublicKey) = keyManager.ensureWireGuardMaterial()

        localWireGuardEngine = GoBackendWireGuardEngine(
            context = this,
            packageName = packageName,
            wireGuardPrivateKey = wgPrivateKey
        )

        wifiDirectManager = WifiDirectManager(
            context = this,
            gatewayWireguardPublicKey = wgPublicKey,
            scope = serviceScope,
            listener = object : WifiDirectListener {
                override fun onGroupFormed(groupOwnerIp: String, networkName: String, passphrase: String) {
                    broadcastStatus("wifi_direct_ready", "SSID: $networkName | Pass: $passphrase")
                    broadcastWifiDirectInfo(networkName, passphrase, groupOwnerIp)
                    startForeground(NOTIFICATION_ID, foregroundNotification("WiFi Direct: $networkName"))
                }

                override fun onGroupRemoved() {
                    broadcastStatus("disconnected", "WiFi Direct stopped")
                }

                override fun onPeerConnected(peer: LocalPeerInfo) {
                    Log.i("GatewayVpnService", "Local peer connected: ${peer.peerIp}")
                    val session = AssignedSession(
                        sessionId = "local-${peer.peerIp}",
                        relayToken = "",
                        relay = RelayDescriptor("", ""),
                        clientTunnelIp = peer.tunnelIp,
                        gatewayTunnelIp = "10.0.0.1",
                        clientWireguardPublicKey = peer.wireguardPublicKey,
                        gatewayWireguardPublicKey = null,
                        transportMode = "WIFI_DIRECT",
                        routingMode = "FULL"
                    )
                    // Direct WireGuard — endpoint is the peer's WiFi Direct IP
                    localWireGuardEngine?.ensureSessionDirect(
                        session, peer.peerIp, WifiDirectManager.WIREGUARD_PORT
                    )
                    val peerCount = wifiDirectManager?.getConnectedPeers()?.size ?: 0
                    broadcastStatus("wifi_direct_ready", "WiFi Direct active ($peerCount peer(s))")
                    startForeground(NOTIFICATION_ID, foregroundNotification("WiFi Direct: $peerCount peer(s)"))
                }

                override fun onError(message: String) {
                    broadcastStatus("error", "WiFi Direct: $message")
                }
            }
        )

        wifiDirectManager?.createGroup()
        broadcastStatus("connecting", "Creating WiFi Direct group...")
    }

    private fun broadcastWifiDirectInfo(networkName: String, passphrase: String, ownerIp: String) {
        sendBroadcast(Intent("com.brn.gateway.WIFI_DIRECT_INFO").apply {
            setPackage(packageName)
            putExtra("networkName", networkName)
            putExtra("passphrase", passphrase)
            putExtra("ownerIp", ownerIp)
        })
    }

    private fun stopWifiDirect() {
        wifiDirectManager?.removeGroup()
        wifiDirectManager = null
        localWireGuardEngine?.shutdown()
        localWireGuardEngine = null
    }

    companion object {
        const val ACTION_START = "com.brn.gateway.START"
        const val ACTION_STOP = "com.brn.gateway.STOP"
        const val ACTION_START_WIFI_DIRECT = "com.brn.gateway.START_WIFI_DIRECT"
        const val ACTION_STOP_WIFI_DIRECT = "com.brn.gateway.STOP_WIFI_DIRECT"
        private const val CHANNEL_ID = "brn_gateway"
        private const val NOTIFICATION_ID = 1002
    }
}
