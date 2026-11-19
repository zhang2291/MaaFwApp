package com.aliothmoon.maafw.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.runner.screenSize
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaInsetSegmentedControl
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaMarkdownSheet
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import com.aliothmoon.maafw.ui.components.MaaSectionHeader
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaSwitchRow
import com.aliothmoon.maafw.ui.options.OptionEditorList
import com.aliothmoon.maafw.ui.pip.PipController
import timber.log.Timber

/**
 * 精简分组设置页（4 组清晰结构，移除无用的 Semi 主题风格与半成品语言切换）
 */
@Composable
fun SettingsScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    settingsState: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
    onOpenRunLogArchive: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.md,
                    bottom = MaaDesignTokens.Spacing.sm,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "PREFERENCES",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.xs,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            // 1. 外观与更新
            AppearanceAndUpdateGroup(state, settingsState, onIntent, onSettingsIntent)

            // 动态 PI 选项（仅在 interface.json 声明时出现）
            if (state.globalOptions.isNotEmpty()) {
                MaaCard(title = stringResource(R.string.settings_section_global_option), collapsible = true) {
                    OptionEditorList(
                        options = state.globalOptions,
                        locked = state.configurationLocked,
                        onSetOption = { name, value -> onIntent(SessionIntent.SetGlobalOption(name, value)) },
                    )
                }
            }
            if (state.resourceOptions.isNotEmpty()) {
                state.environment?.resource?.name?.let { resourceName ->
                    MaaCard(title = stringResource(R.string.settings_section_resource_option), collapsible = true) {
                        OptionEditorList(
                            options = state.resourceOptions,
                            locked = state.configurationLocked,
                            onSetOption = { name, value ->
                                onIntent(SessionIntent.SetResourceOption(resourceName, name, value))
                            },
                        )
                    }
                }
            }

            // 2. 运行与自动化
            RuntimeAndAutomationGroup(
                state = state,
                settingsState = settingsState,
                onIntent = onIntent,
                onSettingsIntent = onSettingsIntent,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )

            // 3. 日志与维护
            LogsAndMaintenanceGroup(
                state = state,
                onIntent = onIntent,
                onOpenRunLogArchive = onOpenRunLogArchive,
                onOpenAppLog = onOpenAppLog,
                onExportLogs = onExportLogs,
            )

            // 4. 关于
            AboutGroup(state)
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        thickness = MaaDesignTokens.Separator.thickness,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.xxs),
    )
}

@Composable
private fun AppearanceAndUpdateGroup(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val update = settingsState.update
    val settingsEnabled = !update.downloading
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        MaaSectionHeader(title = stringResource(R.string.settings_group_appearance_update))
        MaaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge,
                )
                MaaInsetSegmentedControl(
                    options = listOf(
                        ThemeMode.System to stringResource(R.string.settings_follow_system),
                        ThemeMode.Light to stringResource(R.string.settings_theme_light),
                        ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                    ),
                    selected = state.themeMode,
                    onSelect = { onIntent(SessionIntent.SetThemeMode(it)) },
                )
            }
            GroupDivider()
            MaaSwitchRow(
                label = stringResource(R.string.settings_update_auto_check),
                checked = update.autoCheckUpdate,
                enabled = settingsEnabled,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoCheckUpdate(it)) },
            )
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
                MaaSwitchRow(
                    label = stringResource(R.string.settings_update_auto_download),
                    checked = update.autoDownloadUpdate,
                    enabled = update.autoCheckUpdate && settingsEnabled,
                    onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoDownloadUpdate(it)) },
                )
                Text(
                    text = stringResource(R.string.settings_update_auto_download_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuntimeAndAutomationGroup(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val locked = state.configurationLocked
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        MaaSectionHeader(title = stringResource(R.string.settings_group_runtime_automation))
        MaaCard {
            MaaFieldLabel(stringResource(R.string.permission_backend))
            MaaSingleChoiceFlow(
                options = RemoteBackend.entries.map { it to it.display },
                selected = settingsState.remoteAccess.configuredBackend,
                enabled = !locked,
                onSelect = { onSettingsIntent(SettingsIntent.SetBackend(it)) },
            )
            GroupDivider()
            MaaFieldLabel(stringResource(R.string.settings_resolution))
            MaaSingleChoiceFlow(
                options = listOf(
                    ResolutionPreference.P720 to stringResource(R.string.settings_resolution_720p),
                    ResolutionPreference.P1080 to stringResource(R.string.settings_resolution_1080p),
                ),
                selected = state.resolutionPreference,
                enabled = !locked,
                onSelect = { onIntent(SessionIntent.SetResolutionPreference(it)) },
            )
            if (PipController.isSupported(LocalContext.current)) {
                GroupDivider()
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
                    MaaSwitchRow(
                        label = stringResource(R.string.settings_pip_on_home),
                        checked = settingsState.pipOnHome,
                        onCheckedChange = { onSettingsIntent(SettingsIntent.SetPipOnHome(it)) },
                    )
                    Text(
                        text = stringResource(R.string.settings_pip_on_home_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GroupDivider()
            MaaNavigationRow(
                label = stringResource(R.string.notification_settings_title),
                description = stringResource(R.string.settings_notification_desc),
                onClick = onOpenNotificationSettings,
            )
            GroupDivider()
            MaaLabeledControlRow(
                label = stringResource(R.string.settings_wake_unlock),
                trailing = {
                    MaaSwitch(
                        checked = state.wakeUnlockEnabled,
                        onCheckedChange = { onIntent(SessionIntent.SetWakeUnlockEnabled(it)) },
                    )
                },
            )
            if (state.wakeUnlockEnabled) {
                ITextFieldWithFocus(
                    value = state.wakeCredential,
                    onValueChange = { onIntent(SessionIntent.SetWakeCredential(it)) },
                    onFocusLost = {},
                    label = stringResource(R.string.settings_wake_credential),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    inputFilter = { it.all(Char::isDigit) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.settings_wake_credential_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
            if (state.telemetryDeclared && !state.telemetryLockedByVersion) {
                GroupDivider()
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
                    MaaSwitchRow(
                        label = stringResource(R.string.settings_telemetry),
                        checked = state.telemetryEnabled,
                        onCheckedChange = { onIntent(SessionIntent.SetTelemetryEnabled(it)) },
                    )
                    Text(
                        text = stringResource(R.string.settings_telemetry_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsAndMaintenanceGroup(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onOpenRunLogArchive: () -> Unit,
    onOpenAppLog: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var showDebugConfirm by remember { mutableStateOf(false) }
    var showReinstallConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        MaaSectionHeader(title = stringResource(R.string.settings_group_logs_maintenance))
        MaaCard {
            MaaNavigationRow(
                label = stringResource(R.string.log_archive_title),
                description = stringResource(R.string.settings_log_archive_desc),
                onClick = onOpenRunLogArchive,
            )
            GroupDivider()
            MaaNavigationRow(
                label = stringResource(R.string.app_log_title),
                description = stringResource(R.string.settings_log_error_desc),
                onClick = onOpenAppLog,
            )
            GroupDivider()
            MaaNavigationRow(
                label = stringResource(R.string.log_export_title),
                description = stringResource(R.string.settings_log_export_desc),
                onClick = onExportLogs,
            )
            GroupDivider()
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs)) {
                MaaLabeledControlRow(
                    label = stringResource(R.string.settings_debug_mode),
                    trailing = {
                        MaaSwitch(
                            checked = state.debugMode,
                            onCheckedChange = { enabled ->
                                if (enabled) showDebugConfirm = true
                                else onIntent(SessionIntent.SetDebugMode(false))
                            },
                        )
                    },
                )
                Text(
                    text = stringResource(R.string.settings_debug_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GroupDivider()
            MaaNavigationRow(
                label = stringResource(R.string.pi_reinstall_title),
                description = stringResource(R.string.pi_reinstall_desc),
                onClick = { showReinstallConfirm = true },
            )
        }
    }

    if (showDebugConfirm) {
        AlertDialog(
            onDismissRequest = { showDebugConfirm = false },
            title = { Text(stringResource(R.string.dialog_enable_debug_title)) },
            text = { Text(stringResource(R.string.dialog_enable_debug_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDebugConfirm = false
                    onIntent(SessionIntent.SetDebugMode(true))
                }) { Text(stringResource(R.string.common_restart)) }
            },
            dismissButton = {
                TextButton(onClick = { showDebugConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    if (showReinstallConfirm) {
        AlertDialog(
            onDismissRequest = { showReinstallConfirm = false },
            title = { Text(stringResource(R.string.dialog_reinstall_pi_title)) },
            text = { Text(stringResource(R.string.dialog_reinstall_pi_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showReinstallConfirm = false
                    onIntent(SessionIntent.ReinstallPi)
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showReinstallConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

private data class AboutSheet(val titleRes: Int, val body: String)

@Composable
private fun AboutGroup(state: SessionUiState) {
    val context = LocalContext.current
    val metadata = state.projectMetadata
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    val screen = remember(context) { screenSize(context) }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        MaaSectionHeader(title = stringResource(R.string.settings_group_about))
        MaaCard {
            MaaInfoRow(stringResource(R.string.settings_project), appLabel)
            if (BuildConfig.MAFW_PROJECT_VERSION.isNotEmpty()) {
                MaaInfoRow(
                    label = stringResource(R.string.settings_version_of, appLabel),
                    value = BuildConfig.MAFW_PROJECT_VERSION,
                )
            }
            MaaInfoRow(
                label = stringResource(
                    R.string.settings_version_of,
                    stringResource(R.string.app_name),
                ),
                value = BuildConfig.MAFW_APP_VERSION,
            )
            if (BuildConfig.MAFW_FRAMEWORK_VERSION.isNotEmpty()) {
                MaaInfoRow(
                    label = stringResource(
                        R.string.settings_version_of,
                        stringResource(R.string.settings_framework),
                    ),
                    value = BuildConfig.MAFW_FRAMEWORK_VERSION,
                )
            }
            MaaInfoRow(
                label = stringResource(R.string.home_display_resolution),
                value = "${screen.width} × ${screen.height}",
            )

            metadata.description?.let {
                MaaDescriptionPanel {
                    MaaMarkdown(text = it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            metadata.contact?.let { body ->
                GroupDivider()
                MaaNavigationRow(
                    label = stringResource(R.string.settings_about_contact),
                    onClick = { sheet = AboutSheet(R.string.settings_about_contact, body) },
                )
            }
            metadata.license?.let { body ->
                GroupDivider()
                MaaNavigationRow(
                    label = stringResource(R.string.settings_about_license),
                    onClick = { sheet = AboutSheet(R.string.settings_about_license, body) },
                )
            }
            metadata.github?.let { url ->
                GroupDivider()
                MaaNavigationRow(
                    label = stringResource(R.string.settings_about_repository),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure { Timber.w(it, "No activity handles the project repository link") }
                    },
                )
            }
        }
    }

    sheet?.let {
        MaaMarkdownSheet(
            title = stringResource(it.titleRes),
            body = it.body,
            onDismiss = { sheet = null },
        )
    }
}
