package com.aliothmoon.maafw.ui.inventory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var category by rememberSaveable { mutableStateOf("全部") }
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
        if (category != "全部" && loaded?.items.orEmpty().none { it.group == category }) category = "全部"
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
    val groups = listOf("全部") + data?.items.orEmpty().map { it.group }.distinct()
    val filtered = data?.items.orEmpty().filter {
        (category == "全部" || it.group == category) && (query.isBlank() || it.name.contains(query, true))
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = !running && !busy) { Text("开始识别") }
                    if (collecting) OutlinedButton(onClick = onStop) { Text("停止识别") }
                }
                Text(if (collecting) "正在识别仓库，结束后自动刷新。" else "从游戏首页开始，只截图和滚动，不使用物品。", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (message.isNotBlank()) Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (data != null) {
                item {
                    Text(if (data.status == "complete") "识别完成" else "部分识别 · 未识别不代表没有库存", style = MaterialTheme.typography.titleMedium)
                    val capturedAt = runCatching {
                        java.time.OffsetDateTime.parse(data.capturedAt).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    }.getOrDefault(data.capturedAt)
                    Text("采集时间：$capturedAt", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("异方晶", "狄斯币", "搜索令", "都市搜索令").forEach { name ->
                            val item = data.items.firstOrNull { it.name == name }
                            val quantity = if (name in data.currencies) data.currencies[name]?.toString() ?: "未识别" else item?.quantityText ?: "未识别"
                            ElevatedCard { Column(Modifier.padding(12.dp)) {
                                Text(name, style = MaterialTheme.typography.labelMedium)
                                Text(quantity, style = MaterialTheme.typography.titleMedium)
                            } }
                        }
                    }
                }
                item {
                    data.categories.forEach {
                        Text("${it.name}：${if (it.status == "complete") "完整" else "部分"} · ${it.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                    listOf("道具", "消耗品").filter { name -> data.categories.none { it.name == name } }.forEach {
                        Text("$it：本轮尚未识别", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索物品") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { group -> FilterChip(selected = group == category, onClick = { category = group }, label = { Text(group) }) }
                    }
                }
                items(filtered, key = { "${it.category}:${it.id}" }) { item -> InventoryRow(item) }
                if (filtered.isEmpty()) item { Text("没有匹配的物品") }
            }
        }
    }
}

@Composable
private fun InventoryRow(item: InventoryItem) {
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
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            bitmap?.let { Image(it, null, Modifier.size(48.dp)) }
                ?: Icon(Icons.Outlined.Inventory2, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Text(item.category, style = MaterialTheme.typography.labelSmall)
            }
            Text(item.quantityText, modifier = Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
