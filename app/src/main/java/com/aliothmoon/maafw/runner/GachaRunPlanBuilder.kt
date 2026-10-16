package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class GachaCollectionRequest(val categoryId: String, val stopOnKnown: Boolean)

/** Ephemeral configuration: never written to UserConfigurationStore. */
object GachaRunPlanBuilder {
    const val TASK_NAME = "抽卡记录"
    private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")

    fun build(
        definition: ProjectDefinition,
        config: UserConfiguration,
        request: GachaCollectionRequest,
        piRoot: File,
        clientVersion: String? = null,
        clientLanguage: String? = null,
    ): RunPlanResult {
        require(request.categoryId != "legacy" && safeId.matches(request.categoryId)) { "无效的抽卡分类" }
        val id = RunConfigurationId("gacha:" + UUID.randomUUID())
        val isolated = config.copy(
            configurations = listOf(RunConfiguration(id, TASK_NAME, listOf(ConfiguredTask(TASK_NAME)))),
            activeConfigurationId = id,
        )
        val result = RunPlanBuilder.build(definition, isolated, id, clientVersion, clientLanguage)
        if (result !is RunPlanResult.Success) return result
        val plan = result.plan
        var pipeline = JsonObject(emptyMap())
        for (path in plan.resource.paths) {
            val directory = File(piRoot, "$path/pipeline").canonicalFile
            require(directory.toPath().startsWith(piRoot.canonicalFile.toPath())) { "无效资源路径" }
            directory.walkTopDown().onEnter { it == directory || !it.name.startsWith('.') }
                .filter { it.isFile && !it.name.startsWith('.') && it.extension == "json" }
                .sortedBy { it.relativeTo(directory).path }.forEach {
                    pipeline = merge(pipeline, Json.parseToJsonElement(it.readText()) as JsonObject)
                }
        }
        plan.tasks.single().pipelineOverrides.forEach { pipeline = merge(pipeline, it) }
        val patch = collectionPatch(pipeline, request)
        return RunPlanResult.Success(plan.copy(tasks = listOf(plan.tasks.single().copy(
            pipelineOverrides = plan.tasks.single().pipelineOverrides + patch,
        ))))
    }

    /** Preserve the effective complete custom parameter object; custom params are opaque to Maa. */
    internal fun collectionPatch(pipeline: JsonObject, request: GachaCollectionRequest): JsonObject {
        require(request.categoryId != "legacy" && safeId.matches(request.categoryId)) { "无效的抽卡分类" }
        val archive = "record/gacha/" + request.categoryId
        val nodes = pipeline.mapNotNull { (name, value) ->
            val node = value as? JsonObject ?: return@mapNotNull null
            var patched = node
            var changed = false
            for ((stage, kind) in listOf("recognition" to "recognition", "action" to "action")) {
                val block = node[stage] as? JsonObject ?: continue
                val param = block["param"] as? JsonObject ?: continue
                val custom = (param["custom_$kind"] as? JsonPrimitive)?.content ?: continue
                if (!custom.startsWith("GachaRecord")) continue
                val key = "custom_" + kind + "_param"
                val original = param[key] as? JsonObject ?: JsonObject(emptyMap())
                val overrides = mutableMapOf("archive" to JsonPrimitive(archive))
                if (custom == "GachaRecordPage") overrides["stop_on_known"] = JsonPrimitive(request.stopOnKnown)
                if (custom == "GachaRecordExport") overrides["report"] = JsonPrimitive(archive + ".md")
                patched = JsonObject(patched + (stage to JsonObject(block + ("param" to JsonObject(
                    param + (key to JsonObject(original + overrides)),
                )))))
                changed = true
            }
            if (changed) name to patched else null
        }.toMap()
        require(nodes.isNotEmpty()) { "当前资源不包含抽卡采集 Pipeline" }
        return JsonObject(nodes)
    }

    private fun merge(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(base + patch.mapValues { (key, value) ->
        val old = base[key]
        if (old is JsonObject && value is JsonObject) merge(old, value) else value
    })
}
