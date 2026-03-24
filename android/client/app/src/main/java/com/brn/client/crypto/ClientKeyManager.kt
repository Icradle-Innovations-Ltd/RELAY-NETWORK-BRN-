package com.brn.client.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.brn.client.state.ClientStateStore
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

class ClientKeyManager(
    private val context: Context,
    private val stateStore: ClientStateStore
) {
    fun identityPublicKeyPem(): String {
        val keyStore = ensureIdentityKey()
        val certificate = keyStore.getCertificate(IDENTITY_ALIAS)
        return pemEncode("PUBLIC KEY", certificate.publicKey.encoded)
    }

    fun ensureWireGuardMaterial(): Pair<String, String> {
        val existingPublic = stateStore.wireGuardPublicKey
        val existingPrivate = stateStore.wireGuardPrivateKey
        if (existingPublic != null && existingPrivate != null && isValidWireGuardKey(existingPrivate, existingPublic)) {
            return existingPrivate to existingPublic
        }

        val keyPair = KeyPair()
        val privateEncoded = keyPair.privateKey.toBase64()
        val publicEncoded = keyPair.publicKey.toBase64()
        stateStore.wireGuardPrivateKey = privateEncoded
        stateStore.wireGuardPublicKey = publicEncoded
        return privateEncoded to publicEncoded
    }

    private fun isValidWireGuardKey(privateKey: String, publicKey: String): Boolean {
        return runCatching {
            Key.fromBase64(privateKey)
            Key.fromBase64(publicKey)
        }.isSuccess
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
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                IDENTITY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
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
        private const val IDENTITY_ALIAS = "brn-client-identity-ec-p256"
    }
}
