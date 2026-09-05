package com.wyattfleming.frameos.boot

internal object FrameBootRecoveryPolicy {
    internal val recoveryDelaysMillis = listOf(0L, 15_000L, 60_000L)

    fun launchDelaysFor(action: String?): List<Long> = when (action) {
        ACTION_BOOT_COMPLETED,
        ACTION_MY_PACKAGE_REPLACED,
        -> recoveryDelaysMillis
        else -> emptyList()
    }

    private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
}
