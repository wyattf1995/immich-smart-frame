package com.wyattfleming.touchframe.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wyattfleming.frameos.control.FrameCompanionClient
import com.wyattfleming.frameos.control.FrameCompanionCommandStore
import com.wyattfleming.frameos.control.FrameCompanionCredentials
import com.wyattfleming.frameos.control.FrameCompanionCredentialsStore
import com.wyattfleming.frameos.control.FrameCompanionEnvelope
import com.wyattfleming.frameos.control.FrameCompanionEnvelopeDecoder
import com.wyattfleming.frameos.control.FrameCompanionPollResult
import com.wyattfleming.frameos.control.FrameCompanionSnapshotRevision
import com.wyattfleming.frameos.control.FrameRemoteAck
import com.wyattfleming.frameos.control.FrameRemoteCommand
import com.wyattfleming.frameos.control.FrameRemoteStatus
import com.wyattfleming.frameos.control.FrameRemoteSettings
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in single-flight companion poller for TouchFrame. It deliberately has no
 * default provisioning or enabled state, so an installed preview cannot poll
 * alongside the existing FrameOS app with the same device identity.
 */
class TouchCompanionController private constructor(
    private val credentials: () -> FrameCompanionCredentials?,
    private val journal: CommandJournal,
    private val poll: (FrameCompanionCredentials, FrameRemoteStatus, List<FrameRemoteAck>, String?) -> FrameCompanionPollResult,
    private val mainExecutor: Executor,
    private val scheduler: ScheduledExecutorService,
    private val mainCallbackTimeoutMillis: Long,
    private val nowMillis: () -> Long,
    private val statusProvider: () -> FrameRemoteStatus?,
    private val onCommand: (FrameRemoteCommand, Long) -> CommandResult,
    private val onSettings: (FrameRemoteSettings, Long) -> Boolean,
    private val onEvent: (com.wyattfleming.frameos.control.FrameCompanionEvent, Long) -> Boolean,
    /** Called off-main; the supplied predicate must fence any disk commit. */
    private val onHiddenAssets: (Set<String>, Long, () -> Boolean) -> Boolean,
) : AutoCloseable {
    sealed interface CommandResult {
        /** The callback synchronously completed the requested local state change. */
        data class Applied(val message: String? = null) : CommandResult
        /** The Touch bridge accepted ownership but completion must be observed separately. */
        data class Dispatched(val message: String? = null) : CommandResult
        /** The request is unsupported by this Touch build and is terminally rejected. */
        data class Unsupported(val message: String? = null) : CommandResult
        /** The callback attempted the request and knows it failed. */
        data class Failed(val message: String? = null) : CommandResult
    }

    interface CommandJournal {
        fun pendingAcks(): List<FrameRemoteAck>
        fun clearAcks(acks: List<FrameRemoteAck>)
        fun claim(id: String): Boolean
        fun acknowledge(id: String, status: String, message: String? = null)
    }

    private val lock = Any()
    private var started = false
    private var enabled = false
    private var closed = false
    private var inFlight = false
    private var generation = 0L
    private var snapshotRevision: String? = null
    private var scheduled: java.util.concurrent.ScheduledFuture<*>? = null

    /** Enables polling only after an explicit, completed takeover provision. */
    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (closed || enabled == value) return
            enabled = value
            invalidateLocked(clearRevision = true)
            if (started && enabled) scheduleLocked(INITIAL_DELAY_MILLIS)
        }
    }

    fun start() {
        synchronized(lock) {
            if (closed || started) return
            started = true
            if (enabled) scheduleLocked(INITIAL_DELAY_MILLIS)
        }
    }

    /** Stops future polls and makes every queued result stale; it does not erase credentials. */
    fun stop() {
        synchronized(lock) {
            if (closed) return
            started = false
            invalidateLocked(clearRevision = true)
        }
    }

    /** Call after an intentional credential rewrite; the next poll requests a full snapshot. */
    fun onCredentialsChanged() {
        synchronized(lock) {
            if (closed) return
            invalidateLocked(clearRevision = true)
            if (started && enabled) scheduleLocked(INITIAL_DELAY_MILLIS)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            started = false
            invalidateLocked(clearRevision = true)
        }
        scheduler.shutdownNow()
    }

    private fun scheduleLocked(delayMillis: Long) {
        if (closed || !started || !enabled || inFlight || scheduled != null) return
        val expectedGeneration = generation
        scheduled = scheduler.schedule({ runPoll(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun runPoll(expectedGeneration: Long) {
        synchronized(lock) {
            scheduled = null
            if (!isCurrentLocked(expectedGeneration) || inFlight) return
            inFlight = true
        }
        val success = runCatching { pollOnce(expectedGeneration) }.getOrDefault(false)
        finishPoll(expectedGeneration, success)
    }

    private fun pollOnce(expectedGeneration: Long): Boolean {
        val currentCredentials = credentials() ?: return false
        val status = onMain(expectedGeneration) { statusProvider() } ?: return false
        if (!isCurrent(expectedGeneration)) return false
        val acknowledgements = journal.pendingAcks()
        val sentRevision = synchronized(lock) {
            snapshotRevision?.let(FrameCompanionSnapshotRevision::forRequest)
        }
        val result = poll(currentCredentials, status, acknowledgements, sentRevision)
        val envelope = (result as? FrameCompanionPollResult.Success)?.let {
            FrameCompanionEnvelopeDecoder.decode(it.body, currentCredentials.deviceId, nowMillis(), sentRevision)
        } ?: return false
        val accepted = applyEnvelope(envelope, expectedGeneration)
        if (accepted && isCurrent(expectedGeneration)) {
            journal.clearAcks(acknowledgements)
        }
        return accepted
    }

    private fun applyEnvelope(envelope: FrameCompanionEnvelope, expectedGeneration: Long): Boolean {
        if (!isCurrent(expectedGeneration)) return false
        if (envelope is FrameCompanionEnvelope.Unchanged) {
            synchronized(lock) { if (isCurrentLocked(expectedGeneration)) snapshotRevision = envelope.snapshotRevision }
            return true
        }
        envelope as FrameCompanionEnvelope.Full
        val settingsAccepted = envelope.settings?.let {
            onMain(expectedGeneration) { onSettings(it, expectedGeneration) } == true
        } ?: true
        val eventAccepted = envelope.event?.let {
            onMain(expectedGeneration) { onEvent(it, expectedGeneration) } == true
        } ?: true
        val hiddenAccepted = envelope.hiddenAssets?.let { ids ->
            onHiddenAssets(ids, expectedGeneration) { isCurrent(expectedGeneration) }
        } ?: true
        if (!settingsAccepted || !eventAccepted || !hiddenAccepted || !isCurrent(expectedGeneration)) return false
        envelope.command?.let { command -> dispatchCommand(command, expectedGeneration) }
        synchronized(lock) {
            if (!isCurrentLocked(expectedGeneration)) return false
            snapshotRevision = envelope.snapshotRevision
        }
        return true
    }

    /** Claim is durable before main-thread dispatch; acknowledgement reflects the callback result only. */
    private fun dispatchCommand(command: FrameRemoteCommand, expectedGeneration: Long) {
        if (!journal.claim(command.id) || !isCurrent(expectedGeneration)) return
        onMain(expectedGeneration) { onCommand(command, expectedGeneration) }?.let { outcome ->
            journal.acknowledge(command.id, acknowledgementStatus(outcome), outcome.message)
        } // Stale/lifecycle-invalid callbacks are deliberately not auto-acknowledged.
    }

    /** The worker waits only for a bounded UI callback; neither networking nor decode runs on main. */
    private fun <T> onMain(expectedGeneration: Long, callback: () -> T): T? {
        if (!isCurrent(expectedGeneration)) return null
        val task = FutureTask<T?> { if (isCurrent(expectedGeneration)) callback() else null }
        return try {
            mainExecutor.execute(task)
            task.get(mainCallbackTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // A queued Handler task otherwise survives this timeout and can mutate UI after
            // the worker has rejected the snapshot. FutureTask refuses to run once cancelled.
            task.cancel(false)
            null
        }
    }

    private fun finishPoll(expectedGeneration: Long, success: Boolean) {
        synchronized(lock) {
            if (generation == expectedGeneration) {
                inFlight = false
                if (started && enabled && !closed) scheduleLocked(if (success) SUCCESS_DELAY_MILLIS else FAILURE_DELAY_MILLIS)
            }
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) { isCurrentLocked(expectedGeneration) }

    private fun isCurrentLocked(expectedGeneration: Long): Boolean =
        !closed && started && enabled && generation == expectedGeneration

    private fun invalidateLocked(clearRevision: Boolean) {
        generation += 1
        scheduled?.cancel(false)
        scheduled = null
        // An old worker may still return, but its generation can no longer commit.
        inFlight = false
        if (clearRevision) snapshotRevision = null
    }

    companion object {
        fun create(
            context: Context,
            statusProvider: () -> FrameRemoteStatus?,
            onCommand: (FrameRemoteCommand, Long) -> CommandResult,
            onSettings: (FrameRemoteSettings, Long) -> Boolean,
            onEvent: (com.wyattfleming.frameos.control.FrameCompanionEvent, Long) -> Boolean,
            onHiddenAssets: (Set<String>, Long, () -> Boolean) -> Boolean,
        ): TouchCompanionController = TouchCompanionController(
            credentials = { FrameCompanionCredentialsStore(context).read() },
            journal = AndroidCommandJournal(FrameCompanionCommandStore(context)),
            poll = { credentials, status, acknowledgements, revision ->
                FrameCompanionClient(credentials.endpoint, credentials.token).poll(
                    credentials.deviceId, status, acknowledgements, revision,
                )
            },
            mainExecutor = Executor { action -> Handler(Looper.getMainLooper()).post(action) },
            scheduler = ScheduledThreadPoolExecutor(1) { action -> Thread(action, "touch-companion").apply { isDaemon = true } },
            mainCallbackTimeoutMillis = MAIN_CALLBACK_TIMEOUT_MILLIS,
            nowMillis = System::currentTimeMillis,
            statusProvider = statusProvider,
            onCommand = onCommand,
            onSettings = onSettings,
            onEvent = onEvent,
            onHiddenAssets = onHiddenAssets,
        )

        /** Maps every explicit bridge outcome to the companion's accepted terminal vocabulary. */
        internal fun acknowledgementStatus(outcome: CommandResult): String = when (outcome) {
            is CommandResult.Applied -> "applied"
            is CommandResult.Dispatched -> "dispatched"
            is CommandResult.Unsupported -> "rejected"
            is CommandResult.Failed -> "failed"
        }

        internal fun forTest(
            credentials: () -> FrameCompanionCredentials?,
            journal: CommandJournal,
            poll: (FrameCompanionCredentials, FrameRemoteStatus, List<FrameRemoteAck>, String?) -> FrameCompanionPollResult,
            mainExecutor: Executor,
            scheduler: ScheduledExecutorService,
            nowMillis: () -> Long,
            mainCallbackTimeoutMillis: Long = MAIN_CALLBACK_TIMEOUT_MILLIS,
            statusProvider: () -> FrameRemoteStatus?,
            onCommand: (FrameRemoteCommand, Long) -> CommandResult,
            onSettings: (FrameRemoteSettings, Long) -> Boolean = { _, _ -> true },
            onEvent: (com.wyattfleming.frameos.control.FrameCompanionEvent, Long) -> Boolean = { _, _ -> true },
            onHiddenAssets: (Set<String>, Long, () -> Boolean) -> Boolean = { _, _, _ -> true },
        ) = TouchCompanionController(credentials, journal, poll, mainExecutor, scheduler, mainCallbackTimeoutMillis, nowMillis, statusProvider, onCommand, onSettings, onEvent, onHiddenAssets)

        private const val INITIAL_DELAY_MILLIS = 0L
        private const val SUCCESS_DELAY_MILLIS = 5_000L
        private const val FAILURE_DELAY_MILLIS = 10_000L
        private const val MAIN_CALLBACK_TIMEOUT_MILLIS = 2_000L
    }

    private class AndroidCommandJournal(private val store: FrameCompanionCommandStore) : CommandJournal {
        override fun pendingAcks(): List<FrameRemoteAck> = store.consumeAcks()
        override fun clearAcks(acks: List<FrameRemoteAck>) = store.clearAcks(acks)
        override fun claim(id: String): Boolean = store.claim(id)
        override fun acknowledge(id: String, status: String, message: String?) = store.ack(id, status, message)
    }
}
