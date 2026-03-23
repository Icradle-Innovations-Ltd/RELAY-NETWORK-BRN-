package com.brn.gateway.net

import android.util.Log
import com.brn.gateway.crypto.KeyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RelayDescriptor(
    val udpEndpoint: String,
    val tcpEndpoint: String
)

data class RegistrationResult(
    val nodeId: String,
    val token: String,
    val heartbeatIntervalSec: Int,
    val relay: RelayDescriptor
)

data class AssignedSession(
    val sessionId: String,
    val relayToken: String,
    val relay: RelayDescriptor,
    val clientTunnelIp: String?,
    val gatewayTunnelIp: String?,
    val clientWireguardPublicKey: String?,
    val gatewayWireguardPublicKey: String?,
    val transportMode: String,
    val routingMode: String
)

data class HeartbeatResult(
    val heartbeatIntervalSec: Int,
    val assignedSessions: List<AssignedSession>
)

class ControlPlaneApi(
    private val baseUrl: String,
    private val keyManager: KeyManager
) {
    fun registerGateway(location: String? = null): RegistrationResult {
        val (_, wireGuardPublic) = keyManager.ensureWireGuardMaterial()
        val timestamp = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("type", "GATEWAY")
            put("identityPublicKey", keyManager.identityPublicKeyPem())
            put("wireguardPublicKey", wireGuardPublic)
            put("fingerprintHash", keyManager.deviceFingerprint())
            put("location", location ?: "Kampala")
            put("capabilities", JSONObject().apply {
                put("platform", "android")
                put("transports", JSONArray().put("udp").put("tcp_fallback"))
                put("vpnService", true)
            })
            put("timestamp", timestamp)
            put("nonce", nonce)
        }

        val signaturePayload = buildString {
            append("BRN_REGISTER_V1|")
            append("GATEWAY|")
            append(payload.getString("identityPublicKey"))
            append("|")
            append(wireGuardPublic)
            append("|")
            append(keyManager.deviceFingerprint())
            append("|")
            append(location ?: "Kampala")
            append("|")
            append("{\"platform\":\"android\",\"transports\":[\"udp\",\"tcp_fallback\"],\"vpnService\":true}")
            append("|")
            append(timestamp)
            append("|")
            append(nonce)
        }.toByteArray(StandardCharsets.UTF_8)

        payload.put("signature", keyManager.signRegistrationPayload(signaturePayload))
        val response = jsonRequest("POST", "$baseUrl/nodes/register", payload, null)
        return RegistrationResult(
            nodeId = response.getString("nodeId"),
            token = response.getString("token"),
            heartbeatIntervalSec = response.getInt("heartbeatIntervalSec"),
            relay = response.getJSONObject("relay").let {
                RelayDescriptor(it.getString("udpEndpoint"), it.getString("tcpEndpoint"))
            }
        )
    }

    fun heartbeat(token: String, networkType: String, relayHealthy: Boolean, activeSessions: Int): HeartbeatResult {
        val payload = JSONObject().apply {
            put("status", if (relayHealthy) "ACTIVE" else "DEGRADED")
            put("activeSessions", activeSessions)
            put("relayHealthy", relayHealthy)
            put("networkType", networkType)
            put("currentPublicIp", JSONObject.NULL)
            put("loadFactor", activeSessions.coerceAtMost(100))
            put("appVersion", "0.1.0")
        }

        val response = jsonRequest("POST", "$baseUrl/nodes/heartbeat", payload, token)
        val sessions = mutableListOf<AssignedSession>()
        val assigned = response.optJSONArray("assignedSessions") ?: JSONArray()
        for (index in 0 until assigned.length()) {
            val item = assigned.getJSONObject(index)
            val relay = item.getJSONObject("relay")
            sessions += AssignedSession(
                sessionId = item.getString("sessionId"),
                relayToken = item.getString("relayToken"),
                relay = RelayDescriptor(relay.getString("udpEndpoint"), relay.getString("tcpEndpoint")),
                clientTunnelIp = item.optString("clientTunnelIp", null),
                gatewayTunnelIp = item.optString("gatewayTunnelIp", null),
                clientWireguardPublicKey = item.optString("clientWireguardPublicKey", null),
                gatewayWireguardPublicKey = item.optString("gatewayWireguardPublicKey", null),
                transportMode = item.optString("transportMode", "AUTO"),
                routingMode = item.optString("routingMode", "FULL")
            )
        }
        return HeartbeatResult(
            heartbeatIntervalSec = response.getInt("heartbeatIntervalSec"),
            assignedSessions = sessions
        )
    }

    private fun jsonRequest(method: String, url: String, body: JSONObject?, bearerToken: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

        if (body != null) {
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
        val text = BufferedReader(stream.reader(StandardCharsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) {
            Log.e("ControlPlaneApi", "request failed: $code $text")
            throw IllegalStateException("control plane request failed: $code")
        }
        return JSONObject(text)
    }
}
