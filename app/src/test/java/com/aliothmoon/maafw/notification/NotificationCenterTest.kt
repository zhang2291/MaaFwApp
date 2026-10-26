package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.NotRunCause
import com.aliothmoon.maafw.runner.RunEndReason
import com.aliothmoon.maafw.runner.RunLogRecorder
import com.aliothmoon.maafw.runner.TaskResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotificationCenterTest {
    private val notifier = mockk<RunEventNotifier>(relaxed = true)
    private val external = mockk<ExternalNotificationService>(relaxed = true)
    private val settings = mockk<NotificationSettingsManager> {
        coEvery { current() } returns NotificationSettings()
    }
    private val center = NotificationCenter(
        eventNotifier = notifier,
        external = external,
        settings = settings,
        recorder = mockk<RunLogRecorder>(relaxed = true),
        renderText = { it.toString() },
        servicePort = FakePrivilegedServicePort(),
    )

    @Test
    fun `completed run reports completion`() = runTest {
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Completed(listOf(TaskResult("daily", true)))))

        verify(exactly = 1) { notifier.notifyRunFinished(uiTextOf(R.string.notification_event_run_completed).toString(), any(), false) }
        verify(exactly = 1) { external.send(any(), any()) }
    }

    @Test
    fun `failed startup reports the real reason rather than completion`() = runTest {
        val reason = uiTextOf(R.string.msg_reject_setup_failed)
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Failed(reason)))

        verify(exactly = 1) { notifier.notifyRunFinished(uiTextOf(R.string.notification_event_run_failed).toString(), reason.toString(), true) }
        verify(exactly = 1) { external.send(any(), reason.toString()) }
    }

    @Test
    fun `partial failure is reported as an error`() = runTest {
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.CompletedWithFailures(listOf(TaskResult("daily", false)))))

        verify(exactly = 1) { notifier.notifyRunFinished(uiTextOf(R.string.notification_event_run_partial).toString(), any(), true) }
    }

    @Test
    fun `cancelled run gives local feedback without sending external completion`() = runTest {
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())))

        verify(exactly = 1) { notifier.notifyRunFinished(uiTextOf(R.string.result_cancelled).toString(), any(), false) }
        verify(exactly = 0) { external.send(any(), any()) }
        coVerify(exactly = 0) { settings.current() }
    }

    @Test
    fun `request which never ran does not send an execution result`() = runTest {
        center.onRunFinished(RunEndReason.NotRun(NotRunCause.Rejected))

        verify(exactly = 0) { notifier.notifyRunFinished(any(), any(), any()) }
        verify(exactly = 0) { external.send(any(), any()) }
    }
}
