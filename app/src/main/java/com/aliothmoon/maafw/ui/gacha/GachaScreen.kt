package com.aliothmoon.maafw.ui.gacha

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.gacha.GachaExchange
import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.gacha.GachaStore
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GachaScreen(running: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GachaStore(File(AppPaths.ROOT, "data")) }
    val scope = rememberCoroutineScope()
    val currentRunning by rememberUpdatedState(running)
    var records by remember { mutableStateOf(emptyList<GachaRecord>()) }
    var pending by remember { mutableStateOf<List<GachaRecord>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var rarity by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf(emptyList<GachaRecord>()) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun refresh() = action { records = withContext(Dispatchers.IO) { store.read() }; message = "已读取 ${records.size} 条本地记录" }
    LaunchedEffect(Unit) { refresh() }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            check(!currentRunning) { "请先停止任务再导入" }
            pending = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(GachaExchange::readExcel) ?: error("无法读取文件")
            }
        }
    }
    val exportExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { GachaExchange.writeExcel(exportSnapshot, it) } ?: error("无法写入文件")
            }
            message = "已导出 ${exportSnapshot.size} 条记录"
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(GachaExchange.markdown(exportSnapshot)) } ?: error("无法写入文件")
            }
            message = "已导出 Markdown 报告"
        }
    }
    pending?.let { incoming ->
        val count = remember(incoming, records) { GachaStore.merge(records, incoming).size - records.size }
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("导入抽卡记录") },
            text = { Text("文件含 ${incoming.size} 条，预计新增 $count 条。请确认来自同一个游戏账号；重复导入不会重复累计，同秒同名的真实重复抽数会保留。") },
            confirmButton = { TextButton(enabled = !busy && !running, onClick = {
                action {
                    check(!currentRunning) { "请先停止任务再导入" }
                    val added = withContext(Dispatchers.IO) { store.importRecords(incoming) }
                    records = withContext(Dispatchers.IO) { store.read() }
                    pending = null
                    message = "导入完成，新增 $added 条，共 ${records.size} 条"
                }
            }) { Text("合并导入") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } },
        )
    }
    val filtered = remember(records, category, rarity, query) {
        records.filter { (category == "全部" || it.category == category) && (rarity == "全部" || it.rarity == rarity) &&
            (query.isBlank() || it.name.contains(query, true) || it.pool.contains(query, true)) }.sortedByDescending { it.time }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("抽卡记录") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("先手动进入游戏记录页，再到任务页只运行「抽卡记录」。采集结束后点刷新。档案独立保存，资源更新不会清除。", style = MaterialTheme.typography.bodyMedium)
                Text("当前档案按同一游戏账号管理，请勿混入其他账号。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaaOutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("刷新") }
                    MaaOutlinedButton(onClick = { importFile.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }, enabled = !busy && !running) { Text("导入 Excel") }
                    MaaOutlinedButton(onClick = { exportSnapshot = records.toList(); exportExcel.launch("抽卡记录.xlsx") }, enabled = !busy && records.isNotEmpty()) { Text("导出 Excel") }
                    MaaOutlinedButton(onClick = { exportSnapshot = records.toList(); exportMd.launch("抽卡记录.md") }, enabled = !busy && records.isNotEmpty()) { Text("导出报告") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
                if (running) Text("任务运行中，导入暂不可用；导出为最近一次刷新的全部档案。")
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("全部") + records.map { it.category }.distinct().sorted()).forEach { label ->
                        FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("全部") + records.mapNotNull { it.rarity }.distinct().sorted()).forEach { label ->
                        FilterChip(selected = rarity == label, onClick = { rarity = label }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索角色或卡池") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("全部 ${records.size} 抽 · 当前筛选 ${filtered.size} 抽（导出包含全部记录）")
                Text(filtered.groupingBy { it.rarity ?: "未判" }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !stats, onClick = { stats = false }, label = { Text("明细") })
                    FilterChip(selected = stats, onClick = { stats = true }, label = { Text("角色统计") })
                }
            }
            if (filtered.isEmpty()) item { Text("暂无匹配记录。可以先采集，或导入已有 Excel 档案。") }
            if (stats) {
                items(filtered.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }) { row ->
                    Text("${row.key} · ${row.value} 次")
                }
            } else {
                items(filtered) { row ->
                    Column {
                        Text("${row.rarity ?: "未判"} · ${row.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${row.category} · ${row.pool}")
                        Text(row.time, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
