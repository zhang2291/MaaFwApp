package com.aliothmoon.maafw.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaEmptyState
import com.aliothmoon.maafw.ui.components.maaClickable

/**
 * 任务编辑内层：配置行、排序提示条、任务列表/日志切换、sheet
 */
@Composable
internal fun TaskWorkspace(
    state: SessionUiState,
    runLog: () -> List<RunLogEntry>,
    logOpen: Boolean,
    reorderMode: Boolean,
    onCloseLog: () -> Unit,
    onExitReorder: () -> Unit,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.configurationLocked
    val active = state.activeConfiguration

    var showAddTasks by rememberSaveable { mutableStateOf(false) }
    var editingTaskInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    val effectiveReorderMode = reorderMode && !locked && !logOpen && (active?.tasks?.size ?: 0) > 1

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        ConfigurationSelectorRow(
            active = active,
            locked = locked,
            onSelectConfig = { showConfigSheet = true },
            onAddTasks = { showAddTasks = true },
        )

        AnimatedVisibility(visible = effectiveReorderMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tasks_reorder_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.common_done),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .maaClickable(onClick = onExitReorder)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        AnimatedContent(
            targetState = logOpen,
            transitionSpec = {
                val opening = targetState
                val enter = fadeIn(
                    tween(FadeInMillis, FadeInDelayMillis, MaaMotion.EmphasizedDecelerate),
                ) + slideInVertically(
                    tween(FadeInMillis, FadeInDelayMillis, MaaMotion.EmphasizedDecelerate),
                ) { if (opening) -it / SlideFraction else it / SlideFraction }
                val exit = fadeOut(
                    tween(FadeOutMillis, easing = MaaMotion.EmphasizedAccelerate),
                )
                enter.togetherWith(exit)
            },
            label = "tasksBody",
            modifier = Modifier.weight(1f),
        ) { open ->
            TaskWorkspaceBody(
                logOpen = open,
                active = active,
                locked = locked,
                reorderMode = effectiveReorderMode,
                runLog = runLog,
                onExportLogs = onExportLogs,
                onIntent = onIntent,
                onRequestAddTasks = { showAddTasks = true },
                onEditTask = { editingTaskInstanceId = it },
            )
        }
    }

    if (logOpen) {
        BackHandler { onCloseLog() }
    } else if (effectiveReorderMode) {
        BackHandler { onExitReorder() }
    }

    if (showAddTasks && active != null) {
        AddTasksSheet(
            catalog = state.taskCatalog,
            locked = locked,
            onConfirm = { orderedNames ->
                onIntent(SessionIntent.ConfirmAddTasks(active.id, orderedNames))
                showAddTasks = false
            },
            onDismiss = { showAddTasks = false },
        )
    }

    val editingTask = editingTaskInstanceId?.let { id -> active?.tasks?.firstOrNull { it.instanceId == id } }
    if (editingTask != null && active != null) {
        TaskOptionSheet(
            task = editingTask,
            locked = locked,
            onSetOption = { optionName, value ->
                onIntent(SessionIntent.SetTaskOption(active.id, editingTask.instanceId, optionName, value))
            },
            onDismiss = { editingTaskInstanceId = null },
        )
    }

    if (showConfigSheet) {
        ConfigurationSheet(
            state = state,
            templates = (state.projectState as? ProjectState.Ready)?.definition?.templates.orEmpty(),
            locked = locked,
            onSelect = { id ->
                showConfigSheet = false
                onIntent(SessionIntent.SelectConfiguration(id))
            },
            onDuplicate = { id, name -> onIntent(SessionIntent.DuplicateConfiguration(id, name)) },
            onRename = { id, name -> onIntent(SessionIntent.RenameConfiguration(id, name)) },
            onDelete = { onIntent(SessionIntent.DeleteConfiguration(it)) },
            onCreateEmpty = { name ->
                showConfigSheet = false
                onIntent(SessionIntent.CreateConfiguration(name))
            },
            onCreateFromTemplate = { templateName, configurationName, taskNames ->
                showConfigSheet = false
                onIntent(SessionIntent.CreateFromTemplate(templateName, configurationName, taskNames))
            },
            onDismiss = { showConfigSheet = false },
        )
    }
}

@Composable
private fun TaskWorkspaceBody(
    logOpen: Boolean,
    active: ResolvedRunConfiguration?,
    locked: Boolean,
    reorderMode: Boolean,
    runLog: () -> List<RunLogEntry>,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    onRequestAddTasks: () -> Unit,
    onEditTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        logOpen -> RunLogPanel(
            entries = runLog,
            onExport = onExportLogs,
            onClear = { onIntent(SessionIntent.ClearRunLog) },
            modifier = modifier.fillMaxSize(),
        )

        active == null -> MaaEmptyState(
            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            title = stringResource(R.string.tasks_empty_config_title),
            hint = stringResource(R.string.tasks_empty_config_hint),
            modifier = modifier.fillMaxSize(),
        )

        else -> TaskList(
            active = active,
            locked = locked,
            reorderMode = reorderMode,
            onIntent = onIntent,
            onRequestAddTasks = onRequestAddTasks,
            onEditTask = onEditTask,
            modifier = modifier.fillMaxSize(),
        )
    }
}

private const val SlideFraction = 6
private const val FadeOutMillis = 120
private const val FadeInDelayMillis = 80
private const val FadeInMillis = MaaMotion.DURATION_MEDIUM - FadeInDelayMillis
