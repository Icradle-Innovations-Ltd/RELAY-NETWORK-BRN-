package com.brn.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brn.client.BuildConfig
import com.brn.client.crypto.ClientKeyManager
import com.brn.client.net.ClientRelayTransport
import com.brn.client.net.ControlPlaneApi
import com.brn.client.net.SessionResult
import com.brn.client.state.ClientStateStore
import com.brn.client.tunnel.ClientWireGuardEngine
import com.brn.client.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClientVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var stateStore: ClientStateStore
    private lateinit var keyManager: ClientKeyManager
    private lateinit var api: ControlPlaneApi
    private lateinit var relayTransport: ClientRelayTransport
    private lateinit var wireGuardEngine: ClientWireGuardEngine

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                val gatewayId = intent.getStringExtra(EXTRA_GATEWAY_ID) ?: run {
                    broadcastStatus("error", "No gateway ID provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(gatewayId)
            }
            ACTION_START_WIFI_DIRECT -> {
                startVpnWifiDirect(
                    gatewayWgPubKey = intent.getStringExtra("gatewayWgPubKey") ?: "",
                    gatewayTunnelIp = intent.getStringExtra("gatewayTunnelIp") ?: "10.0.0.1",
                    clientTunnelIp = intent.getStringExtra("clientTunnelIp") ?: "10.0.0.2",
                    gatewayDirectIp = intent.getStringExtra("gatewayDirectIp") ?: "192.168.49.1",
                    wireguardPort = intent.getIntExtra("wireguardPort", 51820),
                    mtu = intent.getIntExtra("mtu", 1280),
                    dnsServers = intent.getStringExtra("dnsServers") ?: "1.1.1.1,8.8.8.8",
                    keepaliveSec = intent.getIntExtra("keepaliveSec", 25)
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::relayTransport.isInitialized) relayTransport.stop()
        if (::wireGuardEngine.isInitialized) wireGuardEngine.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn(gatewayId: String) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, foregroundNotification("Connecting..."))

        stateStore = ClientStateStore(this)
        keyManager = ClientKeyManager(this, stateStore)
        api = ControlPlaneApi(BuildConfig.CONTROL_PLANE_BASE_URL)
        relayTransport = ClientRelayTransport(this, serviceScope)
        val (wireGuardPrivateKey, wireGuardPublicKey) = keyManager.ensureWireGuardMaterial()
        wireGuardEngine = ClientWireGuardEngine(this, packageName, wireGuardPrivateKey)

        serviceScope.launch {
            try {
                broadcastStatus("connecting", "Registering client node...")

                // Ensure we have a node token
                if (stateStore.nodeToken == null || stateStore.nodeId == null) {
                    val userToken = stateStore.userToken
                        ?: throw IllegalStateException("Not logged in")
                    val registration = api.registerClient(
                        userToken = userToken,
                        identityPublicKey = keyManager.identityPublicKeyPem(),
                        wireguardPublicKey = wireGuardPublicKey,
                        fingerprintHash = keyManager.deviceFingerprint()
                    )
                    stateStore.nodeId = registration.nodeId
                    stateStore.nodeToken = registration.token
                }

                val nodeToken = stateStore.nodeToken!!
                broadcastStatus("connecting", "Starting session with gateway...")

                val session = api.startSession(nodeToken, gatewayId)
                broadcastStatus("connecting", "Establishing relay bridge...")

                relayTransport.start(
                    nodeId = stateStore.nodeId!!,
                    relayToken = session.relayToken,
                    relayEndpoint = session.relay.udpEndpoint
                ) { localPort ->
                    try {
                        wireGuardEngine.start(session, localPort)
                    } catch (e: Exception) {
                        Log.e("ClientVpnService", "WireGuard start failed: ${e.message}")
                        broadcastStatus("error", "WireGuard failed: ${e.message}")
                    }
                }

                broadcastStatus("connected", "VPN active via gateway")
                startForeground(NOTIFICATION_ID, foregroundNotification("VPN connected"))
                broadcastSession(session)
            } catch (e: Exception) {
                Log.e("ClientVpnService", "VPN start failed: ${e.message}", e)
                broadcastStatus("error", e.message ?: "Connection failed")
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        if (::wireGuardEngine.isInitialized) wireGuardEngine.stop()
        if (::relayTransport.isInitialized) relayTransport.stop()
        broadcastStatus("disconnected", "VPN disconnected")
        stopSelf()
    }

    private fun broadcastStatus(state: String, detail: String) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra("status", state)
            putExtra("detail", detail)
        })
    }

    private fun broadcastSession(session: SessionResult) {
        val info = "${session.clientTunnelIp} \u2194 ${session.gatewayTunnelIp} (${session.dataCapMb} MB cap)"
        sendBroadcast(Intent(ACTION_SESSION_INFO).apply {
            setPackage(packageName)
            putExtra("info", info)
        })
    }

    private fun startVpnWifiDirect(
        gatewayWgPubKey: String,
        gatewayTunnelIp: String,
        clientTunnelIp: String,
        gatewayDirectIp: String,
        wireguardPort: Int,
        mtu: Int,
        dnsServers: String,
        keepaliveSec: Int
    ) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, foregroundNotification("WiFi Direct connecting..."))

        stateStore = ClientStateStore(this)
        keyManager = ClientKeyManager(this, stateStore)
        val (wireGuardPrivateKey, _) = keyManager.ensureWireGuardMaterial()
        wireGuardEngine = ClientWireGuardEngine(this, packageName, wireGuardPrivateKey)

        serviceScope.launch {
            try {
                broadcastStatus("connecting", "Setting up WiFi Direct tunnel...")

                val dnsList = dnsServers.split(",").map { it.trim() }
                wireGuardEngine.startDirect(
                    wireGuardPrivateKey = wireGuardPrivateKey,
                    clientTunnelIp = clientTunnelIp,
                    gatewayWgPubKey = gatewayWgPubKey,
                    gatewayDirectIp = gatewayDirectIp,
                    wireguardPort = wireguardPort,
                    mtu = mtu,
                    dnsServers = dnsList,
                    keepaliveSec = keepaliveSec
                )

                broadcastStatus("connected", "VPN active via WiFi Direct")
                startForeground(NOTIFICATION_ID, foregroundNotification("VPN connected (WiFi Direct)"))

                val info = "$clientTunnelIp \u2194 $gatewayTunnelIp (WiFi Direct)"
                sendBroadcast(Intent(ACTION_SESSION_INFO).apply {
                    setPackage(packageName)
                    putExtra("info", info)
                })
            } catch (e: Exception) {
                Log.e("ClientVpnService", "WiFi Direct VPN failed: ${e.message}", e)
                broadcastStatus("error", e.message ?: "WiFi Direct VPN failed")
                stopSelf()
            }
        }
    }

    private fun foregroundNotification(content: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BRN Client")
            .setContentText(content)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "BRN Client VPN", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.brn.client.START"
        const val ACTION_STOP = "com.brn.client.STOP"
        const val ACTION_START_WIFI_DIRECT = "com.brn.client.START_WIFI_DIRECT"
        const val EXTRA_GATEWAY_ID = "gateway_id"
        const val ACTION_STATUS_UPDATE = "com.brn.client.STATUS_UPDATE"
        const val ACTION_SESSION_INFO = "com.brn.client.SESSION_INFO"
        private const val CHANNEL_ID = "brn_client_vpn"
        private const val NOTIFICATION_ID = 2001
    }
}
