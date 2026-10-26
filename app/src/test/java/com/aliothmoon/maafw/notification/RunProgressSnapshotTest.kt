package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.runner.ActiveExecution
import com.aliothmoon.maafw.runner.RunnerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressSnapshotTest {

    @Test
    fun `Preparing is indeterminate even when the plan already knows the total`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Preparing,
            execution = execution(done = 0, total = 5),
            statusText = null,
        )
        assertEquals(RunProgressTitle.Preparing, snapshot.title)
        assertTrue(snapshot.indeterminate)
        assertEquals(0, snapshot.progress)
        assertEquals("0/5", snapshot.shortCriticalText)
        assertEquals("0/5", snapshot.contentText)
    }

    @Test
    fun `zero tasks stay indeterminate while running`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 0, total = 0),
            statusText = "连接中",
        )
        assertTrue(snapshot.indeterminate)
        assertEquals(0, snapshot.progress)
        assertNull(snapshot.shortCriticalText)
        assertEquals("连接中", snapshot.contentText)
    }

    @Test
    fun `running task never adds guessed partial completion`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(
                done = 2,
                total = 5,
                currentTaskName = "Fight",
                taskLabels = mapOf("Fight" to "作战"),
            ),
            statusText = "正在刷关",
        )
        assertEquals(RunProgressTitle.Running, snapshot.title)
        assertFalse(snapshot.indeterminate)
        assertEquals(400, snapshot.progress)
        assertEquals("2/5", snapshot.shortCriticalText)
        assertEquals("2/5 · 作战 · 正在刷关", snapshot.contentText)
    }

    @Test
    fun `without a current task the bar stays on completed slots`() {
        assertEquals(400, RunProgressSnapshots.progressValue(done = 2, total = 5))
        assertEquals(0, RunProgressSnapshots.progressValue(done = 0, total = 5))
        assertEquals(1000, RunProgressSnapshots.progressValue(done = 5, total = 5))
        assertEquals(1000, RunProgressSnapshots.progressValue(done = 6, total = 5))
        assertEquals(0, RunProgressSnapshots.progressValue(done = -1, total = 5))
    }

    @Test
    fun `missing label falls back to the task name`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 0, total = 3, currentTaskName = "StartUp"),
            statusText = null,
        )
        assertEquals("0/3 · StartUp", snapshot.contentText)
    }

    @Test
    fun `stopping keeps a determinate bar`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Stopping,
            execution = execution(done = 3, total = 4, currentTaskName = "Mall"),
            statusText = null,
        )
        assertEquals(RunProgressTitle.Stopping, snapshot.title)
        assertFalse(snapshot.indeterminate)
        assertEquals(750, snapshot.progress)
        assertEquals("3/4 · Mall", snapshot.contentText)
    }

    @Test
    fun `multiline status keeps the first line`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 1, total = 2),
            statusText = "第一行\n第二行",
        )
        assertEquals("1/2 · 第一行", snapshot.contentText)
    }

    @Test
    fun `blank status is omitted`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 1, total = 1),
            statusText = "  \n  ",
        )
        assertEquals("1/1", snapshot.contentText)
    }

    @Test
    fun `a single long OCR task starts at zero rather than fifty percent`() {
        val snapshot = RunProgressSnapshots.from(
            RunnerPhase.Running,
            execution(done = 0, total = 1, currentTaskName = "Inventory"),
            "识别物品第 20 页",
        )
        assertEquals(0, snapshot.progress)
        assertEquals("0/1 · Inventory · 识别物品第 20 页", snapshot.contentText)
    }

    @Test
    fun `unknown negative total never produces a percentage or invalid clamp`() {
        val snapshot = RunProgressSnapshots.from(
            RunnerPhase.Running,
            execution(done = 2, total = -1, currentTaskName = "Inventory"),
            "翻页中",
        )
        assertTrue(snapshot.indeterminate)
        assertEquals(0, snapshot.progress)
        assertNull(snapshot.shortCriticalText)
        assertEquals("Inventory · 翻页中", snapshot.contentText)
    }

    private fun execution(
        done: Int,
        total: Int,
        currentTaskName: String? = null,
        taskLabels: Map<String, String> = emptyMap(),
    ) = ActiveExecution(
        executionId = "e1",
        runConfigurationId = RunConfigurationId("c1"),
        currentTaskName = currentTaskName,
        completedTaskCount = done,
        totalTaskCount = total,
        taskResults = emptyList(),
        taskLabels = taskLabels,
    )
}
