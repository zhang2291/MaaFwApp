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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GachaCollectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val empty = JsonObject(emptyMap())
    private val pipeline = Json.parseToJsonElement("""{
      "开始":{"action":{"type":"Custom","param":{"custom_action":"GachaRecordBeginRun","custom_action_param":{"archive":"record/抽卡记录"}}}},
      "页面":{"recognition":{"type":"Custom","param":{"custom_recognition":"GachaRecordPage","custom_recognition_param":{"archive":"record/抽卡记录","roi":[1,2,3,4],"badge_x":[856,908],"rarity_dir":"抽卡","rarity_tiers":["狂","危","普"],"gate_roi":[4,5,6,7],"page_roi":[8,9,10,11],"min_score":0.5,"line_tol":14,"max_pages":200}}}},
      "汇总":{"action":{"type":"Custom","param":{"custom_action":"GachaRecordExport","custom_action_param":{"archive":"record/抽卡记录","custom_value":"preserve"}}}}
    }""") as JsonObject
    private fun task(name: String) = TaskDefinition(name, name, name, null, emptyList(), emptyList(), empty, emptyList(), emptyList(), true)
    private val definition = ProjectDefinition(
        "demo", "1", ControllerDefinition(), listOf(ResourceDefinition("官服", listOf("resource/base"))),
        listOf(task("日常"), task(GachaRunPlanBuilder.TASK_NAME)), emptyList(), emptyMap(), templates = emptyList(),
    )
    private val config = UserConfiguration(
        initialized = true, activeResourceName = "官服",
        configurations = listOf(RunConfiguration(RunConfigurationId("daily"), "日常", listOf(
            ConfiguredTask("日常"), ConfiguredTask(GachaRunPlanBuilder.TASK_NAME),
        ))), activeConfigurationId = RunConfigurationId("daily"),
    )
    private fun piRoot(): File = temporary.newFolder().also { root ->
        File(root, "resource/base/pipeline").mkdirs()
        File(root, "resource/base/pipeline/gacha.json").writeText(pipeline.toString())
    }
    private fun params(patch: JsonObject, node: String, stage: String, kind: String) =
        patch.getValue(node).jsonObject.getValue(stage).jsonObject.getValue("param").jsonObject
            .getValue("custom_" + kind + "_param").jsonObject

    @Test fun `collection has only one task and never mutates daily configuration`() {
        val before = config.copy()
        val result = GachaRunPlanBuilder.build(definition, config, GachaCollectionRequest("account_a", true), piRoot()) as RunPlanResult.Success
        assertEquals(listOf(GachaRunPlanBuilder.TASK_NAME), result.plan.tasks.map { it.taskName })
        assertNotEquals(config.activeConfigurationId, result.plan.runConfigurationId)
        assertEquals(before, config)
        val patch = result.plan.tasks.single().pipelineOverrides.last()
        val original = params(pipeline, "页面", "recognition", "recognition")
        val actual = params(patch, "页面", "recognition", "recognition")
        assertEquals(original - "archive", actual - setOf("archive", "stop_on_known"))
        assertEquals(JsonPrimitive(true), actual["stop_on_known"])
        assertEquals(JsonPrimitive("record/gacha/account_a"), actual["archive"])
        assertEquals(JsonPrimitive("record/gacha/account_a.md"), params(patch, "汇总", "action", "action")["report"])
    }

    @Test fun `full scan explicitly overrides incremental false`() {
        val patch = GachaRunPlanBuilder.collectionPatch(pipeline, GachaCollectionRequest("a", false))
        assertEquals(JsonPrimitive(false), params(patch, "页面", "recognition", "recognition")["stop_on_known"])
    }

    @Test fun `hidden pipeline files cannot override live collection parameters`() {
        val root = piRoot()
        val directory = File(root, "resource/base/pipeline")
        val hidden = """{"页面":{"recognition":{"param":{"custom_recognition_param":{"max_pages":1}}}}}"""
        File(directory, ".override.json").writeText(hidden)
        File(directory, ".hidden").mkdirs()
        File(directory, ".hidden/override.json").writeText(hidden)
        val result = GachaRunPlanBuilder.build(definition, config, GachaCollectionRequest("a", false), root) as RunPlanResult.Success
        assertEquals(JsonPrimitive(200), params(result.plan.tasks.single().pipelineOverrides.last(), "页面", "recognition", "recognition")["max_pages"])
    }

    @Test fun `unsafe and legacy destinations fail closed`() {
        for (id in listOf("legacy", "../escape", "a/b", "", "a\\b", ".", "_hidden", "a".repeat(81))) {
            try {
                GachaRunPlanBuilder.build(definition, config, GachaCollectionRequest(id, false), piRoot())
                fail("accepted unsafe destination: " + id)
            } catch (_: IllegalArgumentException) { }
        }
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
            runner, emptyList(), emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal, { piRoot() })
        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual))
        assertEquals(listOf("日常"), runner.plan!!.tasks.map { it.taskName })
        launcher.stopGacha()
        assertEquals(0, runner.stops)
        assertTrue(launcher.launch(RunTrigger.Manual, gacha = GachaCollectionRequest("a", false)) is RunLaunchResult.Blocked)
        assertEquals(0, runner.stops)
    }

    @Test fun `collection owns stop and cannot reenter or stop a replacement run`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingRunner()
        val store = InMemoryUserConfigurationStore(config)
        val root = piRoot()
        val launcher = RunLauncher(FakeProjectRepository(ProjectState.Ready(definition, emptyList())), store,
            runner, emptyList(), emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal, { root })
        assertEquals(RunLaunchResult.Started, launcher.launch(RunTrigger.Manual, gacha = GachaCollectionRequest("a", true)))
        assertNotNull(launcher.gachaOwner.value)
        assertTrue(launcher.launch(RunTrigger.Manual, gacha = GachaCollectionRequest("a", false)) is RunLaunchResult.Blocked)
        launcher.stopGacha()
        assertEquals(1, runner.stops)
        runner.state.value = runner.state.value.copy(activeExecution = runner.state.value.activeExecution!!.copy(runConfigurationId = RunConfigurationId("daily")))
        launcher.stopGacha()
        assertEquals(1, runner.stops)
        assertEquals(config, store.current)
    }

    @Test fun `stop cancels its own pending precheck without dispatching or cancelling intent consumer`() = runTest(UnconfinedTestDispatcher()) {
        val runner = RecordingRunner()
        val store = InMemoryUserConfigurationStore(config)
        val root = piRoot()
        val entered = CompletableDeferred<Unit>()
        val launcher = RunLauncher(FakeProjectRepository(ProjectState.Ready(definition, emptyList())), store,
            runner, listOf(RunPrecheck { entered.complete(Unit); CompletableDeferred<Verdict>().await() }),
            emptyList(), { RunMode.BACKGROUND }, backgroundScope, DiscardingRunJournal, { root })
        val starting = async { launcher.launch(RunTrigger.Manual, gacha = GachaCollectionRequest("a", false)) }
        entered.await()
        assertNotNull(launcher.gachaOwner.value)
        launcher.stopGacha()
        assertTrue(starting.isCancelled)
        assertNull(launcher.gachaOwner.value)
        assertNull(runner.plan)
        assertEquals(0, runner.stops)
        assertEquals(config, store.current)
    }
}
