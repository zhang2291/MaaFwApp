package com.aliothmoon.maafw.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.privileged.SystemPermission
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.ServiceStatus
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.session.StatusTone
import com.aliothmoon.maafw.settings.ResourceUpdatePanelState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaIconBadge
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaPulsingDot
import com.aliothmoon.maafw.ui.components.MaaSemanticOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.maaCardBorder
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.i18n.diagnosticsSummaryUiText

/**
 * 精密极简工业首页：
 * 顶栏（应用名 + 服务器徽标 + 呼吸状态灯）
 * -> 自动化引擎服务 Hero 主卡
 * -> 今日执行任务队列速览卡
 * -> 权限管理卡（后台 4 项精简，已修复系统页兜底拉起）
 * -> 多服务器切换（>1 个时显示）
 * -> 版本与资源更新卡
 * -> 诊断卡（仅异常时显示）
 * -> MBCC 终端暗纹铭牌
 */
@Composable
fun HomeScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    update: UpdatePanelState,
    resourceUpdate: ResourceUpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
    onNavigateToTasks: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    Column(modifier = modifier.fillMaxSize()) {
        HomeHeader(
            appLabel = appLabel,
            resourceLabel = state.environment?.resource?.label,
            status = state.serviceStatus,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.xs,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            HeroServiceCard(
                state = state,
                onIntent = onIntent,
                onNavigateToTasks = onNavigateToTasks,
            )
            ActiveQueueCard(
                state = state,
                onIntent = onIntent,
                onNavigateToTasks = onNavigateToTasks,
            )
            PermissionsCard(state, onIntent)
            if ((state.environment?.resourceCandidates?.size ?: 0) > 1) {
                ResourceCard(state, onIntent)
            }
            UnifiedUpdatesCard(
                update = update,
                resourceUpdate = resourceUpdate,
                onSettingsIntent = onSettingsIntent,
            )
            ProjectDiagnosticsCard(state)
            TerminalFooterPlate()
        }
    }
}

@Composable
private fun HomeHeader(
    appLabel: String,
    resourceLabel: String?,
    status: ServiceStatus,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaaDesignTokens.Spacing.lg,
                end = MaaDesignTokens.Spacing.lg,
                top = MaaDesignTokens.Spacing.md,
                bottom = MaaDesignTokens.Spacing.xs,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = maaCardBorder(),
                ) {
                    Text(
                        text = resourceLabel ?: "官服",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "AUTOMATION CONSOLE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = maaCardBorder(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val dotColor = when (status.tone) {
                    StatusTone.Primary -> MaterialTheme.colorScheme.primary
                    StatusTone.Warning -> MaaTheme.palette.warning.content
                    StatusTone.Error -> MaterialTheme.colorScheme.error
                    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                MaaPulsingDot(color = dotColor, pulse = status.busy)
                Text(
                    text = status.text.asString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun HeroServiceCard(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onNavigateToTasks: () -> Unit,
) {
    val connected = state.privilegedServiceConnected

    MaaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (connected) "自动化引擎服务" else "特权服务未连接",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        connected -> "通道稳定，可随时前往任务页执行"
                        else -> "需要 Shizuku 授权以创建虚拟显示屏"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val badgeTone = if (connected) MaaTheme.palette.success else MaaTheme.palette.warning
            MaaIconBadge(
                icon = if (connected) Icons.Outlined.Check else Icons.Outlined.Bolt,
                containerColor = badgeTone.container,
                contentColor = badgeTone.content,
                containerSize = 40.dp,
            )
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connected) {
                MaaSemanticOutlinedButton(
                    onClick = { onIntent(SessionIntent.TogglePrivilegedService) },
                    semantic = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.home_service_disconnect), maxLines = 1)
                }
                MaaButton(
                    onClick = onNavigateToTasks,
                    modifier = Modifier.weight(1.2f),
                ) {
                    Text("去执行 →", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            } else {
                MaaButton(
                    onClick = { onIntent(SessionIntent.TogglePrivilegedService) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.home_service_connect), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 首页执行队列概览卡片：
 * 展示当前配置名、已勾选的任务胶囊流、就绪统计与直接开始按钮，
 * 让用户在首页就能一眼看清今天要执行的任务并直接启动，充实页面布局。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveQueueCard(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onNavigateToTasks: () -> Unit,
) {
    val activeConfig = state.activeConfiguration ?: return
    val enabledTasks = activeConfig.tasks.filter { it.enabled }
    val totalCount = activeConfig.tasks.size
    val isBusy = state.runner.phase.isBusy

    MaaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "今日执行队列",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "配置：${activeConfig.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${enabledTasks.size}/$totalCount 项就绪",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (enabledTasks.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (enabledTasks.isEmpty()) {
            Text(
                text = "暂无启用的任务，请前往任务页勾选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                enabledTasks.forEachIndexed { index, task ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = maaCardBorder(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            )
                            Text(
                                text = task.label,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaaOutlinedButton(
                onClick = onNavigateToTasks,
                modifier = Modifier.weight(1f),
            ) {
                Text("配置任务 →", maxLines = 1)
            }
            MaaButton(
                onClick = {
                    if (isBusy) {
                        onIntent(SessionIntent.Stop)
                    } else {
                        onIntent(SessionIntent.Start())
                    }
                },
                enabled = isBusy || state.canStart,
                colors = if (isBusy) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1.3f),
            ) {
                Icon(
                    imageVector = if (isBusy) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isBusy) "停止任务" else "一键开始",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 后台权限管理：保留后台静默运行真正需要的 4 项，告别无用悬浮窗/无障碍骚扰 */
@Composable
private fun PermissionsCard(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
) {
    var permsExpanded by remember { mutableStateOf(false) }
    val perms = state.systemPermissions
    val grantedCount = listOf(
        state.remoteAccessGranted,
        perms.batteryWhitelist,
        perms.notification,
        perms.storage,
    ).count { it }
    val totalCount = 4
    val allGranted = grantedCount == totalCount

    val requestText = stringResource(
        if (state.privilegedServiceConnected) R.string.permission_quick_grant
        else R.string.permission_request,
    )

    MaaCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .maaClickable(indication = false) { permsExpanded = !permsExpanded }
                .padding(vertical = MaaDesignTokens.Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.permission_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                Text(
                    text = stringResource(
                        if (allGranted) R.string.home_permissions_ready
                        else R.string.home_permissions_incomplete,
                        grantedCount,
                        totalCount,
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (allGranted) MaaTheme.palette.success.content else MaaTheme.palette.warning.content,
                )
                Icon(
                    imageVector = if (permsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
        }

        AnimatedVisibility(visible = permsExpanded) {
            Column(modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs)) {
                PermissionRow(
                    label = state.remoteAccess.configuredBackend.display,
                    granted = state.remoteAccessGranted,
                    loading = state.remoteAccessGranting,
                    onRequest = { onIntent(SessionIntent.RequestRemoteAccess) },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_battery),
                    granted = perms.batteryWhitelist,
                    grantedText = stringResource(R.string.permission_battery_added),
                    requestText = requestText,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.BatteryWhitelist))
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_notification),
                    granted = perms.notification,
                    requestText = requestText,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Notification))
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.permission_storage),
                    granted = perms.storage,
                    requestText = requestText,
                    onRequest = {
                        onIntent(SessionIntent.RequestSystemPermission(SystemPermission.Storage))
                    },
                )
                Text(
                    text = stringResource(R.string.permission_keepalive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit,
    loading: Boolean = false,
    grantedText: String = stringResource(R.string.permission_granted),
    requestText: String = stringResource(R.string.permission_request),
) {
    MaaLabeledControlRow(
        modifier = Modifier.height(MaaDesignTokens.RowHeight.compact),
        label = label,
        labelStyle = MaterialTheme.typography.bodyMedium,
        trailing = {
            TextButton(
                onClick = onRequest,
                enabled = !granted && !loading,
                modifier = Modifier.height(MaaDesignTokens.RowHeight.compact),
                contentPadding = PaddingValues(horizontal = MaaDesignTokens.Spacing.sm),
                colors = ButtonDefaults.textButtonColors(
                    disabledContentColor = MaaTheme.palette.success.content,
                ),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                        strokeWidth = MaaDesignTokens.Border.marker,
                    )
                } else {
                    Text(if (granted) grantedText else requestText)
                }
            }
        },
    )
}

/** 项目加载失败或有诊断时才出现；正常态不占版面 */
@Composable
private fun ProjectDiagnosticsCard(state: SessionUiState) {
    val project = state.projectState
    val diagnostics = state.visibleDiagnostics
    if (project is ProjectState.Ready && diagnostics.isEmpty()) return
    MaaCard(title = stringResource(R.string.home_project)) {
        when (project) {
            is ProjectState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(MaaDesignTokens.Spacing.xs))
                Text(
                    stringResource(R.string.home_project_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ProjectState.Error -> {
                Text(
                    text = stringResource(R.string.home_project_load_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                MaaDiagnosticList(project.diagnostics.take(5))
            }

            is ProjectState.Ready -> {
                val errors = diagnostics.count { it.severity == DiagnosticSeverity.Error }
                Text(
                    text = diagnosticsSummaryUiText(diagnostics.size, errors).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errors > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 仅存在多个资源/服务器候选时才显示 */
@Composable
private fun ResourceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(
        title = stringResource(R.string.settings_resource),
        collapsible = true,
        initiallyExpanded = false,
        summary = state.environment?.resource?.label,
    ) {
        val environment = state.environment
        val candidates = environment?.resourceCandidates.orEmpty()
        MaaSingleChoiceFlow(
            options = candidates.map { it.name to it.label },
            selected = environment?.resource?.name ?: "",
            enabled = !state.configurationLocked,
            onSelect = { onIntent(SessionIntent.SelectResource(it)) },
        )
    }
}

@Composable
private fun TerminalFooterPlate() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "MINOS BUREAU OF CRISIS CONTROL",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        Text(
            text = "DISCARDS & SINNERS AUTOMATION PROTOCOL",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}
