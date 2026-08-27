package com.wyattfleming.frameos.auth

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreOAuthSessionStore(
    context: Context,
    originBinding: String,
    private val codec: OAuthSessionCodec = OAuthSessionCodec(),
) : OAuthSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val authenticatedData = "$AUTHENTICATED_DATA_PREFIX:$originBinding".toByteArray(StandardCharsets.UTF_8)

    init {
        require(originBinding.isNotBlank())
    }

    @Synchronized
    override fun read(): OAuthSession? {
        val envelope = preferences.getString(KEY_ENCRYPTED_SESSION, null) ?: return null
        return try {
            val root = JSONObject(envelope)
            if (root.getInt("version") != ENVELOPE_VERSION) return clearAndReturnNull()
            val iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(root.getString("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(authenticatedData)
            codec.decode(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)) ?: clearAndReturnNull()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            clearAndReturnNull()
        }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Token exchange runs off the UI thread; survive immediate process death.
    override fun write(session: OAuthSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(authenticatedData)
        val ciphertext = cipher.doFinal(codec.encode(session).toByteArray(StandardCharsets.UTF_8))
        val envelope = JSONObject()
            .put("version", ENVELOPE_VERSION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
        preferences.edit().putString(KEY_ENCRYPTED_SESSION, envelope).commit()
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Clearing an invalid envelope must also be durable.
    override fun clear() {
        preferences.edit().remove(KEY_ENCRYPTED_SESSION).commit()
    }

    override fun toString(): String = "AndroidKeystoreOAuthSessionStore(redacted)"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun clearAndReturnNull(): OAuthSession? {
        clear()
        return null
    }

    private companion object {
        const val PREFERENCES_NAME = "frameos_secure_oauth"
        const val KEY_ENCRYPTED_SESSION = "encrypted_session"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "frameos_home_assistant_oauth_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val ENVELOPE_VERSION = 1
        const val AUTHENTICATED_DATA_PREFIX = "com.wyattfleming.frameos:oauth-session:v1"
    }
}
