package com.brn.client.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.brn.client.BuildConfig
import com.brn.client.R
import com.brn.client.crypto.ClientKeyManager
import com.brn.client.net.ControlPlaneApi
import com.brn.client.net.GatewayInfo
import com.brn.client.service.ClientVpnService
import com.brn.client.state.ClientStateStore
import com.brn.client.wifi.WifiDirectDiscovery
import com.brn.client.wifi.WifiDirectSession
import com.brn.client.wifi.WifiDirectClientListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var stateStore: ClientStateStore
    private lateinit var api: ControlPlaneApi
    private lateinit var keyManager: ClientKeyManager

    // UI elements
    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: TextView
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var sessionInfoText: TextView
    private lateinit var gatewayListContainer: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var disconnectButton: Button
    private lateinit var logoutButton: TextView

    // WiFi Direct UI
    private lateinit var wifiDirectScanButton: Button
    private lateinit var wifiDirectPeerList: LinearLayout
    private lateinit var wifiDirectStatus: TextView

    private var wifiDirectDiscovery: WifiDirectDiscovery? = null
    private var pendingWifiDirectSession: WifiDirectSession? = null

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startWifiDirectDiscovery()
        } else {
            Toast.makeText(this, "WiFi permissions needed for local connect", Toast.LENGTH_SHORT).show()
        }
    }

    private var gateways = emptyList<GatewayInfo>()
    private var selectedGatewayId: String? = null
    private var isConnected = false

    private val vpnPrepare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedGatewayId?.let { startVpn(it) }
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ClientVpnService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra("status") ?: return
                    updateStatus(status)
                }
                ClientVpnService.ACTION_SESSION_INFO -> {
                    val info = intent.getStringExtra("info") ?: return
                    sessionInfoText.text = info
                    sessionInfoText.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateStore = ClientStateStore(this)
        api = ControlPlaneApi(BuildConfig.CONTROL_PLANE_BASE_URL)
        keyManager = ClientKeyManager(this, stateStore)

        if (!stateStore.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = color(R.color.brn_status_bar)
        window.navigationBarColor = color(R.color.brn_background)
        setContentView(buildUI())

        ensureNodeRegistered()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ClientVpnService.ACTION_STATUS_UPDATE)
            addAction(ClientVpnService.ACTION_SESSION_INFO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        wifiDirectDiscovery?.unregisterReceiver()
    }

    private fun buildUI(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(color(R.color.brn_background))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        // ── Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_foreground))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        header.addView(TextView(this).apply {
            text = "BRN Client"
            setTextColor(color(R.color.brn_on_background))
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logoutButton = TextView(this).apply {
            text = "Logout"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 14f
            setOnClickListener { logout() }
        }
        header.addView(logoutButton)
        container.addView(header)
        container.addView(spacer(8))

        // User email
        container.addView(TextView(this).apply {
            text = stateStore.userEmail ?: ""
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
        })
        container.addView(spacer(24))

        // ── Status Card ──
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBackground()
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        statusIcon = TextView(this).apply {
            text = "⬇"
            textSize = 36f
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusIcon)
        statusCard.addView(spacer(8))

        statusLabel = TextView(this).apply {
            text = "Disconnected"
            setTextColor(color(R.color.brn_on_surface))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusLabel)

        statusDetail = TextView(this).apply {
            text = "Select a gateway to connect"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusDetail)
        statusCard.addView(spacer(4))

        sessionInfoText = TextView(this).apply {
            setTextColor(color(R.color.brn_accent))
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        statusCard.addView(sessionInfoText)

        disconnectButton = Button(this).apply {
            text = "Disconnect"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(color(R.color.brn_error))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            setOnClickListener { stopVpn() }
        }
        statusCard.addView(spacer(12))
        statusCard.addView(disconnectButton)

        container.addView(statusCard)
        container.addView(spacer(24))

        // ── Gateways Section ──
        container.addView(TextView(this).apply {
            text = "Available Gateways"
            setTextColor(color(R.color.brn_on_background))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        container.addView(spacer(4))
        container.addView(TextView(this).apply {
            text = "Tap a gateway to connect through it"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
        })
        container.addView(spacer(16))

        loadingText = TextView(this).apply {
            text = "Loading gateways..."
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        container.addView(loadingText)

        gatewayListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(gatewayListContainer)

        // ── Refresh button ──
        container.addView(spacer(16))
        container.addView(Button(this).apply {
            text = "Refresh"
            setTextColor(color(R.color.brn_accent))
            textSize = 14f
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { loadGateways() }
        })

        // ── WiFi Direct Section ──
        container.addView(spacer(32))
        container.addView(View(this).apply {
            setBackgroundColor(color(R.color.brn_divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        container.addView(spacer(24))

        container.addView(TextView(this).apply {
            text = "Local Connect (WiFi Direct)"
            setTextColor(color(R.color.brn_on_background))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        container.addView(spacer(4))
        container.addView(TextView(this).apply {
            text = "Connect directly to a nearby gateway — no internet needed"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
        })
        container.addView(spacer(12))

        wifiDirectStatus = TextView(this).apply {
            text = ""
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        container.addView(wifiDirectStatus)
        container.addView(spacer(8))

        wifiDirectScanButton = Button(this).apply {
            text = "Scan for Local Gateways"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(color(R.color.brn_accent))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onScanWifiDirectClicked() }
        }
        container.addView(wifiDirectScanButton)
        container.addView(spacer(12))

        wifiDirectPeerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(wifiDirectPeerList)

        root.addView(container)
        return root
    }

    private fun ensureNodeRegistered() {
        if (stateStore.nodeToken != null) {
            loadGateways()
            return
        }

        scope.launch {
            try {
                val pubKey = withContext(Dispatchers.IO) { keyManager.identityPublicKeyPem() }
                val (_, wireGuardPublicKey) = keyManager.ensureWireGuardMaterial()
                val fingerprint = keyManager.deviceFingerprint()
                stateStore.fingerprintHash = fingerprint

                val result = withContext(Dispatchers.IO) {
                    api.registerClient(
                        userToken = stateStore.userToken!!,
                        identityPublicKey = pubKey,
                        wireguardPublicKey = wireGuardPublicKey,
                        fingerprintHash = fingerprint
                    )
                }
                stateStore.nodeId = result.nodeId
                stateStore.nodeToken = result.token
                loadGateways()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadGateways() {
        val token = stateStore.nodeToken ?: return
        loadingText.visibility = View.VISIBLE
        gatewayListContainer.removeAllViews()

        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.listGateways(token) }
                gateways = list
                loadingText.visibility = View.GONE
                if (list.isEmpty()) {
                    loadingText.text = "No gateways available"
                    loadingText.visibility = View.VISIBLE
                } else {
                    list.forEach { gw -> gatewayListContainer.addView(gatewayRow(gw)) }
                }
            } catch (e: Exception) {
                loadingText.text = "Failed to load: ${e.message}"
                loadingText.visibility = View.VISIBLE
            }
        }
    }

    private fun gatewayRow(gw: GatewayInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onGatewaySelected(gw) }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = gw.location ?: gw.id.take(12)
            setTextColor(color(R.color.brn_on_surface))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        info.addView(TextView(this).apply {
            text = "ID: ${gw.id.take(16)}..."
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 12f
        })
        row.addView(info)

        // online indicator
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.brn_success))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        })

        return row
    }

    private fun onGatewaySelected(gw: GatewayInfo) {
        if (isConnected) {
            Toast.makeText(this, "Disconnect first", Toast.LENGTH_SHORT).show()
            return
        }
        selectedGatewayId = gw.id
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPrepare.launch(vpnIntent)
        } else {
            startVpn(gw.id)
        }
    }

    private fun startVpn(gatewayId: String) {
        updateStatus("connecting")
        val intent = Intent(this, ClientVpnService::class.java).apply {
            action = ClientVpnService.ACTION_START
            putExtra(ClientVpnService.EXTRA_GATEWAY_ID, gatewayId)
        }
        startForegroundService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, ClientVpnService::class.java).apply {
            action = ClientVpnService.ACTION_STOP
        }
        startService(intent)
        updateStatus("disconnected")
    }

    private fun updateStatus(status: String) {
        when (status.lowercase()) {
            "connected" -> {
                isConnected = true
                statusIcon.text = "🟢"
                statusLabel.text = "Connected"
                statusLabel.setTextColor(color(R.color.brn_success))
                statusDetail.text = "VPN tunnel active"
                disconnectButton.visibility = View.VISIBLE
            }
            "connecting" -> {
                statusIcon.text = "🔄"
                statusLabel.text = "Connecting..."
                statusLabel.setTextColor(color(R.color.brn_accent))
                statusDetail.text = "Setting up tunnel"
                disconnectButton.visibility = View.GONE
            }
            "error" -> {
                isConnected = false
                statusIcon.text = "❌"
                statusLabel.text = "Error"
                statusLabel.setTextColor(color(R.color.brn_error))
                statusDetail.text = "Connection failed"
                disconnectButton.visibility = View.GONE
            }
            else -> {
                isConnected = false
                statusIcon.text = "⬇"
                statusLabel.text = "Disconnected"
                statusLabel.setTextColor(color(R.color.brn_on_surface))
                statusDetail.text = "Select a gateway to connect"
                disconnectButton.visibility = View.GONE
                sessionInfoText.visibility = View.GONE
            }
        }
    }

    private fun logout() {
        stopVpn()
        stateStore.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ── WiFi Direct ──

    private fun onScanWifiDirectClicked() {
        // Check permissions
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (perms.isNotEmpty()) {
            wifiPermissionLauncher.launch(perms.toTypedArray())
            return
        }
        startWifiDirectDiscovery()
    }

    private fun startWifiDirectDiscovery() {
        wifiDirectPeerList.removeAllViews()
        wifiDirectStatus.text = "Scanning for nearby gateways..."
        wifiDirectStatus.visibility = View.VISIBLE
        wifiDirectScanButton.isEnabled = false
        wifiDirectScanButton.alpha = 0.5f

        val (_, wgPubKey) = keyManager.ensureWireGuardMaterial()

        wifiDirectDiscovery?.disconnect()
        wifiDirectDiscovery = WifiDirectDiscovery(this, wgPubKey, scope, object : WifiDirectClientListener {
            override fun onPeersFound(peers: List<WifiP2pDevice>) {
                runOnUiThread {
                    wifiDirectPeerList.removeAllViews()
                    if (peers.isEmpty()) {
                        wifiDirectStatus.text = "No gateways found. Try again."
                        wifiDirectScanButton.isEnabled = true
                        wifiDirectScanButton.alpha = 1f
                    } else {
                        wifiDirectStatus.text = "Found ${peers.size} device(s) — tap to connect"
                        peers.forEach { device ->
                            wifiDirectPeerList.addView(wifiDirectPeerRow(device))
                        }
                        wifiDirectScanButton.isEnabled = true
                        wifiDirectScanButton.alpha = 1f
                    }
                }
            }

            override fun onConnectedToGroup(groupOwnerIp: String) {
                runOnUiThread {
                    wifiDirectStatus.text = "Connected to group, performing handshake..."
                }
            }

            override fun onSessionReady(session: WifiDirectSession) {
                runOnUiThread {
                    wifiDirectStatus.text = "Handshake complete — starting VPN..."
                    pendingWifiDirectSession = session
                    val vpnIntent = VpnService.prepare(this@MainActivity)
                    if (vpnIntent != null) {
                        vpnPrepareWifiDirect.launch(vpnIntent)
                    } else {
                        startWifiDirectVpn(session)
                    }
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    wifiDirectStatus.text = "WiFi Direct disconnected"
                    wifiDirectScanButton.isEnabled = true
                    wifiDirectScanButton.alpha = 1f
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    wifiDirectStatus.text = "Error: $message"
                    wifiDirectScanButton.isEnabled = true
                    wifiDirectScanButton.alpha = 1f
                }
            }
        })

        wifiDirectDiscovery?.registerReceiver()
        wifiDirectDiscovery?.discoverPeers()
    }

    private val vpnPrepareWifiDirect = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingWifiDirectSession?.let { startWifiDirectVpn(it) }
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingWifiDirectSession = null
    }

    private fun startWifiDirectVpn(session: WifiDirectSession) {
        updateStatus("connecting")
        val intent = Intent(this, ClientVpnService::class.java).apply {
            action = ClientVpnService.ACTION_START_WIFI_DIRECT
            putExtra("gatewayWgPubKey", session.gatewayWireguardPublicKey)
            putExtra("gatewayTunnelIp", session.gatewayTunnelIp)
            putExtra("clientTunnelIp", session.clientTunnelIp)
            putExtra("gatewayDirectIp", session.gatewayDirectIp)
            putExtra("wireguardPort", session.wireguardPort)
            putExtra("mtu", session.mtu)
            putExtra("dnsServers", session.gatewayTunnelIp)
            putExtra("keepaliveSec", session.keepaliveSec)
        }
        startForegroundService(intent)
    }

    private fun wifiDirectPeerRow(device: WifiP2pDevice): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                wifiDirectStatus.text = "Connecting to ${device.deviceName}..."
                wifiDirectDiscovery?.connectToPeer(device)
            }

            val info = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@MainActivity).apply {
                text = device.deviceName.ifBlank { "BRN Gateway" }
                setTextColor(color(R.color.brn_on_surface))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this@MainActivity).apply {
                text = device.deviceAddress
                setTextColor(color(R.color.brn_on_surface_dim))
                textSize = 12f
            })
            addView(info)

            addView(TextView(this@MainActivity).apply {
                text = "📶"
                textSize = 20f
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiDirectDiscovery?.disconnect()
    }

    // ── UI helpers ──

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun spacer(heightDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(color(R.color.brn_surface))
        cornerRadius = dp(12).toFloat()
        setStroke(1, color(R.color.brn_divider))
    }
}
