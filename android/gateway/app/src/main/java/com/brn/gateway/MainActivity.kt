package com.brn.gateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.brn.gateway.service.GatewayVpnService

class MainActivity : ComponentActivity() {
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wifiDirectButton: Button
    private lateinit var stopWifiDirectButton: Button
    private lateinit var wifiDirectInfoCard: LinearLayout
    private lateinit var wifiDirectSsid: TextView
    private lateinit var wifiDirectPass: TextView

    private var pendingAction: String? = null

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onWifiDirectClicked()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.brn.gateway.STATUS_UPDATE" -> {
                    val state = intent.getStringExtra("state") ?: return
                    val detail = intent.getStringExtra("detail")
                    runOnUiThread { updateStatus(state, detail) }
                }
                "com.brn.gateway.WIFI_DIRECT_INFO" -> {
                    val ssid = intent.getStringExtra("networkName") ?: ""
                    val pass = intent.getStringExtra("passphrase") ?: ""
                    runOnUiThread {
                        wifiDirectSsid.text = "Network: $ssid"
                        wifiDirectPass.text = "Password: $pass"
                        wifiDirectInfoCard.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.brn_status_bar)
        window.navigationBarColor = color(R.color.brn_background)
        setContentView(buildUI())
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.brn.gateway.STATUS_UPDATE")
            addAction("com.brn.gateway.WIFI_DIRECT_INFO")
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
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PREPARE_VPN && resultCode == RESULT_OK) {
            when (pendingAction) {
                "wifi_direct" -> startWifiDirectService()
                else -> startGatewayService()
            }
            pendingAction = null
        }
    }

    private fun buildUI(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(color(R.color.brn_background))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        // ── App icon ──
        val icon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_foreground))
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        container.addView(icon)
        container.addView(spacer(12))

        // ── Title ──
        container.addView(TextView(this).apply {
            text = "BRN Gateway"
            setTextColor(color(R.color.brn_on_background))
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        container.addView(spacer(4))

        // ── Subtitle ──
        container.addView(TextView(this).apply {
            text = "Relay Network Node"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 14f
            gravity = Gravity.CENTER
        })
        container.addView(spacer(32))

        // ── Status card ──
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBackground()
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Status row (dot + label)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.brn_on_surface_dim))
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(8)
            }
        }
        statusRow.addView(statusDot)

        statusLabel = TextView(this).apply {
            text = "Disconnected"
            setTextColor(color(R.color.brn_on_surface))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusRow.addView(statusLabel)
        statusCard.addView(statusRow)
        statusCard.addView(spacer(8))

        statusDetail = TextView(this).apply {
            text = "Tap Start to connect to the relay network"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusDetail)
        container.addView(statusCard)
        container.addView(spacer(32))

        // ── Info card ──
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        infoCard.addView(infoRow("Version", BuildConfig.VERSION_NAME))
        infoCard.addView(divider())
        infoCard.addView(infoRow("Node Type", "Gateway"))
        infoCard.addView(divider())
        infoCard.addView(infoRow("Protocol", "WireGuard + Relay"))
        container.addView(infoCard)
        container.addView(spacer(40))

        // ── Buttons ──
        startButton = Button(this).apply {
            text = "Start Gateway"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_button_start), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onStartClicked() }
        }
        container.addView(startButton)
        container.addView(spacer(12))

        stopButton = Button(this).apply {
            text = "Stop Gateway"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_button_stop), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { onStopClicked() }
        }
        container.addView(stopButton)
        container.addView(spacer(24))

        // ── WiFi Direct section ──
        container.addView(TextView(this).apply {
            text = "Local Mode (WiFi Direct)"
            setTextColor(color(R.color.brn_on_surface))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        container.addView(spacer(4))
        container.addView(TextView(this).apply {
            text = "Share internet directly — no server needed"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        container.addView(spacer(12))

        wifiDirectButton = Button(this).apply {
            text = "Start WiFi Direct"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_accent), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onWifiDirectClicked() }
        }
        container.addView(wifiDirectButton)
        container.addView(spacer(12))

        stopWifiDirectButton = Button(this).apply {
            text = "Stop WiFi Direct"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_button_stop), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { onStopWifiDirectClicked() }
        }
        container.addView(stopWifiDirectButton)
        container.addView(spacer(12))

        // WiFi Direct info card (hidden until active)
        wifiDirectInfoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        wifiDirectInfoCard.addView(TextView(this).apply {
            text = "Tell the client to connect to:"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        wifiDirectInfoCard.addView(spacer(8))
        wifiDirectSsid = TextView(this).apply {
            text = ""
            setTextColor(color(R.color.brn_on_surface))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        wifiDirectInfoCard.addView(wifiDirectSsid)
        wifiDirectInfoCard.addView(spacer(4))
        wifiDirectPass = TextView(this).apply {
            text = ""
            setTextColor(color(R.color.brn_accent))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        wifiDirectInfoCard.addView(wifiDirectPass)
        container.addView(wifiDirectInfoCard)
        container.addView(spacer(24))

        // ── Footer ──
        container.addView(TextView(this).apply {
            text = "Secured by BRN Relay Network"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 12f
            gravity = Gravity.CENTER
        })

        root.addView(container)
        return root
    }

    @Suppress("DEPRECATION")
    private fun onStartClicked() {
        pendingAction = "relay"
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQUEST_PREPARE_VPN)
        } else {
            startGatewayService()
            pendingAction = null
        }
    }

    private fun onStopClicked() {
        try {
            startService(Intent(this, GatewayVpnService::class.java).apply {
                action = GatewayVpnService.ACTION_STOP
            })
            updateStatus("stopping", "Shutting down gateway...")
        } catch (e: Exception) {
            updateStatus("error", "Failed to stop service: ${e.message}")
        }
    }

    private fun onWifiDirectClicked() {
        // Check runtime permissions for WiFi Direct
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

        pendingAction = "wifi_direct"
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQUEST_PREPARE_VPN)
        } else {
            startWifiDirectService()
            pendingAction = null
        }
    }

    private fun onStopWifiDirectClicked() {
        try {
            startService(Intent(this, GatewayVpnService::class.java).apply {
                action = GatewayVpnService.ACTION_STOP_WIFI_DIRECT
            })
            wifiDirectInfoCard.visibility = View.GONE
            wifiDirectButton.isEnabled = true
            wifiDirectButton.alpha = 1f
            stopWifiDirectButton.isEnabled = false
            stopWifiDirectButton.alpha = 0.5f
            updateStatus("disconnected", "WiFi Direct stopped")
        } catch (e: Exception) {
            updateStatus("error", "Failed to stop WiFi Direct: ${e.message}")
        }
    }

    private fun startGatewayService() {
        try {
            startService(Intent(this, GatewayVpnService::class.java).apply {
                action = GatewayVpnService.ACTION_START
            })
            updateStatus("connecting", "Registering with control plane...")
        } catch (e: Exception) {
            updateStatus("error", "Failed to start service: ${e.message}")
        }
    }

    private fun startWifiDirectService() {
        try {
            startService(Intent(this, GatewayVpnService::class.java).apply {
                action = GatewayVpnService.ACTION_START_WIFI_DIRECT
            })
            wifiDirectButton.isEnabled = false
            wifiDirectButton.alpha = 0.5f
            stopWifiDirectButton.isEnabled = true
            stopWifiDirectButton.alpha = 1f
            updateStatus("wifi_direct_starting", "Creating WiFi Direct group...")
        } catch (e: Exception) {
            updateStatus("error", "Failed to start WiFi Direct: ${e.message}")
        }
    }

    private fun updateStatus(state: String, detail: String? = null) {
        val (label, dotColor, detailText) = when (state) {
            "connected" -> Triple(
                "Connected", color(R.color.brn_success),
                detail ?: "Gateway is active and relaying traffic"
            )
            "connecting" -> Triple(
                "Connecting...", color(R.color.brn_warning),
                detail ?: "Establishing connection..."
            )
            "stopping" -> Triple(
                "Stopping...", color(R.color.brn_warning),
                detail ?: "Shutting down..."
            )
            "wifi_direct_starting" -> Triple(
                "WiFi Direct...", color(R.color.brn_accent),
                detail ?: "Creating local group..."
            )
            "wifi_direct_ready" -> Triple(
                "WiFi Direct Active", color(R.color.brn_success),
                detail ?: "Waiting for peers to connect"
            )
            "error" -> Triple(
                "Error", color(R.color.brn_error),
                detail ?: "An error occurred"
            )
            else -> Triple(
                "Disconnected", color(R.color.brn_on_surface_dim),
                detail ?: "Tap Start to connect to the relay network"
            )
        }

        statusLabel.text = label
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        statusDetail.text = detailText

        val isActive = state == "connected" || state == "connecting"
        startButton.isEnabled = !isActive
        startButton.alpha = if (isActive) 0.5f else 1f
        stopButton.isEnabled = isActive
        stopButton.alpha = if (isActive) 1f else 0.5f
    }

    // ── UI helpers ──

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun spacer(heightDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        )
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(color(R.color.brn_surface))
        cornerRadius = dp(16).toFloat()
        setStroke(1, color(R.color.brn_divider))
    }

    private fun buttonBackground(bgColor: Int, radius: Float) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radius
    }

    private fun infoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(color(R.color.brn_on_surface_dim))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextColor(color(R.color.brn_on_surface))
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(color(R.color.brn_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
    }

    companion object {
        private const val REQUEST_PREPARE_VPN = 1001
    }
}
