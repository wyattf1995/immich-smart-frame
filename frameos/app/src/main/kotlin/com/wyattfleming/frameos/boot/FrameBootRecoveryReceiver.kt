package com.wyattfleming.frameos.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.wyattfleming.frameos.MainActivity

class FrameBootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FrameBootRecoveryScheduler.ACTION_RETRY) {
            FrameBootRecoveryScheduler.launch(context)
            return
        }

        val delays = FrameBootRecoveryPolicy.launchDelaysFor(intent.action)
        if (delays.isNotEmpty()) {
            FrameBootRecoveryScheduler.schedule(context, delays)
        }
    }
}

internal object FrameBootRecoveryScheduler {
    const val ACTION_RETRY = "com.wyattfleming.frameos.BOOT_RECOVERY_RETRY"

    fun schedule(context: Context, delaysMillis: List<Long>) {
        cancelRetries(context)
        delaysMillis.forEachIndexed { attemptIndex, delayMillis ->
            if (delayMillis == 0L) {
                launch(context)
            } else {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    saturatedAdd(SystemClock.elapsedRealtime(), delayMillis),
                    retryIntent(context, attemptIndex),
                )
            }
        }
    }

    fun cancelRetries(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        FrameBootRecoveryPolicy.recoveryDelaysMillis.indices.drop(1).forEach { attemptIndex ->
            alarmManager.cancel(retryIntent(context, attemptIndex))
        }
    }

    fun launch(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(LOG_TAG, "Boot recovery skipped until display-over-other-apps access is granted")
            return
        }
        val launchIntent = Intent(context, MainActivity::class.java).setAction(ACTION_RETRY).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        try {
            context.startActivity(launchIntent)
        } catch (error: RuntimeException) {
            Log.e(LOG_TAG, "Unable to restore FrameOS after boot", error)
        }
    }

    private fun retryIntent(context: Context, attemptIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE_BASE + attemptIndex,
            Intent(context, FrameBootRecoveryReceiver::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (increment > 0 && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private const val RETRY_REQUEST_CODE_BASE = 24_000
    private const val LOG_TAG = "FrameBootRecovery"
}
