package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.*
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryCollectionTest {
    private val empty = JsonObject(emptyMap())
    private fun task(name: String) = TaskDefinition(name, name, name, null, emptyList(), emptyList(), empty, emptyList(), emptyList(), true)
    private val definition = ProjectDefinition(
        "demo", "1", ControllerDefinition(), listOf(ResourceDefinition("官服", listOf("resource/base"))),
        listOf(task("日常"), task(InventoryRunPlanBuilder.TASK_NAME)), emptyList(), emptyMap(), templates = emptyList(),
    )
    private val config = UserConfiguration(
        initialized = true, activeResourceName = "官服",
        configurations = listOf(RunConfiguration(RunConfigurationId("daily"), "日常", listOf(
            ConfiguredTask("日常"), ConfiguredTask(InventoryRunPlanBuilder.TASK_NAME),
        ))), activeConfigurationId = RunConfigurationId("daily"),
    )
    @Test fun `isolated plan preserves daily configuration`() {
        val before = config.copy()
        val result = InventoryRunPlanBuilder.build(definition, config) as RunPlanResult.Success
        assertEquals(listOf(InventoryRunPlanBuilder.TASK_NAME), result.plan.tasks.map { it.taskName })
        assertNotEquals(config.activeConfigurationId, result.plan.runConfigurationId)
        assertEquals(before, config)
    }

    private class RecordingRunner : RunnerPort {
        override val state = MutableStateFlow(RunnerState())
        override val events = emptyFlow<RunnerEvent>()
        var stops = 0
        var plan: RunPlan? = null
        override suspend fun start(plan: RunPlan): RunnerCommandResult {
            this.plan = plan
            state.value = RunnerState(RunnerPhase.Running, ActiveExecution("execution", plan.runConfigurationId, null, 0, plan.tasks.size, emptyList()))
            return RunnerCommandResult.Accepted
        }
        override suspend fun stop(): RunnerCommandResult { stops++; return RunnerCommandResult.Accepted }
    }

    @Test fun `collection stop does not stop daily and collection rejects busy runner`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingRunner()
        val store = InMemoryUserConfigurationStore(config)
        val launcher = RunLauncher(FakeProjectRepository(ProjectState.Ready(definition, emptyList())), store,
            runner, emptyList(), emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal)
        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        assertEquals(listOf("日常"), runner.plan!!.tasks.map { it.taskName })
        launcher.stopInventory()
        assertEquals(0, runner.stops)
        assertTrue(launcher.launch(RunTrigger.Manual, inventory = true) is RunLaunchResult.Blocked)
        assertEquals(0, runner.stops)
    }

    @Test fun `collection owns stop and cannot reenter or stop a replacement run`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingRunner()
        val store = InMemoryUserConfigurationStore(config)
        val launcher = RunLauncher(FakeProjectRepository(ProjectState.Ready(definition, emptyList())), store,
            runner, emptyList(), emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal)
        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual, inventory = true))
        assertNotNull(launcher.inventoryOwner.value)
        assertTrue(launcher.launch(RunTrigger.Manual, inventory = true) is RunLaunchResult.Blocked)
        launcher.stopGacha()
        assertEquals(0, runner.stops)
        launcher.stopInventory()
        assertEquals(1, runner.stops)
        runner.state.value = runner.state.value.copy(activeExecution = runner.state.value.activeExecution!!.copy(runConfigurationId = RunConfigurationId("daily")))
        launcher.stopInventory()
        assertEquals(1, runner.stops)
        assertEquals(config, store.current)
    }

    @Test fun `stop cancels its own pending precheck without dispatching or cancelling intent consumer`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingRunner()
        val store = InMemoryUserConfigurationStore(config)
        val entered = CompletableDeferred<Unit>()
        val launcher = RunLauncher(FakeProjectRepository(ProjectState.Ready(definition, emptyList())), store,
            runner, listOf(RunPrecheck { entered.complete(Unit); CompletableDeferred<Verdict>().await() }),
            emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal)
        val starting = async { launcher.launch(RunTrigger.Manual, inventory = true) }
        entered.await()
        assertNotNull(launcher.inventoryOwner.value)
        launcher.stopInventory()
        assertTrue(starting.isCancelled)
        assertNull(launcher.inventoryOwner.value)
        assertNull(runner.plan)
        assertEquals(0, runner.stops)
        assertEquals(config, store.current)
    }
}
