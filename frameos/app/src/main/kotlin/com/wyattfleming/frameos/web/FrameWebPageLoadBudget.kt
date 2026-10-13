package com.wyattfleming.frameos.web

/**
 * Keeps a slow first Home Assistant document from being treated as a failed
 * generic web surface. Explicit caller overrides keep their exact value.
 */
class FrameWebPageLoadBudget(
    private val defaultMillis: Long = DEFAULT_MILLIS,
    private val homeAssistantMillis: Long = HOME_ASSISTANT_MILLIS,
) {
    init {
        require(defaultMillis > 0L)
        require(homeAssistantMillis > 0L)
    }

    fun millisFor(label: String, explicitOverrideMillis: Long? = null): Long =
        explicitOverrideMillis ?: if (label == HOME_ASSISTANT_LABEL) homeAssistantMillis else defaultMillis

    companion object {
        const val HOME_ASSISTANT_LABEL = "Home Assistant"
        const val DEFAULT_MILLIS = 20_000L
        const val HOME_ASSISTANT_MILLIS = 60_000L
    }
}
