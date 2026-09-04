package com.wyattfleming.frameos.control

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class FrameCompanionCredentials(val endpoint: FrameRemoteEndpoint, val token: String, val deviceId: String)

class FrameCompanionCredentialsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("frameos_companion", Context.MODE_PRIVATE)
    fun read(): FrameCompanionCredentials? = runCatching {
        val root = JSONObject(prefs.getString("value", null) ?: return null)
        val endpoint = FrameRemoteEndpoint.from(root.getString("endpoint")) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(root.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(endpoint.pollUrl.toByteArray(StandardCharsets.UTF_8))
        FrameCompanionCredentials(endpoint, String(cipher.doFinal(Base64.decode(root.getString("token"), Base64.NO_WRAP)), StandardCharsets.UTF_8), root.getString("device"))
    }.getOrNull()
    fun write(endpointValue: String, token: String, deviceId: String): Boolean = runCatching {
        val endpoint = FrameRemoteEndpoint.from(endpointValue) ?: return false
        require(token.isNotBlank() && deviceId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(endpoint.pollUrl.toByteArray(StandardCharsets.UTF_8))
        prefs.edit().putString("value", JSONObject().put("endpoint", endpoint.pollUrl).put("device", deviceId).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("token", Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).toString()).commit(); true
    }.getOrDefault(false)
    private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("frameos_companion_v1", null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("frameos_companion_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
}
