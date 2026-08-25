package com.wyattfleming.frameos.auth

import android.annotation.SuppressLint
import android.content.Context

class SharedPreferencesPendingOAuthStateStore(context: Context) : PendingOAuthStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    @SuppressLint("ApplySharedPref") // The external OAuth browser can outlive this process.
    override fun write(state: OAuthState) {
        // This state is needed after an external browser can reclaim the process.
        preferences.edit().putString(KEY_PENDING_STATE, state.value).commit()
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Consume durably so a callback cannot replay after a crash.
    override fun consume(): String? {
        val state = preferences.getString(KEY_PENDING_STATE, null)
        preferences.edit().remove(KEY_PENDING_STATE).commit()
        return state
    }

    override fun toString(): String = "SharedPreferencesPendingOAuthStateStore(redacted)"

    private companion object {
        const val PREFERENCES_NAME = "frameos_pending_oauth"
        const val KEY_PENDING_STATE = "pending_state"
    }
}
