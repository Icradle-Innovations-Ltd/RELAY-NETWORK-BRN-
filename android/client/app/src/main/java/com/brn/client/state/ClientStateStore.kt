package com.brn.client.state

import android.content.Context

class ClientStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("brn_client", Context.MODE_PRIVATE)

    var userToken: String?
        get() = prefs.getString("user_token", null)
        set(value) = prefs.edit().putString("user_token", value).apply()

    var userEmail: String?
        get() = prefs.getString("user_email", null)
        set(value) = prefs.edit().putString("user_email", value).apply()

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    var nodeId: String?
        get() = prefs.getString("node_id", null)
        set(value) = prefs.edit().putString("node_id", value).apply()

    var nodeToken: String?
        get() = prefs.getString("node_token", null)
        set(value) = prefs.edit().putString("node_token", value).apply()

    var wireGuardPublicKey: String?
        get() = prefs.getString("wg_public_key", null)
        set(value) = prefs.edit().putString("wg_public_key", value).apply()

    var wireGuardPrivateKey: String?
        get() = prefs.getString("wg_private_key", null)
        set(value) = prefs.edit().putString("wg_private_key", value).apply()

    var fingerprintHash: String?
        get() = prefs.getString("fingerprint_hash", null)
        set(value) = prefs.edit().putString("fingerprint_hash", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    val isLoggedIn: Boolean
        get() = userToken != null
}
