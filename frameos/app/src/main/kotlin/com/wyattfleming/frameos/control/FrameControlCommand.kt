package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import java.util.Locale

sealed interface FrameControlCommand {
    data object Next : FrameControlCommand
    data object Previous : FrameControlCommand
    data object Home : FrameControlCommand
    data class Show(val mode: FrameMode) : FrameControlCommand
}

class FrameControlCommandCodec {
    fun decode(command: String?, mode: String?): FrameControlCommand? {
        val requestedMode = mode
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { name -> FrameMode.entries.firstOrNull { it.name == name } }
        if (requestedMode != null) return FrameControlCommand.Show(requestedMode)

        return when (command?.trim()?.lowercase(Locale.ROOT)) {
            "next" -> FrameControlCommand.Next
            "prev", "previous" -> FrameControlCommand.Previous
            "home" -> FrameControlCommand.Home
            else -> null
        }
    }

    fun encode(command: FrameControlCommand): String = when (command) {
        FrameControlCommand.Next -> "next"
        FrameControlCommand.Previous -> "previous"
        FrameControlCommand.Home -> "home"
        is FrameControlCommand.Show -> "show:${command.mode.name.lowercase(Locale.ROOT)}"
    }

    fun decodeStored(value: String): FrameControlCommand? = when {
        value.startsWith(SHOW_PREFIX) -> decode(command = null, mode = value.removePrefix(SHOW_PREFIX))
        else -> decode(command = value, mode = null)
    }

    private companion object {
        const val SHOW_PREFIX = "show:"
    }
}

object FrameControlContract {
    const val ACTION_CONTROL = "com.wyattfleming.frameos.CONTROL"
    const val EXTRA_COMMAND = "frameos.command"
    const val EXTRA_MODE = "frameos.mode"
    const val EXTRA_PHOTOS_URL = "frameos.photos_url"
    const val EXTRA_HOME_ASSISTANT_URL = "frameos.home_assistant_url"
    const val EXTRA_HOME_ASSISTANT_FALLBACK_URL = "frameos.home_assistant_fallback_url"
    const val EXTRA_WEATHER_ENTITY_ID = "frameos.weather_entity_id"
    const val EXTRA_BIRDS_URL = "frameos.birds_url"
}
