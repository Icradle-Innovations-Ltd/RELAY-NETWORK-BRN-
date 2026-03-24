package com.brn.client.net

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class AuthResult(
    val userId: String,
    val token: String
)

data class RelayDescriptor(
    val udpEndpoint: String,
    val tcpEndpoint: String
)

data class ClientRegistrationResult(
    val nodeId: String,
    val token: String,
    val heartbeatIntervalSec: Int
)

data class GatewayInfo(
    val id: String,
    val location: String?,
    val loadFactor: Int,
    val networkType: String?
)

data class SessionResult(
    val sessionId: String,
    val relayToken: String,
    val relay: RelayDescriptor,
    val clientTunnelIp: String,
    val gatewayTunnelIp: String,
    val networkCidr: String,
    val dnsServers: List<String>,
    val peerWireguardPublicKey: String,
    val keepaliveSec: Int,
    val mtu: Int,
    val dataCapMb: Int
)

class ControlPlaneApi(private val baseUrl: String) {

    fun signup(email: String, password: String): AuthResult {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val response = jsonRequest("POST", "$baseUrl/auth/signup", body, null)
        return AuthResult(
            userId = response.getString("userId"),
            token = response.getString("token")
        )
    }

    fun login(email: String, password: String): AuthResult {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val response = jsonRequest("POST", "$baseUrl/auth/login", body, null)
        return AuthResult(
            userId = response.getString("userId"),
            token = response.getString("token")
        )
    }

    fun registerClient(
        userToken: String,
        identityPublicKey: String,
        wireguardPublicKey: String,
        fingerprintHash: String
    ): ClientRegistrationResult {
        val body = JSONObject().apply {
            put("identityPublicKey", identityPublicKey)
            put("wireguardPublicKey", wireguardPublicKey)
            put("fingerprintHash", fingerprintHash)
        }
        val response = jsonRequest("POST", "$baseUrl/auth/register-client", body, userToken)
        return ClientRegistrationResult(
            nodeId = response.getString("nodeId"),
            token = response.getString("token"),
            heartbeatIntervalSec = response.getInt("heartbeatIntervalSec")
        )
    }

    fun listGateways(nodeToken: String): List<GatewayInfo> {
        val response = jsonRequest("GET", "$baseUrl/nodes/available", null, nodeToken)
        val nodes = response.getJSONArray("gateways")
        val result = mutableListOf<GatewayInfo>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            result += GatewayInfo(
                id = node.getString("id"),
                location = node.optString("location", null),
                loadFactor = node.optInt("loadFactor", 0),
                networkType = node.optString("networkType", null)
            )
        }
        return result
    }

    fun startSession(nodeToken: String, gatewayId: String): SessionResult {
        val body = JSONObject().apply {
            put("gatewayId", gatewayId)
            put("routingMode", "FULL")
            put("transportPreference", "AUTO")
        }
        val response = jsonRequest("POST", "$baseUrl/sessions/start", body, nodeToken)

        val relay = response.getJSONObject("relay")
        val tunnel = response.getJSONObject("tunnel")
        val peer = response.getJSONObject("peer")
        val dnsArray = tunnel.getJSONArray("dnsServers")
        val dnsServers = mutableListOf<String>()
        for (i in 0 until dnsArray.length()) {
            dnsServers += dnsArray.getString(i)
        }

        return SessionResult(
            sessionId = response.getString("sessionId"),
            relayToken = response.getString("relayToken"),
            relay = RelayDescriptor(relay.getString("udpEndpoint"), relay.getString("tcpEndpoint")),
            clientTunnelIp = tunnel.getString("clientTunnelIp"),
            gatewayTunnelIp = tunnel.getString("gatewayTunnelIp"),
            networkCidr = tunnel.getString("networkCidr"),
            dnsServers = dnsServers,
            peerWireguardPublicKey = peer.getString("wireguardPublicKey"),
            keepaliveSec = tunnel.getInt("keepaliveSec"),
            mtu = tunnel.getInt("mtu"),
            dataCapMb = response.getInt("dataCapMb")
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
            throw IllegalStateException("Request failed ($code): ${parseErrorMessage(text)}")
        }
        return JSONObject(text)
    }

    private fun parseErrorMessage(text: String): String {
        return runCatching {
            JSONObject(text).optString("error", text)
        }.getOrDefault(text)
    }
}
