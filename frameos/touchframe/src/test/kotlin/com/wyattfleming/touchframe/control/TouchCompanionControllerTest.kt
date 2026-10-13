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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun `persists command claim before dispatch and only acknowledges explicit result`() {
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
            assertEquals(listOf("11111111-1111-1111-1111-111111111111"), journal.claimed)
            assertEquals(listOf(FrameRemoteAck("11111111-1111-1111-1111-111111111111", "unsupported", "not supported by this preview")), journal.acknowledged)
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
        onCommand: (com.wyattfleming.frameos.control.FrameRemoteCommand, Long) -> TouchCompanionController.CommandResult = { _, _ -> TouchCompanionController.CommandResult.Applied() },
    ): TouchCompanionController = TouchCompanionController.forTest(
        credentials = { FrameCompanionCredentials(requireNotNull(FrameRemoteEndpoint.from("https://companion.example")), "token", "touch-frame") },
        journal = journal,
        poll = poll,
        mainExecutor = Executor { it.run() },
        scheduler = scheduler,
        nowMillis = { 1_000L },
        statusProvider = {
            FrameRemoteStatus(FrameMode.HOME, false, null, null, 0, null, false, "test")
        },
        onCommand = onCommand,
    )

    private fun commandEnvelope() = """
        {"schema":1,"deviceId":"touch-frame","commands":[{
          "id":"11111111-1111-1111-1111-111111111111",
          "issuedAt":1000,"expiresAt":2000,"type":"show_mode","mode":"PHOTOS"
        }]}
    """.trimIndent()

    private class RecordingJournal : TouchCompanionController.CommandJournal {
        val claimed = mutableListOf<String>()
        val acknowledged = mutableListOf<FrameRemoteAck>()

        override fun pendingAcks(): List<FrameRemoteAck> = emptyList()
        override fun clearAcks(acks: List<FrameRemoteAck>) = Unit
        override fun claim(id: String): Boolean = (id !in claimed).also { if (it) claimed += id }
        override fun acknowledge(id: String, status: String, message: String?) {
            acknowledged += FrameRemoteAck(id, status, message)
        }
    }
}
