package com.wyattfleming.frameos.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.config.FrameConfigurationStore
import com.wyattfleming.frameos.weather.SharedPreferencesWeatherCache

class FrameControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FrameControlContract.ACTION_CONTROL) return

        val configurationStore = FrameConfigurationStore(context)
        if (configurationStore.read() == null) {
            val configuration = FrameConfiguration.from(
                photosUrl = intent.getStringExtra(FrameControlContract.EXTRA_PHOTOS_URL).orEmpty(),
                homeAssistantUrl = intent.getStringExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_URL).orEmpty(),
                weatherEntityId = intent
                    .getStringExtra(FrameControlContract.EXTRA_WEATHER_ENTITY_ID)
                    .orEmpty()
                    .ifBlank { DEFAULT_WEATHER_ENTITY_ID },
                homeAssistantFallbackUrl = intent
                    .getStringExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_FALLBACK_URL),
                birdsUrl = intent.getStringExtra(FrameControlContract.EXTRA_BIRDS_URL),
            )
            if (configuration != null) {
                SharedPreferencesWeatherCache(context).clear()
                configurationStore.write(configuration)
            }
        }

        val command = FrameControlCommandCodec().decode(
            command = intent.getStringExtra(FrameControlContract.EXTRA_COMMAND),
            mode = intent.getStringExtra(FrameControlContract.EXTRA_MODE),
        )
        if (command != null) FrameControlStore(context).write(command)
    }

    private companion object {
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
    }
}
