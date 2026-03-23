package com.brn.gateway

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.brn.gateway.service.GatewayVpnService

class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "BRN Gateway\nForeground VPN relay service"
            textSize = 18f
            setPadding(40, 60, 40, 40)
        }

        val startButton = Button(this).apply {
            text = "Start Gateway"
            setOnClickListener {
                val prepareIntent = VpnService.prepare(this@MainActivity)
                if (prepareIntent != null) {
                    startActivityForResult(prepareIntent, REQUEST_PREPARE_VPN)
                } else {
                    startGatewayService()
                }
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Gateway"
            setOnClickListener {
                startService(Intent(this@MainActivity, GatewayVpnService::class.java).apply {
                    action = GatewayVpnService.ACTION_STOP
                })
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(startButton)
            addView(stopButton)
        }

        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PREPARE_VPN && resultCode == RESULT_OK) {
            startGatewayService()
        }
    }

    private fun startGatewayService() {
        startService(Intent(this, GatewayVpnService::class.java).apply {
            action = GatewayVpnService.ACTION_START
        })
        statusView.text = "Gateway service starting..."
    }

    companion object {
        private const val REQUEST_PREPARE_VPN = 1001
    }
}
