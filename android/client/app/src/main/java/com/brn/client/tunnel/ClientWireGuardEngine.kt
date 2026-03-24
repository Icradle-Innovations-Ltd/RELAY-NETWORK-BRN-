package com.brn.client.tunnel

import android.content.Context
import android.util.Log
import com.brn.client.net.SessionResult
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class ClientWireGuardEngine(
    context: Context,
    private val packageName: String,
    private val wireGuardPrivateKey: String
) {
    private val backend = GoBackend(context.applicationContext)
    private var tunnel: ManagedTunnel? = null

    fun start(session: SessionResult, localRelayPort: Int) {
        val config = buildConfig(session, localRelayPort)
        val t = ManagedTunnel("brn-client")
        tunnel = t
        try {
            backend.setState(t, Tunnel.State.UP, config)
            Log.i("ClientWireGuardEngine", "tunnel UP via relay port $localRelayPort")
        } catch (error: Exception) {
            throw IllegalStateException("Unable to bring WireGuard tunnel up", error)
        }
    }

    fun stop() {
        tunnel?.let { t ->
            runCatching {
                backend.setState(t, Tunnel.State.DOWN, null)
            }.onFailure { error ->
                Log.w("ClientWireGuardEngine", "failed to stop tunnel: ${error.message}")
            }
            tunnel = null
        }
    }

    private fun buildConfig(session: SessionResult, localRelayPort: Int): Config {
        val dnsLine = if (session.dnsServers.isNotEmpty()) {
            "DNS = ${session.dnsServers.joinToString(", ")}"
        } else {
            "DNS = 1.1.1.1, 8.8.8.8"
        }

        val configText = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $wireGuardPrivateKey")
            appendLine("Address = ${session.clientTunnelIp}/32")
            appendLine("MTU = ${session.mtu}")
            appendLine(dnsLine)
            appendLine("ExcludedApplications = $packageName")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${session.peerWireguardPublicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("Endpoint = 127.0.0.1:$localRelayPort")
            appendLine("PersistentKeepalive = ${session.keepaliveSec}")
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
