package com.wyattfleming.touchframe.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wyattfleming.touchframe.data.TouchConfigurationStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * DUMP-protected, one-time non-secret configuration bootstrap, plus a later change of the weather location label
 * alone. It neither accepts nor copies OAuth, companion, or other credentials. A successful result requires an app
 * restart.
 */
class TouchProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TouchProvisionContract.ACTION_PROVISION -> provision(context, intent)
            TouchProvisionContract.ACTION_SET_WEATHER_LOCATION -> setWeatherLocation(context, intent)
        }
    }

    /** Provisioning is write-once, so the label shown beside "Updated" on Weather is changed here instead. */
    private fun setWeatherLocation(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            worker.execute {
                try {
                    val store = TouchConfigurationStore(context)
                    val current = store.read()
                    val updated = current?.let { TouchProvisionContract.withWeatherLocation(current, intent.getStringExtra(TouchProvisionContract.EXTRA_WEATHER_LOCATION_LABEL)) }
                    when {
                        current == null -> pending.result(TouchProvisionContract.RESULT_INVALID, "not-provisioned")
                        updated == null -> pending.result(TouchProvisionContract.RESULT_INVALID, "invalid-request")
                        store.replaceDurably(updated) -> pending.result(TouchProvisionContract.RESULT_OK, "updated-restart-required")
                        else -> pending.result(TouchProvisionContract.RESULT_FAILED, "update-failed")
                    }
                } catch (_: RuntimeException) {
                    pending.result(TouchProvisionContract.RESULT_FAILED, "update-failed")
                } finally {
                    pending.finish()
                }
            }
        } catch (_: RuntimeException) {
            pending.result(TouchProvisionContract.RESULT_FAILED, "update-failed")
            pending.finish()
        }
    }

    private fun provision(context: Context, intent: Intent) {
        val configuration = TouchProvisionContract.parse(
            photosUrl = intent.getStringExtra(TouchProvisionContract.EXTRA_PHOTOS_URL),
            homeAssistantUrl = intent.getStringExtra(TouchProvisionContract.EXTRA_HOME_ASSISTANT_URL),
            weatherEntityId = intent.getStringExtra(TouchProvisionContract.EXTRA_WEATHER_ENTITY_ID),
            weatherLocationLabel = intent.getStringExtra(TouchProvisionContract.EXTRA_WEATHER_LOCATION_LABEL),
            artUrl = intent.getStringExtra(TouchProvisionContract.EXTRA_ART_URL),
            birdsUrl = intent.getStringExtra(TouchProvisionContract.EXTRA_BIRDS_URL),
            climateEntityId = intent.getStringExtra(TouchProvisionContract.EXTRA_CLIMATE_ENTITY_ID),
            calendarEntityIds = intent.getStringArrayListExtra(TouchProvisionContract.EXTRA_CALENDAR_ENTITY_IDS),
            cameraEntityIds = intent.getStringArrayListExtra(TouchProvisionContract.EXTRA_CAMERA_ENTITY_IDS),
        )
        if (configuration == null) {
            result(TouchProvisionContract.RESULT_INVALID, "invalid-request")
            return
        }

        val pending = goAsync()
        try {
            worker.execute {
                try {
                    when (TouchConfigurationStore(context).writeInitialDurably(configuration)) {
                        TouchConfigurationStore.InitialWriteResult.WRITTEN ->
                            pending.result(TouchProvisionContract.RESULT_OK, "provisioned-restart-required")
                        TouchConfigurationStore.InitialWriteResult.ALREADY_PRESENT ->
                            pending.result(TouchProvisionContract.RESULT_INVALID, "already-provisioned")
                        TouchConfigurationStore.InitialWriteResult.FAILED ->
                            pending.result(TouchProvisionContract.RESULT_FAILED, "provisioning-failed")
                    }
                } catch (_: RuntimeException) {
                    pending.result(TouchProvisionContract.RESULT_FAILED, "provisioning-failed")
                } finally {
                    pending.finish()
                }
            }
        } catch (_: RuntimeException) {
            pending.result(TouchProvisionContract.RESULT_FAILED, "provisioning-failed")
            pending.finish()
        }
    }

    private fun result(code: Int, text: String) {
        setResultCode(code)
        setResultData(text)
    }

    private fun PendingResult.result(code: Int, text: String) {
        setResultCode(code)
        setResultData(text)
    }

    private companion object {
        val worker = ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(4),
            ThreadFactory { runnable -> Thread(runnable, "touchframe-provision").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
