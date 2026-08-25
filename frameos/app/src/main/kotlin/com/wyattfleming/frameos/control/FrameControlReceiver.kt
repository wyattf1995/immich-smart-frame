package com.wyattfleming.frameos.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wyattfleming.frameos.MainActivity
import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.config.FrameConfigurationStore

class FrameControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FrameControlContract.ACTION_CONTROL) return

        val configurationStore = FrameConfigurationStore(context)
        var provisioned = false
        if (configurationStore.read() == null) {
            val configuration = FrameConfiguration.from(
                photosUrl = intent.getStringExtra(FrameControlContract.EXTRA_PHOTOS_URL).orEmpty(),
                homeAssistantUrl = intent.getStringExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_URL).orEmpty(),
                weatherEntityId = intent
                    .getStringExtra(FrameControlContract.EXTRA_WEATHER_ENTITY_ID)
                    .orEmpty()
                    .ifBlank { DEFAULT_WEATHER_ENTITY_ID },
            )
            if (configuration != null) {
                configurationStore.write(configuration)
                provisioned = true
            }
        }

        val command = FrameControlCommandCodec().decode(
            command = intent.getStringExtra(FrameControlContract.EXTRA_COMMAND),
            mode = intent.getStringExtra(FrameControlContract.EXTRA_MODE),
        )
        if (command != null) FrameControlStore(context).write(command)
        if (!provisioned && command == null) return

        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }

    private companion object {
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
    }
}
