package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.*
import java.util.UUID

/** 单独采集仓库，不改写日常配置，也不携带其他任务。 */
object InventoryRunPlanBuilder {
    const val TASK_NAME = "仓库识别"

    fun build(definition: ProjectDefinition, config: UserConfiguration,
              clientVersion: String? = null, clientLanguage: String? = null): RunPlanResult {
        val id = RunConfigurationId("inventory:" + UUID.randomUUID())
        val isolated = config.copy(
            configurations = listOf(RunConfiguration(id, TASK_NAME, listOf(ConfiguredTask(TASK_NAME)))),
            activeConfigurationId = id,
        )
        return RunPlanBuilder.build(definition, isolated, id, clientVersion, clientLanguage)
    }
}
