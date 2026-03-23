package com.brn.gateway.state

import android.content.Context

class GatewayStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("brn_gateway", Context.MODE_PRIVATE)

    var nodeId: String?
        get() = prefs.getString("node_id", null)
        set(value) = prefs.edit().putString("node_id", value).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    var fingerprintHash: String?
        get() = prefs.getString("fingerprint_hash", null)
        set(value) = prefs.edit().putString("fingerprint_hash", value).apply()

    var wireGuardPublicKey: String?
        get() = prefs.getString("wg_public_key", null)
        set(value) = prefs.edit().putString("wg_public_key", value).apply()

    var wireGuardPrivateKey: String?
        get() = prefs.getString("wg_private_key", null)
        set(value) = prefs.edit().putString("wg_private_key", value).apply()
}
