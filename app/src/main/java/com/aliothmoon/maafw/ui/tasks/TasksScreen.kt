package com.aliothmoon.maafw.ui.tasks

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.ui.components.MaaInsetSegmentedControl
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.maaCardBorder
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.gacha.GachaScreen
import com.aliothmoon.maafw.ui.inventory.InventoryScreen
import com.aliothmoon.maafw.ui.pip.LocalIsInPip
import com.aliothmoon.maafw.ui.pip.PipController
import com.aliothmoon.maafw.ui.pip.PipHost
import com.aliothmoon.maafw.ui.pip.PipRequest

/**
 * [previewContent] 由 AppRoot 创建并持有：全屏宿主必须在 pager 之外才能盖住底部 tab 栏，
 * 而 movableContent 要求两处调用点在同一棵组合树里，所以整份预览面的所有权提到了顶层
 * 为 null 表示画面已搬去全屏，这里显示占位
 */
@Composable
fun TasksScreen(
    state: SessionUiState,
    previewSurfaceReady: Boolean,
    previewContent: (@Composable () -> Unit)?,
    /** 用户停留的页是否是本页；画中画只在该页武装（pager 会预组合相邻页，组合≠可见） */
    isActivePage: Boolean,
    pipOnHome: Boolean,
    /** 取值而不是值：日志面板没开时这一层不该跟着日志频率重组 */
    runLog: () -> List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = state.projectState,
        animationSpec = MaaMotion.enter(),
        modifier = modifier,
    ) { projectState ->
        when (projectState) {
            is ProjectState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is ProjectState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.xl),
                    )
                    Text(stringResource(R.string.tasks_project_load_failed), color = MaterialTheme.colorScheme.error)
                    MaaOutlinedButton(onClick = { onIntent(SessionIntent.ReloadProject) }) {
                        Text(stringResource(R.string.tasks_reload))
                    }
                }
            }

            is ProjectState.Ready -> TasksContent(
                state = state,
                previewSurfaceReady = previewSurfaceReady,
                previewContent = previewContent,
                isActivePage = isActivePage,
                pipOnHome = pipOnHome,
                runLog = runLog,
                onEnterFullscreen = onEnterFullscreen,
                onExportLogs = onExportLogs,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun TasksContent(
    state: SessionUiState,
    previewSurfaceReady: Boolean,
    previewContent: (@Composable () -> Unit)?,
    isActivePage: Boolean,
    pipOnHome: Boolean,
    runLog: () -> List<RunLogEntry>,
    onEnterFullscreen: () -> Unit,
    onExportLogs: () -> Unit,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQuickOptions by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf("tasks") } // "tasks" or "tools"
    var selectedTool by rememberSaveable { mutableStateOf("inventory") } // "inventory" or "gacha"
    var previewManuallyExpanded by rememberSaveable { mutableStateOf(false) }
    var showRunLog by rememberSaveable { mutableStateOf(false) }
    var reorderMode by rememberSaveable { mutableStateOf(false) }

    val activeTasks = state.activeConfiguration?.tasks.orEmpty()
    val enabledTaskCount = activeTasks.count { it.enabled }
    val canReorder = !state.configurationLocked && !showRunLog && activeTasks.size > 1
    val effectiveReorderMode = reorderMode && canReorder
    val isPreviewRunning = state.runner.phase.isBusy || state.gachaCollecting || state.inventoryCollecting
    val showFullPreview = isPreviewRunning || previewManuallyExpanded

    val pipActive = LocalIsInPip.current
    val context = LocalContext.current
    val pipHost = context as? PipHost
    val previewResolution = state.previewResolution
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    val pipEligible = pipOnHome &&
        isActivePage &&
        previewContent != null &&
        previewResolution != null &&
        state.runMode == RunMode.BACKGROUND &&
        state.runner.phase.isBusy &&
        previewSurfaceReady &&
        pipHost != null &&
        PipController.isSupported(context)
    DisposableEffect(pipHost, pipEligible, previewResolution, previewBounds) {
        fun arm(enabled: Boolean, sourceRect: Rect?) {
            val host = pipHost ?: return
            val activity = host as? android.app.Activity ?: return
            val resolution = previewResolution ?: return
            val request = PipRequest(resolution, sourceRect)
            host.pipRequest = if (enabled) request else null
            PipController.updateParams(activity, enabled, request)
        }
        arm(pipEligible, previewBounds)
        onDispose { arm(enabled = false, sourceRect = null) }
    }

    if (currentTab == "tools") {
        BackHandler { currentTab = "tasks" }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(),
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaaDesignTokens.Spacing.lg),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = MaaDesignTokens.Spacing.md)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    // 顶栏：左侧内凹分段切换（任务 | 小工具），右侧「排序」「日志」快捷胶囊
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaaInsetSegmentedControl(
                            options = listOf(
                                "tasks" to stringResource(R.string.tasks_tab_tasks),
                                "tools" to stringResource(R.string.tasks_tab_tools),
                            ),
                            selected = currentTab,
                            onSelect = { tab ->
                                currentTab = tab
                                if (tab != "tasks") reorderMode = false
                            },
                        )

                        if (currentTab == "tasks") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TopActionPill(
                                    label = stringResource(
                                        if (effectiveReorderMode) R.string.common_done else R.string.tasks_reorder,
                                    ),
                                    active = effectiveReorderMode,
                                    enabled = canReorder,
                                    onClick = { reorderMode = !effectiveReorderMode },
                                )
                                TopActionPill(
                                    label = stringResource(
                                        if (showRunLog) R.string.common_close else R.string.tasks_log,
                                    ),
                                    active = showRunLog,
                                    enabled = true,
                                    onClick = {
                                        showRunLog = !showRunLog
                                        if (showRunLog) reorderMode = false
                                    },
                                )
                            }
                        }
                    }

                    // 实时预览区：运行或手动展开时显示 LivePreview，空闲时显示紧凑状态胶囊
                    if (showFullPreview) {
                        LivePreview(
                            resolution = state.previewResolution,
                            surfaceReady = previewSurfaceReady,
                            running = isPreviewRunning,
                            watchdogState = state.watchdogState,
                            content = previewContent.takeUnless { pipActive },
                            onEnterFullscreen = onEnterFullscreen,
                            onCollapse = if (!isPreviewRunning) {
                                { previewManuallyExpanded = false }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(176.dp),
                            onBoundsChanged = { if (!pipActive) previewBounds = it },
                        )
                    } else {
                        IdlePreviewCapsule(
                            serviceStatus = state.serviceStatus,
                            enabledTaskCount = enabledTaskCount,
                            totalTaskCount = activeTasks.size,
                            onExpand = { previewManuallyExpanded = true },
                        )
                    }

                    if (currentTab == "tools") {
                        MaaInsetSegmentedControl(
                            options = listOf(
                                "inventory" to stringResource(R.string.tasks_tool_inventory),
                                "gacha" to stringResource(R.string.tasks_tool_gacha),
                            ),
                            selected = selectedTool,
                            onSelect = { selectedTool = it },
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            if (selectedTool == "inventory") {
                                InventoryScreen(
                                    running = state.configurationLocked || state.gachaCollecting || state.inventoryCollecting || state.runner.phase.isBusy,
                                    collecting = state.inventoryCollecting,
                                    onStart = { onIntent(SessionIntent.StartInventoryCollection) },
                                    onStop = { onIntent(SessionIntent.StopInventoryCollection) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                GachaScreen(
                                    running = state.configurationLocked || state.gachaCollecting || state.inventoryCollecting || state.runner.phase.isBusy,
                                    collecting = state.gachaCollecting,
                                    onStart = { categoryId, stopOnKnown ->
                                        onIntent(SessionIntent.StartGachaCollection(categoryId, stopOnKnown))
                                    },
                                    onStop = { onIntent(SessionIntent.StopGachaCollection) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        TaskWorkspace(
                            state = state,
                            runLog = runLog,
                            logOpen = showRunLog,
                            reorderMode = effectiveReorderMode,
                            onCloseLog = { showRunLog = false },
                            onExitReorder = { reorderMode = false },
                            onExportLogs = onExportLogs,
                            onIntent = onIntent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (currentTab == "tasks") {
                    Spacer(Modifier.height(MaaDesignTokens.Spacing.xs))
                    RunnerToggleButton(
                        state = state,
                        quickOptionsOpen = showQuickOptions,
                        onIntent = onIntent,
                        onToggleQuickOptions = { showQuickOptions = !showQuickOptions },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (showQuickOptions) {
            BackHandler { showQuickOptions = false }
            TasksQuickOptionsPanel(
                state = state,
                onIntent = onIntent,
                onDismiss = { showQuickOptions = false },
            )
        }
    }
}

@Composable
private fun TopActionPill(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (active) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            maaCardBorder()
        },
        modifier = Modifier
            .clip(shape)
            .alpha(if (enabled) 1f else MaaDesignTokens.Alpha.disabled)
            .maaClickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}
