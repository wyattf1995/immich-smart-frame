package com.wyattfleming.touchframe.control

import com.wyattfleming.frameos.control.FrameCompanionCredentials
import com.wyattfleming.frameos.control.FrameCompanionPollResult
import com.wyattfleming.frameos.control.FrameRemoteAck
import com.wyattfleming.frameos.control.FrameRemoteEndpoint
import com.wyattfleming.frameos.control.FrameRemoteStatus
import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TouchCompanionControllerTest {
    @Test
    fun `default disabled controller never starts companion polling`() {
        val pollCalls = AtomicInteger()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val controller = controller(scheduler, poll = { _, _, _, _ ->
            pollCalls.incrementAndGet()
            FrameCompanionPollResult.Offline
        })
        try {
            controller.start()
            Thread.sleep(50)
            assertEquals(0, pollCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `persists command claim before dispatch and rejects unsupported commands terminally`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val journal = RecordingJournal()
        val dispatched = CountDownLatch(1)
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            poll = { _, _, _, _ -> FrameCompanionPollResult.Success(commandEnvelope()) },
            onCommand = { command, _ ->
                assertTrue("claim must be durable before main dispatch", command.id in journal.claimed)
                dispatched.countDown()
                TouchCompanionController.CommandResult.Unsupported("not supported by this preview")
            },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(dispatched.await(2, TimeUnit.SECONDS))
            assertTrue(journal.acknowledgement.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("11111111-1111-1111-1111-111111111111"), journal.claimed)
            assertEquals(listOf(FrameRemoteAck("11111111-1111-1111-1111-111111111111", "rejected", "not supported by this preview")), journal.acknowledged)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `maps every explicit bridge outcome to a canonical terminal companion status`() {
        assertEquals(
            setOf("applied", "dispatched", "rejected", "failed", "expired"),
            setOf(
                TouchCompanionController.acknowledgementStatus(TouchCompanionController.CommandResult.Applied()),
                TouchCompanionController.acknowledgementStatus(TouchCompanionController.CommandResult.Dispatched()),
                TouchCompanionController.acknowledgementStatus(TouchCompanionController.CommandResult.Unsupported()),
                TouchCompanionController.acknowledgementStatus(TouchCompanionController.CommandResult.Failed()),
                TouchCompanionController.acknowledgementStatus(TouchCompanionController.CommandResult.Expired()),
            ),
        )
    }

    @Test
    fun `event acceptance runs on poll worker and blocks snapshot command when persistence rejects`() {
        val scheduler = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "event-poll-worker") }
        val eventReceived = CountDownLatch(1)
        val commandCalls = AtomicInteger()
        val eventWorker = AtomicReference<String?>()
        val controller = controller(
            scheduler = scheduler,
            poll = { _, _, _, _ -> FrameCompanionPollResult.Success(eventAndCommandEnvelope()) },
            onEvent = { _, _, canCommit ->
                eventWorker.set(Thread.currentThread().name)
                assertTrue(canCommit())
                eventReceived.countDown()
                false // Simulates a failed durable event-store commit.
            },
            onCommand = { _, _ -> commandCalls.incrementAndGet(); TouchCompanionController.CommandResult.Applied() },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(eventReceived.await(2, TimeUnit.SECONDS))
            assertTrue(eventWorker.get()?.contains("event-poll-worker") == true)
            assertEquals(0, commandCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `credential change during poll commits none of the old snapshot`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val credentials = AtomicReference(credentials("first-token"))
        val journal = RecordingJournal()
        val commandCalls = AtomicInteger()
        val settingCalls = AtomicInteger()
        val eventCalls = AtomicInteger()
        val hiddenCalls = AtomicInteger()
        val pollDone = CountDownLatch(1)
        val pollCount = AtomicInteger()
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            credentials = credentials::get,
            poll = { _, _, _, _ ->
                if (pollCount.getAndIncrement() == 0) {
                    credentials.set(credentials("replacement-token"))
                    pollDone.countDown()
                    FrameCompanionPollResult.Success(fullEnvelope())
                } else {
                    FrameCompanionPollResult.Offline
                }
            },
            onCommand = { _, _ -> commandCalls.incrementAndGet(); TouchCompanionController.CommandResult.Applied() },
            onSettings = { _, _ -> settingCalls.incrementAndGet(); true },
            onEvent = { _, _, _ -> eventCalls.incrementAndGet(); true },
            onHiddenAssets = { _, _, _ -> hiddenCalls.incrementAndGet(); true },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(pollDone.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)

            assertEquals(0, commandCalls.get())
            assertEquals(0, settingCalls.get())
            assertEquals(0, eventCalls.get())
            assertEquals(0, hiddenCalls.get())
            assertTrue(journal.acknowledged.isEmpty())
            assertEquals(0, journal.clearCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `command that expires while queued is terminally expired without dispatch`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val main = FirstInlineThenQueueExecutor()
        val now = AtomicLong(1_000L)
        val journal = RecordingJournal()
        val commandCalls = AtomicInteger()
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            mainExecutor = main,
            nowMillis = now::get,
            poll = { _, _, _, _ -> FrameCompanionPollResult.Success(commandEnvelope(expiresAt = 1_000L)) },
            onCommand = { _, _ ->
                commandCalls.incrementAndGet()
                TouchCompanionController.CommandResult.Applied()
            },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(main.queued.await(2, TimeUnit.SECONDS))
            now.set(1_001L)
            main.runQueued()
            assertTrue(journal.acknowledgement.await(2, TimeUnit.SECONDS))

            assertEquals(0, commandCalls.get())
            assertEquals(
                listOf(FrameRemoteAck("11111111-1111-1111-1111-111111111111", "expired", "expired before main-thread dispatch")),
                journal.acknowledged,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `stop after durable claim prevents queued command dispatch`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val main = FirstInlineThenQueueExecutor()
        val journal = RecordingJournal()
        val commandCalls = AtomicInteger()
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            mainExecutor = main,
            poll = { _, _, _, _ -> FrameCompanionPollResult.Success(commandEnvelope()) },
            onCommand = { _, _ ->
                commandCalls.incrementAndGet()
                TouchCompanionController.CommandResult.Applied()
            },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(main.queued.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("11111111-1111-1111-1111-111111111111"), journal.claimed)

            controller.stop()
            main.runQueued()

            assertEquals(0, commandCalls.get())
            assertTrue("a stopped controller must leave claim recovery to the canonical journal", journal.acknowledged.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `timed out queued callback is cancelled before a delayed main executor runs it`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val main = FirstInlineThenQueueExecutor()
        val journal = RecordingJournal()
        val commandCalls = AtomicInteger()
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            mainExecutor = main,
            mainCallbackTimeoutMillis = 50L,
            poll = { _, _, _, _ -> FrameCompanionPollResult.Success(commandEnvelope()) },
            onCommand = { _, _ ->
                commandCalls.incrementAndGet()
                TouchCompanionController.CommandResult.Applied()
            },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(main.queued.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            main.runQueued()

            assertEquals(0, commandCalls.get())
            assertEquals(listOf("11111111-1111-1111-1111-111111111111"), journal.claimed)
            assertTrue("timed-out dispatch has no acknowledgement", journal.acknowledged.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `stop makes an in-flight command stale before it can dispatch`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val pollEntered = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val journal = RecordingJournal()
        val commandCalls = AtomicInteger()
        val controller = controller(
            scheduler = scheduler,
            journal = journal,
            poll = { _, _, _, _ ->
                pollEntered.countDown()
                assertTrue(releasePoll.await(2, TimeUnit.SECONDS))
                FrameCompanionPollResult.Success(commandEnvelope())
            },
            onCommand = { _, _ ->
                commandCalls.incrementAndGet()
                TouchCompanionController.CommandResult.Applied()
            },
        )
        try {
            controller.setEnabled(true)
            controller.start()
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            controller.stop()
            releasePoll.countDown()
            Thread.sleep(100)
            assertEquals(0, commandCalls.get())
            assertFalse("a stale result cannot persist a command claim", journal.claimed.isNotEmpty())
        } finally {
            controller.close()
        }
    }

    private fun controller(
        scheduler: ScheduledThreadPoolExecutor,
        journal: RecordingJournal = RecordingJournal(),
        poll: (FrameCompanionCredentials, FrameRemoteStatus, List<FrameRemoteAck>, String?) -> FrameCompanionPollResult,
        credentials: () -> FrameCompanionCredentials? = { credentials("token") },
        mainExecutor: Executor = Executor { it.run() },
        mainCallbackTimeoutMillis: Long = 2_000L,
        nowMillis: () -> Long = { 1_000L },
        onCommand: (com.wyattfleming.frameos.control.FrameRemoteCommand, Long) -> TouchCompanionController.CommandResult = { _, _ -> TouchCompanionController.CommandResult.Applied() },
        onSettings: (com.wyattfleming.frameos.control.FrameRemoteSettings, Long) -> Boolean = { _, _ -> true },
        onEvent: (com.wyattfleming.frameos.control.FrameCompanionEvent, Long, () -> Boolean) -> Boolean = { _, _, _ -> true },
        onHiddenAssets: (Set<String>, Long, () -> Boolean) -> Boolean = { _, _, _ -> true },
    ): TouchCompanionController = TouchCompanionController.forTest(
        credentials = credentials,
        journal = journal,
        poll = poll,
        mainExecutor = mainExecutor,
        scheduler = scheduler,
        nowMillis = nowMillis,
        mainCallbackTimeoutMillis = mainCallbackTimeoutMillis,
        statusProvider = {
            FrameRemoteStatus(FrameMode.HOME, false, null, null, 0, null, false, "test")
        },
        onCommand = onCommand,
        onSettings = onSettings,
        onEvent = onEvent,
        onHiddenAssets = onHiddenAssets,
    )

    private fun credentials(token: String) = FrameCompanionCredentials(
        requireNotNull(FrameRemoteEndpoint.from("https://companion.example")),
        token,
        "touch-frame",
    )

    private fun eventAndCommandEnvelope() = """
        {"schema":1,"deviceId":"touch-frame",
         "events":[{"id":"22222222-2222-2222-2222-222222222222","type":"calendar","text":"Event","issuedAt":1000,"expiresAt":2000}],
         "commands":[{"id":"11111111-1111-1111-1111-111111111111","issuedAt":1000,"expiresAt":2000,"type":"show_mode","mode":"PHOTOS"}]}
    """.trimIndent()

    private fun fullEnvelope() = """
        {"schema":1,"deviceId":"touch-frame","hiddenAssets":["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"],
         "events":[{"id":"22222222-2222-2222-2222-222222222222","type":"calendar","text":"Event","issuedAt":1000,"expiresAt":2000}],
         "settingsRevision":0,"settings":{"modeOrder":["photos","home"],"quietHours":{"enabled":false,"start":"22:00","end":"07:00","brightness":50},"idleReturnSeconds":30,"profile":"default","hiddenHomeSuspend":false,"eventOverlays":false},
         "commands":[{"id":"11111111-1111-1111-1111-111111111111","issuedAt":1000,"expiresAt":2000,"type":"show_mode","mode":"PHOTOS"}]}
    """.trimIndent()

    private fun commandEnvelope(expiresAt: Long = 2_000L) = """
        {"schema":1,"deviceId":"touch-frame","commands":[{
          "id":"11111111-1111-1111-1111-111111111111",
          "issuedAt":1000,"expiresAt":$expiresAt,"type":"show_mode","mode":"PHOTOS"
        }]}
    """.trimIndent()

    private class FirstInlineThenQueueExecutor : Executor {
        private var calls = 0
        private val queuedActions = ArrayDeque<Runnable>()
        val queued = CountDownLatch(1)

        override fun execute(command: Runnable) {
            if (calls++ == 0) {
                command.run()
            } else {
                synchronized(queuedActions) { queuedActions.addLast(command) }
                queued.countDown()
            }
        }

        fun runQueued() {
            val next = synchronized(queuedActions) {
                if (queuedActions.isEmpty()) null else queuedActions.removeFirst()
            }
            next?.run()
        }
    }

    private class RecordingJournal : TouchCompanionController.CommandJournal {
        val claimed = mutableListOf<String>()
        val acknowledged = mutableListOf<FrameRemoteAck>()
        val acknowledgement = CountDownLatch(1)

        override fun pendingAcks(): List<FrameRemoteAck> = emptyList()
        val clearCalls = AtomicInteger()
        override fun clearAcks(acks: List<FrameRemoteAck>) { clearCalls.incrementAndGet() }
        override fun claim(id: String): Boolean = (id !in claimed).also { if (it) claimed += id }
        override fun acknowledge(id: String, status: String, message: String?) {
            acknowledged += FrameRemoteAck(id, status, message)
            acknowledgement.countDown()
        }
    }
}
