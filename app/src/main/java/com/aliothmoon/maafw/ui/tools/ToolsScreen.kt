package com.aliothmoon.maafw.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 子工具独立保存界面状态，返回始终回到卡片中心。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    isActivePage: Boolean = true,
    inventory: @Composable () -> Unit,
    gacha: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = isActivePage && selected != null) { selected = null }
    Column(modifier) {
        if (selected == null) {
            TopAppBar(title = { Text("小工具") }, windowInsets = WindowInsets(0, 0, 0, 0))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolCard("抽卡记录", "识别、导入和查看抽卡统计", Icons.Outlined.History) { selected = "gacha" }
                ToolCard("仓库资源", "查看物品数量，选择历史快照对比变化", Icons.Outlined.Inventory2) { selected = "inventory" }
            }
        } else {
            TextButton(onClick = { selected = null }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("返回小工具")
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                holder.SaveableStateProvider(selected!!) {
                    if (selected == "inventory") inventory() else gacha()
                }
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
