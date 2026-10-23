package com.aliothmoon.maafw.ui.inventory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
    Column(modifier) {
        TopAppBar(title = { Text("仓库资源") }, windowInsets = WindowInsets(0, 0, 0, 0), actions = {
            IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "刷新") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "导出") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("导出 JSON 记录") }, enabled = data != null && !busy,
                        onClick = { menu = false; exportJson.launch("仓库资源.json") })
                    DropdownMenuItem(text = { Text("导出 Markdown 汇总") }, enabled = data != null && !busy,
                        onClick = { menu = false; exportMd.launch("仓库资源.md") })
                }
            }
        })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Controls and summary span the screen; all inventory items share one adaptive grid.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !running && !busy) { Text("开始识别") }
                        if (collecting) OutlinedButton(onClick = onStop) { Text("停止识别") }
                    }
                    Text(if (collecting) "正在识别仓库，结束后自动刷新。" else "从游戏首页开始，只截图和滚动，不使用物品。", style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (message.isNotBlank()) Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (data != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val capturedAt = runCatching {
                            java.time.OffsetDateTime.parse(data.capturedAt).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        }.getOrDefault(data.capturedAt)
                        Text("更新于 $capturedAt", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("异方晶", "狄斯币", "搜索令", "都市搜索令").forEach { name ->
                                val item = data.items.firstOrNull { it.name == name }
                                val quantity = if (name in data.currencies) data.currencies[name]?.toString() ?: "未识别" else item?.quantityText ?: "未识别"
                                // Display-only conversion; the snapshot and exports retain the raw amount.
                                val displayQuantity = if (name == "异方晶") {
                                    data.currencies[name]?.let { "$it / ${it / 180}" } ?: "未识别 / —"
                                } else quantity
                                ElevatedCard { Column(Modifier.padding(12.dp)) {
                                    Text(if (name == "异方晶") "异方晶 / 抽数" else name, style = MaterialTheme.typography.labelMedium)
                                    Text(displayQuantity, style = MaterialTheme.typography.titleMedium)
                                } }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索物品") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                items(filtered, key = { "${it.category}:${it.id}" }) { item -> InventoryCell(item) }
                if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的物品") }
            }
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
