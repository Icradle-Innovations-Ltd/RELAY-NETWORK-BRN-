package com.brn.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.brn.gateway.BuildConfig
import com.brn.gateway.MainActivity
import com.brn.gateway.crypto.KeyManager
import com.brn.gateway.net.ControlPlaneApi
import com.brn.gateway.net.NetworkMonitor
import com.brn.gateway.net.RelayTransport
import com.brn.gateway.state.GatewayStateStore
import com.brn.gateway.tunnel.GoBackendWireGuardEngine
import com.brn.gateway.tunnel.UserspaceWireGuardEngine
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP && heartbeatJob?.isActive == true) {
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startGateway()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
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
            ensureRegistration()
            while (isActive) {
                runCatching {
                    val token = stateStore.token ?: return@runCatching
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
                    startForeground(
                        NOTIFICATION_ID,
                        foregroundNotification("Gateway active with ${relayTransport.activeSessionCount()} session(s)")
                    )
                    val waitMs = (heartbeat.heartbeatIntervalSec.coerceAtLeast(15) * 1000).toLong()
                    delay(waitMs)
                }.onFailure { error ->
                    ensureActive()
                    android.util.Log.w("GatewayVpnService", "heartbeat loop error: ${error.message}")
                    delay(5_000)
                }
            }
        }
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

    companion object {
        const val ACTION_START = "com.brn.gateway.START"
        const val ACTION_STOP = "com.brn.gateway.STOP"
        private const val CHANNEL_ID = "brn_gateway"
        private const val NOTIFICATION_ID = 1002
    }
}
