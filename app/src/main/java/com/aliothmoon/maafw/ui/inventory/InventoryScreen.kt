package com.aliothmoon.maafw.ui.inventory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.inventory.*
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(running: Boolean, collecting: Boolean, onStart: () -> Unit, onStop: () -> Unit,
                    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InventoryStore(File(AppPaths.ROOT, "data"), File(AppPaths.ROOT, AppFiles.PI_DIR)) }
    var snapshot by remember { mutableStateOf<InventorySnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<InventoryHistoryEntry>?>(null) }
    var baseline by remember { mutableStateOf<InventorySnapshot?>(null) }
    var showUnchanged by rememberSaveable { mutableStateOf(false) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun refresh() = action {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        snapshot = loaded
        message = if (loaded == null) "暂无仓库记录，请先打开游戏首页，再开始识别。" else ""
    }
    LaunchedEffect(collecting) { if (!collecting) refresh() }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = snapshot
        if (uri != null && data != null) action {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data.json) }
                    ?: error("无法写入文件")
            }
            message = "仓库记录已导出"
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val data = snapshot
        if (uri != null && data != null) action {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data.markdown()) }
                    ?: error("无法写入文件")
            }
            message = "仓库汇总已导出"
        }
    }
    val data = snapshot
    val filtered = data?.items.orEmpty().filter {
        query.isBlank() || it.name.contains(query, true)
    }
    val previous = baseline
    val comparison = remember(previous, data) {
        if (previous != null && data != null) InventoryComparison.compare(previous, data) else emptyList()
    }.filter { (showUnchanged || it.delta != 0L) && (query.isBlank() || it.name.contains(query, true)) }

    history?.let { entries ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("选择历史快照") },
            text = {
                if (entries.isEmpty()) Text("暂无可对比的历史快照，请先完成另一次仓库识别。")
                else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    listItems(entries, key = { it.fileName }) { entry ->
                        TextButton(enabled = !busy, onClick = {
                            action {
                                baseline = withContext(Dispatchers.IO) { store.loadHistory(entry.fileName) }
                                showUnchanged = false
                                history = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(formatCapturedAt(entry.capturedAt))
                                Text(if (entry.status == "complete") "完整快照" else "部分识别快照",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text("取消") } },
        )
    }
    MaaCardSurface(modifier = modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = MaaDesignTokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "仓库识别",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = !busy, onClick = { refresh() }) {
                Icon(Icons.Outlined.Refresh, "刷新")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "导出") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("导出 JSON 记录") }, enabled = data != null && !busy,
                        onClick = { menu = false; exportJson.launch("仓库资源.json") })
                    DropdownMenuItem(text = { Text("导出 Markdown 汇总") }, enabled = data != null && !busy,
                        onClick = { menu = false; exportMd.launch("仓库资源.md") })
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(MaaDesignTokens.Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            // Controls and summary span the screen; all inventory items share one adaptive grid.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    MaaButton(
                        onClick = onStart,
                        enabled = !running && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始识别") }
                    if (collecting) {
                        MaaOutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止识别") }
                    }
                    if (collecting) {
                        Text(
                            "正在识别仓库，结束后自动刷新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = MaaDesignTokens.Spacing.sm))
                    if (message.isNotBlank()) {
                        Text(message, Modifier.padding(top = MaaDesignTokens.Spacing.sm), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (data != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                        val capturedAt = runCatching {
                            java.time.OffsetDateTime.parse(data.capturedAt).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        }.getOrDefault(data.capturedAt)
                        Text(
                            "更新于 $capturedAt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val metrics = listOf("异方晶", "狄斯币", "搜索令", "都市搜索令").map { name ->
                            val item = data.items.firstOrNull { it.name == name }
                            val quantity = if (name in data.currencies) {
                                data.currencies[name]?.toString() ?: "未识别"
                            } else {
                                item?.quantityText ?: "未识别"
                            }
                            // Display-only conversion; the snapshot and exports retain the raw amount.
                            val displayQuantity = if (name == "异方晶") {
                                data.currencies[name]?.let { it.toString() + " / " + (it / 180) } ?: "未识别 / —"
                            } else quantity
                            (if (name == "异方晶") "异方晶 / 抽数" else name) to displayQuantity
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                            metrics.chunked(2).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    row.forEach { (label, value) ->
                                        InventoryMetricCard(label, value, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        ) {
                            MaaOutlinedButton(enabled = !busy && !collecting, onClick = {
                                action {
                                    history = withContext(Dispatchers.IO) { store.history() }
                                        .filter { data.runId.isBlank() || it.fileName != data.runId + ".json" }
                                }
                            }) { Text(if (previous == null) "对比历史快照" else "更换历史快照") }
                            if (previous != null) TextButton(onClick = { baseline = null }) { Text("关闭对比") }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索物品") },
                        singleLine = true,
                        shape = RoundedCornerShape(MaaTheme.style.radii.inner),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (previous != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(MaaTheme.style.radii.card),
                        ) {
                            Column(Modifier.padding(MaaDesignTokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                                Text("与历史快照对比", style = MaterialTheme.typography.titleMedium)
                                Text("历史：${formatCapturedAt(previous.capturedAt)}", style = MaterialTheme.typography.bodySmall)
                                Text("当前：${formatCapturedAt(data.capturedAt)}", style = MaterialTheme.typography.bodySmall)
                                Text("变化 = 当前 − 历史；缺失、未知和冲突不按 0 计算。", style = MaterialTheme.typography.bodySmall)
                                if (previous.status != "complete" || data.status != "complete") {
                                    Text("包含部分识别快照，仅比较两侧已确认的数量。", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { showUnchanged = !showUnchanged }) {
                                    Text(if (showUnchanged) "隐藏未变化物品" else "显示未变化物品")
                                }
                            }
                        }
                    }
                    items(comparison, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { row ->
                        InventoryChangeRow(row)
                    }
                    if (comparison.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (query.isBlank()) "已确认物品没有数量变化" else "没有匹配的变化记录")
                    }
                } else {
                    items(filtered, key = { "${it.category}:${it.id}" }) { item -> InventoryCell(item) }
                    if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的物品") }
                }
            }
        }
    } }
}

@Composable
private fun InventoryMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 78.dp),
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaaDesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InventoryCell(item: InventoryItem) {
    val bitmap by produceState<ImageBitmap?>(null, item.image) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val path = item.image?.path ?: return@runCatching null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 192).coerceAtLeast(1)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaaDesignTokens.Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            bitmap?.let { Image(it, null, Modifier.size(48.dp)) }
                ?: Icon(Icons.Outlined.Inventory2, null, Modifier.size(48.dp))
            Text(
                text = if (item.unknown) "未识别 · ${item.id.substringAfterLast(':').take(6)}" else item.name,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Quantity has no height/line cap: conflicts must remain readable in narrow cells.
            Text(
                text = item.quantityText,
                style = if (item.conflicts.isEmpty()) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatCapturedAt(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrDefault(value)

@Composable
private fun InventoryChangeRow(row: InventoryComparisonRow) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
    ) {
        Column(Modifier.padding(MaaDesignTokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(row.deltaText, style = MaterialTheme.typography.titleSmall,
                    color = if (row.delta == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            Text("历史 ${row.previousText} → 当前 ${row.currentText}", style = MaterialTheme.typography.bodySmall)
            if (row.reason.isNotBlank()) Text(row.reason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
