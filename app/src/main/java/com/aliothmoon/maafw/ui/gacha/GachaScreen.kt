package com.aliothmoon.maafw.ui.gacha

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.gacha.GachaArchiveStore
import com.aliothmoon.maafw.gacha.GachaArchiveSnapshot
import com.aliothmoon.maafw.gacha.GachaBundle
import com.aliothmoon.maafw.gacha.GachaCategory
import com.aliothmoon.maafw.gacha.GachaExchange
import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GachaScreen(
    running: Boolean,
    collecting: Boolean,
    onStart: (String, Boolean) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { GachaArchiveStore(File(AppPaths.ROOT, "data")) }
    val scope = rememberCoroutineScope()
    val currentRunning by rememberUpdatedState(running)
    var snapshot by remember { mutableStateOf(GachaArchiveSnapshot(emptyList(), emptyList())) }
    var pending by remember { mutableStateOf<GachaBundle?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("default-0") }
    var pool by rememberSaveable { mutableStateOf("") }
    var rarity by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var stopOnKnown by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GachaCategory?>(null) }
    var deleteError by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    val category = snapshot.categories.firstOrNull { it.id == selectedId }
    val groupedRecords = remember(snapshot) { snapshot.records.groupBy { it.categoryId ?: "legacy" } }
    val selectedRecords = groupedRecords[selectedId].orEmpty()
    val pools = remember(selectedRecords) { GachaStatistics.pools(selectedRecords) }
    val totals = remember(snapshot) { GachaStatistics.totals(snapshot.records) }
    val hits = remember(selectedRecords, pool) { GachaStatistics.hits(selectedRecords).filter { pool.isEmpty() || it.record.pool == pool } }
    val filtered = remember(selectedRecords, pool, rarity, query) {
        selectedRecords.filter {
            (pool.isEmpty() || it.pool == pool) && (rarity == "全部" || it.rarity == rarity) &&
                (query.isBlank() || it.name.contains(query, true) || it.pool.contains(query, true))
        }.sortedWith(GachaStatistics.newestFirst)
    }

    fun action(onError: (String) -> Unit = { message = it }, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "操作失败，请重试") }
            finally { busy = false }
        }
    }
    suspend fun loadRecords() {
        snapshot = withContext(Dispatchers.IO) { store.snapshot() }
        if (snapshot.categories.none { it.id == selectedId }) {
            selectedId = snapshot.categories.firstOrNull { it.id != "legacy" }?.id ?: "legacy"
            pool = ""
        }
    }
    fun refresh() = action { loadRecords() }
    LaunchedEffect(collecting) { if (!collecting) refresh() }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            check(!currentRunning) { "请先停止任务再导入" }
            pending = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(GachaExchange::readBundleExcel) ?: error("无法读取文件")
            }
            importError = ""
        }
    }
    val exportExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) action {
            check(!currentRunning) { "请在任务结束后导出" }
            val count = withContext(Dispatchers.IO) {
                val bundle = store.bundle()
                context.contentResolver.openOutputStream(uri)?.use { GachaExchange.writeBundleExcel(bundle, it) } ?: error("无法写入文件")
                bundle.records.size
            }
            message = "已导出全部类型，共 $count 条记录"
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) action {
            check(!currentRunning) { "请在任务结束后导出" }
            withContext(Dispatchers.IO) {
                val bundle = store.bundle()
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(GachaExchange.markdownBundle(bundle))
                } ?: error("无法写入文件")
            }
            message = "全部记录已导出为 Markdown"
        }
    }
    if (showAdd) AlertDialog(
        onDismissRequest = { if (!busy) showAdd = false },
        title = { Text("添加池子类型") },
        text = {
            OutlinedTextField(value = newName, onValueChange = { if (it.length <= 40) newName = it; addError = "" },
                label = { Text("类型名称") }, placeholder = { Text("例如：四合一") }, singleLine = true,
                enabled = !busy, isError = addError.isNotEmpty(), supportingText = { if (addError.isNotEmpty()) Text(addError) })
        },
        confirmButton = { TextButton(enabled = !busy && !running && newName.isNotBlank(), onClick = {
            action(onError = { addError = it }) {
                check(!currentRunning) { "请先停止任务" }
                val added = withContext(Dispatchers.IO) { store.addCategory(newName.trim()) }
                selectedId = added.id; pool = ""; showAdd = false; newName = ""
                loadRecords()
            }
        }) { Text("添加") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { showAdd = false }) { Text("取消") } },
    )
    deleteTarget?.let { target ->
        fun remove(permanent: Boolean) = action(onError = { deleteError = it }) {
            check(!currentRunning) { "请先停止任务" }
            val backup = withContext(Dispatchers.IO) { store.deleteCategory(target.id, permanent) }
            deleteTarget = null
            loadRecords()
            message = if (permanent) "已永久删除该类型的当前记录" else "类型已删除，记录已备份：$backup"
        }
        AlertDialog(onDismissRequest = { if (!busy) deleteTarget = null }, title = { Text("删除“${target.name}”？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("删除并备份：记录保留在备份目录。\n永久删除：清除当前记录和自动报告，无法撤销。")
                if (deleteError.isNotEmpty()) Text(deleteError, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !busy && !running, onClick = { remove(false) }) { Text("删除并备份") }
                    TextButton(enabled = !busy && !running, onClick = { remove(true) }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                }
            }, dismissButton = { TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") } })
    }
    pending?.let { bundle ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("导入全部记录") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件包含 ${bundle.categories.size} 个类型、${bundle.records.size} 条记录。按类型合并，重复导入不会重复累计。请确认来自同一个游戏账号。")
                if (importError.isNotEmpty()) Text(importError, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && !running, onClick = {
                action(onError = { importError = it }) {
                    check(!currentRunning) { "请先停止任务" }
                    val added = withContext(Dispatchers.IO) { store.importAll(bundle) }
                    pending = null; loadRecords(); message = "导入完成，新增 $added 条记录"
                }
            }) { Text("合并导入") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } })
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("抽卡记录") }, windowInsets = WindowInsets(0, 0, 0, 0), actions = {
            IconButton(onClick = { refresh() }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新记录") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "记录管理") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("添加类型") }, enabled = !busy && !running,
                        onClick = { more = false; newName = ""; addError = ""; showAdd = true })
                    DropdownMenuItem(text = { Text("删除当前类型") }, enabled = !busy && !running && category != null,
                        onClick = { more = false; deleteError = ""; deleteTarget = category })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("导出全部 Excel") }, enabled = !busy && !running,
                        onClick = { more = false; exportExcel.launch("全部抽卡记录.xlsx") })
                    DropdownMenuItem(text = { Text("导出全部 Markdown") }, enabled = !busy && !running,
                        onClick = { more = false; exportMd.launch("全部抽卡记录.md") })
                    DropdownMenuItem(text = { Text("导入全部记录") }, enabled = !busy && !running,
                        onClick = { more = false; importFile.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) })
                }
            }
        })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GachaMetric("总抽数", totals.pulls.toString(), Modifier.weight(1f))
                    GachaMetric("狂总数", totals.gold.toString(), Modifier.weight(1f))
                    GachaMetric("狂平均抽数", totals.average, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GachaChoice(category?.name ?: "选择类型", snapshot.categories.map { it.id to it.name }, selectedId,
                        enabled = !busy && !running, modifier = Modifier.weight(1f)) {
                        selectedId = it; pool = ""; rarity = "全部"; query = ""
                    }
                    GachaChoice(if (stopOnKnown) "识别到已知停止" else "完整识别",
                        listOf("full" to "完整识别", "known" to "识别到已知停止"), if (stopOnKnown) "known" else "full",
                        enabled = !busy && !running, modifier = Modifier.weight(1f)) { stopOnKnown = it == "known" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MaaButton(onClick = { if (!currentRunning && category != null) onStart(selectedId, stopOnKnown) },
                        enabled = !busy && !running && category != null && selectedId != "legacy") { Text("开始采集") }
                    if (collecting) MaaOutlinedButton(onClick = onStop) { Text("停止") }
                    Text(if (collecting) "正在采集 · 自动保存" else if (running) "其他任务运行中" else "采集记录自动保存",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                }
                if (!collecting) Text("采集前请手动打开游戏记录页。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
            item {
                GachaChoice(if (pool.isEmpty()) "全部实际卡池" else pool,
                    listOf("" to "全部实际卡池") + pools.map { it to it }, pool, modifier = Modifier.fillMaxWidth()) { pool = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !details, onClick = { details = false }, label = { Text("概览") })
                    FilterChip(selected = details, onClick = { details = true }, label = { Text("完整明细") })
                }
            }
            if (!details) {
                item {
                    Text("各类型汇总", style = MaterialTheme.typography.titleSmall)
                    FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        snapshot.categories.forEach { c ->
                            val count = GachaStatistics.totals(groupedRecords[c.id].orEmpty())
                            OutlinedCard(Modifier.weight(1f).widthIn(min = 135.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(c.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${count.pulls} 抽", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("狂：${count.gold}", style = MaterialTheme.typography.bodySmall)
                                    Text("狂平均抽数：${count.average}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                item { Text("${category?.name ?: "当前类型"} · 狂级出货记录", style = MaterialTheme.typography.titleSmall) }
                if (hits.isEmpty()) item { Text("暂无狂级记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(hits) { hit ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(hit.record.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Text(hit.interval?.let { "$it 抽" } ?: "—", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(hit.record.pool, style = MaterialTheme.typography.bodySmall)
                            Text(hit.record.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hit.interval == null) Text("无上一条狂记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索角色 / 卡池") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        GachaChoice(rarity, (listOf("全部") + selectedRecords.mapNotNull { it.rarity }.distinct()).map { it to it },
                            rarity, modifier = Modifier.widthIn(max = 110.dp)) { rarity = it }
                    }
                    Text("${filtered.size} 条记录", style = MaterialTheme.typography.labelMedium)
                }
                if (filtered.isEmpty()) item { Text("暂无匹配记录") }
                items(filtered) { row -> GachaDetailRow(row) }
            }
        }
    }
}

@Composable
private fun GachaMetric(label: String, value: String, modifier: Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun GachaChoice(label: String, options: List<Pair<String, String>>, selected: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        MaaOutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(" ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name, fontWeight = if (id == selected) FontWeight.SemiBold else null) },
                onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun GachaDetailRow(record: GachaRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(record.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(record.rarity ?: "未判", style = MaterialTheme.typography.labelLarge)
        }
        Text("${record.pool} · ${record.category}", style = MaterialTheme.typography.bodySmall)
        Text(record.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
