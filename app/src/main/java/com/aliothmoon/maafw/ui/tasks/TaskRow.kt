package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaCardBorder
import com.aliothmoon.maafw.ui.components.maaClickable

/** 未勾选任务的文案区淡化程度；Checkbox 与操作钮不跟着淡，否则点不准 */
private const val DisabledTaskAlpha = 0.52f

/**
 * 提取任务当前已选参数的 1 行紧凑摘要（例如「刷取材料 · 恩慈余光 S2-6」），
 * 方便在列表中直接预览配置而无需逐个点开
 */
private fun buildOptionSummary(options: List<OptionEditorState>): String? {
    if (options.isEmpty()) return null
    val parts = mutableListOf<String>()
    fun collect(list: List<OptionEditorState>) {
        for (opt in list) {
            if (parts.size >= 3) return
            when (opt.kind) {
                OptionKind.Select, OptionKind.Switch -> {
                    val active = opt.activeCases.firstOrNull()
                    if (active != null) {
                        if (opt.kind == OptionKind.Select) {
                            parts += active.label
                        } else if (active.name.lowercase() in setOf("yes", "y", "on", "true", "enable")) {
                            parts += opt.label
                        }
                        if (active.children.isNotEmpty()) collect(active.children)
                    }
                }
                OptionKind.Checkbox -> {
                    opt.activeCases.forEach { c ->
                        if (parts.size < 3) parts += c.label
                        if (c.children.isNotEmpty()) collect(c.children)
                    }
                }
                OptionKind.Input -> {
                    val first = opt.inputs.firstOrNull { it.value.isNotBlank() }
                    if (first != null) parts += "${first.label}:${first.value}"
                }
            }
        }
    }
    collect(options)
    return parts.distinct().take(3).joinToString(" · ").takeIf { it.isNotBlank() }
}

/**
 * 紧凑动作图标：28dp 触控区 + 15dp 图标，在保留行内重命名/复制/删除的同时最大化标题显示宽度
 */
@Composable
private fun CompactActionIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 0.88f else MaaDesignTokens.Alpha.disabledContent),
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
internal fun TaskRow(
    task: ResolvedConfiguredTask,
    orderIndex: Int,
    locked: Boolean,
    isDragging: Boolean,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (task.enabled) 1f else DisabledTaskAlpha,
        animationSpec = MaaMotion.enter(),
        label = "taskContentAlpha",
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) MaaTheme.style.dragElevation else 0.dp,
        animationSpec = MaaMotion.enter(),
        label = "dragElevation",
    )
    val optionSummary = remember(task.options) { buildOptionSummary(task.options) }
    val border = if (reorderMode) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f))
    } else {
        maaCardBorder()
    }

    MaaCardSurface(
        border = border,
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = dragElevation, shape = MaterialTheme.shapes.medium)
            .maaClickable(enabled = !reorderMode && task.hasOptions, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Checkbox(
                    checked = task.enabled,
                    onCheckedChange = onToggle,
                    enabled = !locked && !task.missingDefinition,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = (orderIndex + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                    fontSize = 10.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f * contentAlpha),
            )
            Spacer(Modifier.width(6.dp))
            if (!task.icon.isNullOrBlank()) {
                MaaPiIcon(
                    path = task.icon,
                    size = 18.dp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .alpha(contentAlpha),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp,
                    ),
                    color = if (task.effectiveEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!optionSummary.isNullOrBlank() && task.unavailableReason == null) {
                    Text(
                        text = optionSummary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                task.unavailableReason?.let {
                    MaaToneBadge(
                        text = it.asString(),
                        tone = MaaTone(
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.errorContainer,
                        ),
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            if (reorderMode) {
                CompactActionIcon(
                    icon = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.tasks_move_up),
                    enabled = !locked && canMoveUp,
                    emphasized = canMoveUp,
                    onClick = onMoveUp,
                )
                CompactActionIcon(
                    icon = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.tasks_move_down),
                    enabled = !locked && canMoveDown,
                    emphasized = canMoveDown,
                    onClick = onMoveDown,
                )
                CompactActionIcon(
                    icon = Icons.Outlined.DragIndicator,
                    contentDescription = stringResource(R.string.tasks_drag_reorder),
                    enabled = !locked,
                    emphasized = true,
                    modifier = dragHandleModifier,
                )
            } else {
                if (task.hasOptions) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(16.dp)
                            .alpha(contentAlpha),
                    )
                }
                CompactActionIcon(
                    icon = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.tasks_rename),
                    enabled = !locked,
                    onClick = onRename,
                )
                CompactActionIcon(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.tasks_duplicate),
                    enabled = !locked,
                    onClick = onDuplicate,
                )
                CompactActionIcon(
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.tasks_remove),
                    enabled = !locked,
                    onClick = onRemove,
                )
            }
        }
    }
}
