package com.wyattfleming.frameos.auth

import android.content.Context

class SharedPreferencesPendingOAuthStateStore(context: Context) : PendingOAuthStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun write(state: OAuthState) {
        preferences.edit().putString(KEY_PENDING_STATE, state.value).apply()
    }

    @Synchronized
    override fun consume(): String? {
        val state = preferences.getString(KEY_PENDING_STATE, null)
        preferences.edit().remove(KEY_PENDING_STATE).apply()
        return state
    }

    override fun toString(): String = "SharedPreferencesPendingOAuthStateStore(redacted)"

    private companion object {
        const val PREFERENCES_NAME = "frameos_pending_oauth"
        const val KEY_PENDING_STATE = "pending_state"
    }
}
