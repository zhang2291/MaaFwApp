package com.aliothmoon.maafw.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.settings.ResourceUpdatePanelState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.update.UpdateCheckResult

/**
 * 紧凑双行更新卡片：合并客户端本体更新与 PI 资源包更新，直连 GitHub，移除冗余更新源/渠道配置
 */
@Composable
internal fun UnifiedUpdatesCard(
    update: UpdatePanelState,
    resourceUpdate: ResourceUpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_updates_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "GITHUB DIRECT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        AppUpdateCompactRow(update = update, onSettingsIntent = onSettingsIntent)

        HorizontalDivider(
            thickness = MaaDesignTokens.Separator.thickness,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.xxs),
        )

        ResourceUpdateCompactRow(update = resourceUpdate, onSettingsIntent = onSettingsIntent)
    }
}

@Composable
private fun AppUpdateCompactRow(
    update: UpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val upToDate = update.checkResult is UpdateCheckResult.UpToDate
    val subtitle = buildString {
        append("v")
        append(BuildConfig.VERSION_NAME)
        if (upToDate) {
            append(" · ")
            append(stringResource(R.string.settings_update_up_to_date))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_app_client),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!update.downloading) {
                CompactUpdateButton(
                    label = stringResource(
                        if (update.checking) R.string.settings_update_checking
                        else R.string.home_update_check_short,
                    ),
                    loading = update.checking,
                    emphasized = update.availableUpdate != null,
                    onClick = { onSettingsIntent(SettingsIntent.CheckUpdate) },
                )
            }
        }
        if (update.downloading) {
            CompactProgressRow(
                downloadedBytes = update.downloadedBytes,
                totalBytes = update.totalBytes,
                onCancel = { onSettingsIntent(SettingsIntent.CancelDownload) },
            )
        }
        update.errorMessage?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ResourceUpdateCompactRow(
    update: ResourceUpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val versionText = update.currentVersion ?: stringResource(R.string.settings_resource_version_builtin)
    val subtitle = buildString {
        append(versionText)
        update.repository?.let { repo ->
            append(" · ")
            append(repo.substringAfter('/'))
        }
        update.latestVersion?.takeIf { it != update.currentVersion }?.let { latest ->
            append(" → ")
            append(latest)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_resource_pack),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    color = if (update.updateAvailable) {
                        MaaTheme.palette.success.content
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!update.downloading) {
                CompactUpdateButton(
                    label = stringResource(
                        when {
                            update.checking -> R.string.settings_resource_update_checking
                            update.updateAvailable -> R.string.home_update_download_short
                            else -> R.string.home_update_check_short
                        },
                    ),
                    loading = update.checking,
                    emphasized = update.updateAvailable,
                    onClick = {
                        onSettingsIntent(
                            if (update.updateAvailable) SettingsIntent.DownloadResourceUpdate
                            else SettingsIntent.CheckResourceUpdate,
                        )
                    },
                )
            }
        }
        if (update.downloading) {
            CompactProgressRow(
                downloadedBytes = update.downloadedBytes,
                totalBytes = update.totalBytes,
                onCancel = { onSettingsIntent(SettingsIntent.CancelResourceDownload) },
            )
        }
        update.statusMessage?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        update.errorMessage?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CompactUpdateButton(
    label: String,
    loading: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    if (emphasized) {
        MaaButton(
            onClick = onClick,
            enabled = !loading,
            shape = shape,
            contentPadding = contentPadding,
            modifier = Modifier.height(32.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = MaaDesignTokens.Border.marker,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        MaaOutlinedButton(
            onClick = onClick,
            enabled = !loading,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(
                width = MaaDesignTokens.Separator.thickness,
                color = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.height(32.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = MaaDesignTokens.Border.marker,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun CompactProgressRow(
    downloadedBytes: Long,
    totalBytes: Long,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        if (totalBytes > 0) {
            LinearProgressIndicator(
                progress = { downloadedBytes.coerceAtLeast(0L).toFloat() / totalBytes },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${downloadedBytes.coerceAtLeast(0L) * 100 / totalBytes}%",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.weight(1f))
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(MaaDesignTokens.IconContainer.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.settings_update_cancel_download),
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
        }
    }
}
