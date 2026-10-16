package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.*
import com.aliothmoon.maafw.runner.GachaCollectionRequest
import com.aliothmoon.maafw.runner.GachaRunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual MBCCtools PI execution chain, with a byte-for-byte snapshot for standalone CI. */
class MbccGachaProjectRegressionTest {
    private val root: File = listOf(File("../../MBCCtools"), File("../MBCCtools"))
        .firstOrNull { File(it, "interface.json").isFile }
        ?: File("src/test/fixtures/PI/MBCCtools")
    private val text = object : PiTextResolver {
        override fun label(raw: String?) = raw
        override fun description(raw: String?) = raw
    }
    private fun load(): ProjectLoadResult.Ready {
        println("MBCCtools regression PI source: " + root.canonicalPath)
        val loaded = ProjectLoader(DirectoryProjectSource(root)).load()
        assertTrue("Actual PI should load: $loaded", loaded is ProjectLoadResult.Ready)
        return loaded as ProjectLoadResult.Ready
    }
    private fun config(vararg tasks: ConfiguredTask): UserConfiguration {
        val id = RunConfigurationId("existing-daily")
        return UserConfiguration(initialized = true, activeResourceName = "官服",
            configurations = listOf(RunConfiguration(id, "日常", tasks.toList())), activeConfigurationId = id)
    }
    private fun custom(patch: JsonObject) = patch.getValue("抽卡记录_单页识别").jsonObject
        .getValue("recognition").jsonObject.getValue("param").jsonObject.getValue("custom_recognition_param").jsonObject

    @Test fun `real PI retains missing-type option and its first-case default through loader and planner`() {
        val raw = File(root, "interface.json").readText()
        val parsedRoot = PiParser.parseInterface("interface.json", raw).root!!
        val parsed = PiParser.parseFile("interface.json", parsedRoot, text)
        val option = parsed.options["识别方式"]
        assertTrue("Omitted type must become select, diagnostics=${parsed.diagnostics}", option is OptionDefinition.Select)
        assertEquals("完整识别", (option as OptionDefinition.Select).effectiveDefaultCase)
        assertEquals(listOf("完整识别", "识别到已知停止"), option.cases.map { it.name })
        val rawOptions = parsedRoot.getValue("option").jsonObject
        val explicitSelect = JsonObject(parsedRoot + ("option" to JsonObject(rawOptions + ("识别方式" to JsonObject(
            rawOptions.getValue("识别方式").jsonObject + ("type" to JsonPrimitive("select")),
        )))))
        assertEquals("An omitted select type must not change any other option",
            PiParser.parseFile("interface.json", explicitSelect, text).options, parsed.options)
        val loaded = load()
        assertEquals(parsedRoot.getValue("option").jsonObject.keys, loaded.definition.options.keys)
        assertTrue("No option reference errors: ${loaded.diagnostics}", loaded.diagnostics.none { it.severity == DiagnosticSeverity.Error })
        val result = RunPlanBuilder.build(loaded.definition, config(ConfiguredTask("抽卡记录")))
        assertTrue("Real PI gacha plan must compile: $result", result is RunPlanResult.Success)
        assertEquals(listOf("抽卡记录"), (result as RunPlanResult.Success).plan.tasks.map { it.taskName })
    }

    @Test fun `real PI collection honors full and incremental without changing other custom fields or daily config`() {
        val loaded = load()
        val daily = config(ConfiguredTask("进入游戏"))
        val before = daily.copy()
        val basePlan = RunPlanBuilder.build(loaded.definition, config(ConfiguredTask("抽卡记录"))) as RunPlanResult.Success
        val original = custom(basePlan.plan.tasks.single().pipelineOverrides.last())
        for (incremental in listOf(false, true)) {
            val result = GachaRunPlanBuilder.build(loaded.definition, daily,
                GachaCollectionRequest("account_test", incremental), root)
            assertTrue("Collection must compile: $result", result is RunPlanResult.Success)
            val task = (result as RunPlanResult.Success).plan.tasks.single()
            assertEquals("抽卡记录", task.taskName)
            val actual = custom(task.pipelineOverrides.last())
            assertEquals(JsonPrimitive(incremental), actual["stop_on_known"])
            assertEquals(JsonPrimitive("record/gacha/account_test"), actual["archive"])
            assertEquals(original - setOf("archive", "stop_on_known"), actual - setOf("archive", "stop_on_known"))
        }
        assertEquals(before, daily)
        val normal = RunPlanBuilder.build(loaded.definition, daily)
        assertTrue(normal is RunPlanResult.Success)
        assertEquals(listOf("进入游戏"), (normal as RunPlanResult.Success).plan.tasks.map { it.taskName })
        val withOptions = RunPlanBuilder.build(loaded.definition, config(ConfiguredTask("夜莺助手快捷任务跳转")))
        assertTrue("Existing daily options must still compile: $withOptions", withOptions is RunPlanResult.Success)
        assertEquals(listOf("夜莺助手快捷任务跳转"), (withOptions as RunPlanResult.Success).plan.tasks.map { it.taskName })
    }

    @Test fun `explicit unsupported option type remains invalid instead of becoming select`() {
        val original = PiParser.parseInterface("interface.json", File(root, "interface.json").readText()).root!!
        val options = original.getValue("option").jsonObject
        val changed = JsonObject(original + ("option" to JsonObject(options + ("识别方式" to JsonObject(
            options.getValue("识别方式").jsonObject + ("type" to JsonPrimitive("future_type")),
        )))))
        val parsed = PiParser.parseFile("interface.json", changed, text)
        assertFalse("Explicit unknown types must still fail", "识别方式" in parsed.options)
        assertTrue(parsed.diagnostics.any { it.severity == DiagnosticSeverity.Error })
        assertEquals(options.keys - "识别方式", parsed.options.keys)
    }
}
