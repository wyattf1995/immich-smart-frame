package com.wyattfleming.frameos.control

import android.content.Context

class FrameControlStore(
    context: Context,
    private val codec: FrameControlCommandCodec = FrameControlCommandCodec(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun write(command: FrameControlCommand) {
        preferences.edit().putString(KEY_PENDING_COMMAND, codec.encode(command)).apply()
    }

    @Synchronized
    fun consume(): FrameControlCommand? {
        val encoded = preferences.getString(KEY_PENDING_COMMAND, null) ?: return null
        preferences.edit().remove(KEY_PENDING_COMMAND).apply()
        return codec.decodeStored(encoded)
    }

    private companion object {
        const val PREFERENCES_NAME = "frameos_control"
        const val KEY_PENDING_COMMAND = "pending_command"
    }
}
