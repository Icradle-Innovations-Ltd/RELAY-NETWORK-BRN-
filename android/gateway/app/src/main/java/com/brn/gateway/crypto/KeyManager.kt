package com.brn.gateway.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.brn.gateway.state.GatewayStateStore
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.NamedParameterSpec

class KeyManager(
    private val context: Context,
    private val stateStore: GatewayStateStore
) {
    fun identityPublicKeyPem(): String {
        val keyStore = ensureIdentityKey()
        val certificate = keyStore.getCertificate(IDENTITY_ALIAS)
        return pemEncode("PUBLIC KEY", certificate.publicKey.encoded)
    }

    fun signRegistrationPayload(payload: ByteArray): String {
        val keyStore = ensureIdentityKey()
        val privateKey = keyStore.getKey(IDENTITY_ALIAS, null)
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(payload)
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun ensureWireGuardMaterial(): Pair<String, String> {
        val existingPublic = stateStore.wireGuardPublicKey
        val existingPrivate = stateStore.wireGuardPrivateKey
        if (existingPublic != null && existingPrivate != null) {
            return existingPrivate to existingPublic
        }

        val keyPairGenerator = try {
            KeyPairGenerator.getInstance("X25519")
        } catch (_: Exception) {
            KeyPairGenerator.getInstance("XDH")
        }
        keyPairGenerator.initialize(NamedParameterSpec("X25519"))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateEncoded = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val publicEncoded = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        stateStore.wireGuardPrivateKey = privateEncoded
        stateStore.wireGuardPublicKey = publicEncoded
        return privateEncoded to publicEncoded
    }

    fun deviceFingerprint(): String {
        stateStore.fingerprintHash?.let { return it }

        val seed = buildString {
            append(android.os.Build.MANUFACTURER)
            append("|")
            append(android.os.Build.MODEL)
            append("|")
            append(android.os.Build.VERSION.SDK_INT)
            append("|")
            append(context.packageName)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }
        stateStore.fingerprintHash = hash
        return hash
    }

    private fun ensureIdentityKey(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(IDENTITY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                IDENTITY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setDigests(KeyProperties.DIGEST_NONE).build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return keyStore
    }

    private fun pemEncode(type: String, bytes: ByteArray): String {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN ")
            append(type)
            append("-----\n")
            encoded.chunked(64).forEach { chunk ->
                append(chunk)
                append('\n')
            }
            append("-----END ")
            append(type)
            append("-----")
        }
    }

    companion object {
        private const val IDENTITY_ALIAS = "brn-identity-ed25519"
    }
}
