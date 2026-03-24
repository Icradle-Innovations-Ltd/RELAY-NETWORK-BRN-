package com.brn.gateway.tunnel

import android.content.Context
import android.util.Log
import com.brn.gateway.net.AssignedSession
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class WireGuardSessionBinding(
    val sessionId: String,
    val localRelayPort: Int,
    val gatewayTunnelIp: String?,
    val clientTunnelIp: String?
)

interface UserspaceWireGuardEngine {
    fun ensureSession(session: AssignedSession, localRelayPort: Int): WireGuardSessionBinding
    fun ensureSessionDirect(session: AssignedSession, peerIp: String, peerPort: Int): WireGuardSessionBinding
    fun pruneSessions(activeSessionIds: Set<String>)
    fun shutdown()
}

class GoBackendWireGuardEngine(
    context: Context,
    private val packageName: String,
    private val wireGuardPrivateKey: String
) : UserspaceWireGuardEngine {
    private val backend = GoBackend(context.applicationContext)
    private val tunnels = ConcurrentHashMap<String, ManagedTunnel>()

    override fun ensureSession(session: AssignedSession, localRelayPort: Int): WireGuardSessionBinding {
        val config = buildConfig(session, "127.0.0.1:$localRelayPort")
        return bringUp(session, config, localRelayPort)
    }

    override fun ensureSessionDirect(session: AssignedSession, peerIp: String, peerPort: Int): WireGuardSessionBinding {
        val config = buildConfig(session, "$peerIp:$peerPort")
        return bringUp(session, config, peerPort)
    }

    private fun bringUp(session: AssignedSession, config: Config, port: Int): WireGuardSessionBinding {
        val tunnel = tunnels.computeIfAbsent(session.sessionId) { ManagedTunnel(session.sessionId) }

        try {
            backend.setState(tunnel, Tunnel.State.UP, config)
            Log.i("GoBackendWireGuardEngine", "session ${session.sessionId} active")
        } catch (error: Exception) {
            throw IllegalStateException("Unable to bring WireGuard session ${session.sessionId} up", error)
        }

        return WireGuardSessionBinding(
            sessionId = session.sessionId,
            localRelayPort = port,
            gatewayTunnelIp = session.gatewayTunnelIp,
            clientTunnelIp = session.clientTunnelIp
        )
    }

    override fun shutdown() {
        pruneSessions(emptySet())
    }

    override fun pruneSessions(activeSessionIds: Set<String>) {
        tunnels.entries
            .filter { (sessionId, _) -> sessionId !in activeSessionIds }
            .forEach { (sessionId, tunnel) ->
                runCatching {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }.onFailure { error ->
                    Log.w("GoBackendWireGuardEngine", "failed to stop ${tunnel.name}: ${error.message}")
                }
                tunnels.remove(sessionId)
            }
    }

    private fun buildConfig(session: AssignedSession, endpoint: String): Config {
        val gatewayAddress = requireNotNull(session.gatewayTunnelIp) {
            "gatewayTunnelIp missing for session ${session.sessionId}"
        }
        val clientAddress = requireNotNull(session.clientTunnelIp) {
            "clientTunnelIp missing for session ${session.sessionId}"
        }
        val clientPublicKey = requireNotNull(session.clientWireguardPublicKey) {
            "clientWireguardPublicKey missing for session ${session.sessionId}"
        }

        val configText = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $wireGuardPrivateKey")
            appendLine("Address = $gatewayAddress")
            appendLine("MTU = 1280")
            appendLine("ExcludedApplications = $packageName")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $clientPublicKey")
            appendLine("AllowedIPs = $clientAddress")
            appendLine("Endpoint = $endpoint")
            appendLine("PersistentKeepalive = 25")
        }

        return Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
    }

    private class ManagedTunnel(private val tunnelName: String) : Tunnel {
        @Volatile
        private var state: Tunnel.State = Tunnel.State.DOWN

        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            state = newState
        }
    }
}
