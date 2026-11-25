package com.aliothmoon.maafw.ui.gacha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.gacha.GachaArchiveSnapshot
import com.aliothmoon.maafw.gacha.GachaArchiveStore
import com.aliothmoon.maafw.gacha.GachaBundle
import com.aliothmoon.maafw.gacha.GachaCategory
import com.aliothmoon.maafw.gacha.GachaExchange
import com.aliothmoon.maafw.gacha.GachaProgress
import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.gacha.UpBannerCatalog
import com.aliothmoon.maafw.gacha.UpStatus
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.theme.MaaTone
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCardSurface
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaPulsingDot
import com.aliothmoon.maafw.ui.components.maaCardBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val SinnerAvatarCache = object : android.util.LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = maxOf(1, value.width * value.height * 4)
}

@Volatile
private var CachedGachaSnapshot: GachaArchiveSnapshot? = null

private fun decodeSinnerAvatar(piRoot: File, name: String): ImageBitmap? {
    SinnerAvatarCache.get(name)?.let { return it }
    val file = UpBannerCatalog.resolveAvatar(piRoot, name) ?: return null
    val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() ?: return null
    SinnerAvatarCache.put(name, bmp)
    return bmp
}

private data class GachaUiPalette(
    val gold: MaaTone,
    val purple: MaaTone,
    val blue: MaaTone,
    val warn: MaaTone,
    val green: MaaTone,
    val neutral: MaaTone,
    val trackBg: Color,
    val segmentBg: Color,
    val goldCardBg: Color,
    val purpleCardBg: Color,
    val softTick: Color,
)

@Composable
private fun gachaPalette(): GachaUiPalette {
    val isDark = MaaTheme.isDark
    val scheme = MaterialTheme.colorScheme
    val palette = MaaTheme.palette
    return GachaUiPalette(
        gold = palette.warning,
        purple = palette.violet,
        blue = palette.info,
        warn = palette.warning,
        green = palette.success,
        neutral = palette.neutral,
        trackBg = scheme.surfaceContainerHighest,
        segmentBg = scheme.surfaceContainerHigh,
        goldCardBg = if (isDark) Color(0xFF1D1912) else Color(0xFFFFFCF5),
        purpleCardBg = if (isDark) Color(0xFF191622) else Color(0xFFFCF9FF),
        softTick = scheme.onSurfaceVariant.copy(alpha = 0.55f),
    )
}

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
    val dataRoot = remember { File(AppPaths.ROOT, "data") }
    val store = remember { GachaArchiveStore(dataRoot) }
    val scope = rememberCoroutineScope()
    val currentRunning by rememberUpdatedState(running)
    var snapshot by remember { mutableStateOf(CachedGachaSnapshot ?: GachaArchiveSnapshot(emptyList(), emptyList())) }
    var pending by remember { mutableStateOf<GachaBundle?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf("all") }
    var pool by rememberSaveable { mutableStateOf("") }
    var rarity by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var groupByTen by rememberSaveable { mutableStateOf(true) }
    var stopOnKnown by rememberSaveable { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showSkippedDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GachaCategory?>(null) }
    var deleteError by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var collectStartMs by rememberSaveable { mutableStateOf(0L) }
    var liveProgress by remember { mutableStateOf<GachaProgress?>(null) }
    var wasCollecting by remember { mutableStateOf(false) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val colors = gachaPalette()
    val piRoot = remember { File(AppPaths.ROOT, AppFiles.PI_DIR) }
    val bannerCatalog by produceState(UpBannerCatalog(), piRoot) {
        value = withContext(Dispatchers.IO) { UpBannerCatalog.load(piRoot) }
    }
    var showReportDialog by remember { mutableStateOf(false) }
    var pendingReportDark by remember { mutableStateOf<Boolean?>(null) }

    val category = snapshot.categories.firstOrNull { it.id == selectedId }
    val isAll = selectedId == "all" || (snapshot.categories.isEmpty() && selectedId == "legacy")
    val groupedRecords = remember(snapshot) { snapshot.records.groupBy { it.categoryId ?: "legacy" } }
    val gameTypes = remember(groupedRecords) {
        groupedRecords.mapValues { (_, list) -> GachaStatistics.gameType(list) }
    }
    val selectedRecords = if (isAll) snapshot.records else groupedRecords[selectedId].orEmpty()
    val pools = remember(selectedRecords) { GachaStatistics.pools(selectedRecords) }
    val totals = remember(selectedRecords) { GachaStatistics.totals(selectedRecords) }
    val pityCount = remember(selectedRecords, isAll) { if (isAll) 0 else GachaStatistics.pity(selectedRecords) }
    val hits = remember(selectedRecords, isAll, pool, bannerCatalog) {
        val resolver: (GachaRecord) -> UpStatus? = { rec -> bannerCatalog.classify(rec.pool, rec.type, rec.name) }
        val raw = if (isAll) {
            GachaStatistics.hitsAcrossCategories(selectedRecords, resolver)
        } else {
            GachaStatistics.hits(selectedRecords, resolver)
        }
        raw.filter { pool.isEmpty() || it.record.pool == pool }
    }
    val upStats = remember(hits) { GachaStatistics.upStats(hits) }
    val filtered = remember(selectedRecords, pool, rarity, query) {
        selectedRecords.filter {
            (pool.isEmpty() || it.pool == pool) && (rarity == "全部" || it.rarity == rarity) &&
                (query.isBlank() || it.name.contains(query, true) || it.pool.contains(query, true))
        }.sortedWith(GachaStatistics.newestFirst)
    }
    val pullGroups = remember(filtered, groupByTen) {
        if (groupByTen) GachaStatistics.groups(filtered) else emptyList()
    }

    fun action(
        onError: (String) -> Unit = {
            message = it
            messageIsError = true
        },
        block: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("GachaScreen", "Gacha action failed", e)
                onError(e.message ?: "操作失败，请重试")
            } finally {
                busy = false
            }
        }
    }

    suspend fun loadRecords() {
        val loaded = withContext(Dispatchers.IO) { store.snapshot() }
        CachedGachaSnapshot = loaded
        snapshot = loaded
        if (selectedId != "all" && snapshot.categories.none { it.id == selectedId }) {
            selectedId = "all"
            pool = ""
        }
    }

    fun refresh() = action {
        message = ""
        messageIsError = false
        loadRecords()
    }

    LaunchedEffect(collecting) {
        if (collecting) {
            wasCollecting = true
            if (collectStartMs == 0L) collectStartMs = System.currentTimeMillis()
            while (true) {
                liveProgress = withContext(Dispatchers.IO) { GachaProgress.read(dataRoot, collectStartMs) }
                delay(800)
            }
        } else {
            if (wasCollecting) {
                wasCollecting = false
                val finalProgress = withContext(Dispatchers.IO) { GachaProgress.read(dataRoot, collectStartMs) }
                liveProgress = null
                collectStartMs = 0L
                action {
                    loadRecords()
                    if (finalProgress != null) {
                        val (summary, isWarning) = finalProgress.completionSummary()
                        message = summary
                        messageIsError = isWarning
                    } else {
                        message = ""
                        messageIsError = false
                    }
                }
            } else {
                refresh()
            }
        }
    }

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
            messageIsError = false
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
            messageIsError = false
            message = "全部记录已导出为 Markdown"
        }
    }
    val exportReportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val useDark = pendingReportDark
        pendingReportDark = null
        if (uri != null && useDark != null) action {
            val titleText = category?.name ?: if (isAll) "全部记录" else "抽卡记录"
            val ordered = selectedRecords.sortedWith(GachaStatistics.newestFirst)
            val oldest = ordered.lastOrNull()?.time?.take(10).orEmpty()
            val newest = ordered.firstOrNull()?.time?.take(10).orEmpty()
            val dateRange = if (oldest.isNotEmpty() && newest.isNotEmpty()) "$oldest 至 $newest" else ""
            withContext(Dispatchers.IO) {
                val bmp = GachaReportRenderer.render(
                    title = "$titleText · 抽卡战报",
                    dateRange = dateRange,
                    totals = totals,
                    upStats = upStats,
                    hits = hits,
                    pityCount = if (isAll) null else pityCount,
                    piRoot = piRoot,
                    darkTheme = useDark,
                )
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        check(bmp.compress(Bitmap.CompressFormat.PNG, 95, out)) { "PNG 编码失败" }
                    } ?: error("无法写入文件")
                } finally {
                    bmp.recycle()
                }
            }
            showReportDialog = false
            messageIsError = false
            message = "抽卡战报长图已保存"
        }
    }

    if (showReportDialog) {
        val titleText = category?.name ?: if (isAll) "全部记录" else "抽卡记录"
        val ordered = remember(selectedRecords) { selectedRecords.sortedWith(GachaStatistics.newestFirst) }
        val oldest = ordered.lastOrNull()?.time?.take(10).orEmpty()
        val newest = ordered.firstOrNull()?.time?.take(10).orEmpty()
        val dateRange = if (oldest.isNotEmpty() && newest.isNotEmpty()) "$oldest 至 $newest" else ""
        GachaReportDialog(
            title = "$titleText · 抽卡战报",
            dateRange = dateRange,
            totals = totals,
            upStats = upStats,
            hits = hits,
            pityCount = if (isAll) null else pityCount,
            piRoot = piRoot,
            initialDark = MaaTheme.isDark,
            colors = colors,
            onSave = { useDark ->
                pendingReportDark = useDark
                exportReportPng.launch("${titleText}_抽卡战报.png")
            },
            onDismiss = { showReportDialog = false },
        )
    }

    if (showSkippedDialog) {
        AlertDialog(
            onDismissRequest = { showSkippedDialog = false },
            title = { Text("无法解析的记录行 (${snapshot.skipped.size})") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "以下行在读取时未通过格式校验，已跳过显示（原文件未被修改）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot.skipped.take(50).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    if (snapshot.skipped.size > 50) {
                        Text(
                            "……其余 ${snapshot.skipped.size - 50} 行已省略",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSkippedDialog = false }) { Text("知道了") } },
        )
    }

    if (showAdd) AlertDialog(
        onDismissRequest = { if (!busy) showAdd = false },
        title = { Text("添加池子类型") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { if (it.length <= 40) newName = it; addError = "" },
                label = { Text("类型名称") },
                placeholder = { Text("例如：四合一") },
                singleLine = true,
                enabled = !busy,
                isError = addError.isNotEmpty(),
                supportingText = { if (addError.isNotEmpty()) Text(addError) },
            )
        },
        confirmButton = {
            TextButton(enabled = !busy && !running && newName.isNotBlank(), onClick = {
                action(onError = { addError = it }) {
                    check(!currentRunning) { "请先停止任务" }
                    val added = withContext(Dispatchers.IO) { store.addCategory(newName.trim()) }
                    selectedId = added.id; pool = ""; showAdd = false; newName = ""
                    loadRecords()
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { showAdd = false }) { Text("取消") } },
    )

    deleteTarget?.let { target ->
        fun remove(permanent: Boolean) = action(onError = { deleteError = it }) {
            check(!currentRunning) { "请先停止任务" }
            val backup = withContext(Dispatchers.IO) { store.deleteCategory(target.id, permanent) }
            deleteTarget = null
            loadRecords()
            messageIsError = false
            message = if (permanent) "已永久删除该类型的当前记录" else "类型已删除，记录已备份：$backup"
        }
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除“${target.name}”？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text("该类型包含 ${groupedRecords[target.id].orEmpty().size} 条抽卡记录。")
                    Text("删除并备份：记录保留在备份目录。\n永久删除：清除当前记录和自动报告，无法撤销。")
                    if (deleteError.isNotEmpty()) Text(deleteError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !busy && !running, onClick = { remove(false) }) { Text("删除并备份") }
                    TextButton(enabled = !busy && !running, onClick = { remove(true) }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    pending?.let { bundle ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("导入全部记录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text("文件包含 ${bundle.categories.size} 个类型、${bundle.records.size} 条记录。按类型合并，重复导入不会重复累计。请确认来自同一个游戏账号。")
                    if (importError.isNotEmpty()) Text(importError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && !running, onClick = {
                    action(onError = { importError = it }) {
                        check(!currentRunning) { "请先停止任务" }
                        val added = withContext(Dispatchers.IO) { store.importAll(bundle) }
                        pending = null; loadRecords(); messageIsError = false; message = "导入完成，新增 $added 条记录"
                    }
                }) { Text("合并导入") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } },
        )
    }

    MaaCardSurface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部紧凑工具栏（与 InventoryScreen 保持完全一致的轻量工具栏结构）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = MaaDesignTokens.Spacing.lg, end = MaaDesignTokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                if (collecting) {
                    MaaPulsingDot(color = colors.green.content)
                }
                val topSummary = when {
                    collecting -> "正在采集到「${category?.name ?: "记录"}」"
                    isAll -> "全部记录 · ${selectedRecords.size} 条（跨池汇总）"
                    category != null -> "${category.name} · ${selectedRecords.size} 条"
                    else -> "抽卡统计 · ${selectedRecords.size} 条"
                }
                Text(
                    text = topSummary,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (collecting) colors.green.content else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showReportDialog = true }, enabled = !busy && totals.pulls > 0) {
                    Icon(Icons.Outlined.Image, "生成战报长图", modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                }
                IconButton(onClick = { refresh() }, enabled = !busy) {
                    Icon(MaaIcons.Refresh, "刷新记录", modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                }
                Box {
                    IconButton(onClick = { more = true }) {
                        Icon(MaaIcons.More, "记录管理", modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("生成战报长图") },
                            enabled = !busy && totals.pulls > 0,
                            onClick = { more = false; showReportDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("添加类型") },
                            enabled = !busy && !running,
                            onClick = { more = false; newName = ""; addError = ""; showAdd = true },
                        )
                        DropdownMenuItem(
                            text = { Text("删除当前类型") },
                            enabled = !busy && !running && category != null,
                            onClick = { more = false; deleteError = ""; deleteTarget = category },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("导出全部 Excel") },
                            enabled = !busy && !running,
                            onClick = { more = false; exportExcel.launch("全部抽卡记录.xlsx") },
                        )
                        DropdownMenuItem(
                            text = { Text("导出全部 Markdown") },
                            enabled = !busy && !running,
                            onClick = { more = false; exportMd.launch("全部抽卡记录.md") },
                        )
                        DropdownMenuItem(
                            text = { Text("导入全部记录") },
                            enabled = !busy && !running,
                            onClick = { more = false; importFile.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(MaaDesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                // 1. 横向池子类型选择条
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryChip(
                            title = "全部",
                            subtitle = "${snapshot.records.size} 条 · 不计保底",
                            selected = isAll,
                            onClick = { selectedId = "all"; pool = ""; rarity = "全部"; query = "" },
                        )
                        snapshot.categories.forEach { c ->
                            val count = groupedRecords[c.id].orEmpty().size
                            val gt = gameTypes[c.id]
                            val sub = if (gt != null && gt != c.name) "游戏「$gt」· $count" else "$count 条"
                            CategoryChip(
                                title = c.name,
                                subtitle = sub,
                                selected = selectedId == c.id,
                                onClick = { selectedId = c.id; pool = ""; rarity = "全部"; query = "" },
                            )
                        }
                        if (!busy && !running) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { newName = ""; addError = ""; showAdd = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = maaCardBorder(),
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MaaIcons.Add,
                                        contentDescription = "添加池子类型",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 损坏/跳过行提示条
                if (snapshot.skipped.isNotEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showSkippedDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.warn.container,
                            border = BorderStroke(1.dp, colors.warn.content.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    MaaIcons.Warning,
                                    contentDescription = null,
                                    tint = colors.warn.content,
                                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                )
                                Text(
                                    "档案中有 ${snapshot.skipped.size} 行无法解析，已跳过显示（原文件未修改）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.warn.content,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "查看",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.warn.content,
                                )
                            }
                        }
                    }
                }

                // 3. 实时采集进度 & 操作反馈
                item {
                    AnimatedVisibility(visible = collecting) {
                        LiveProgressPanel(progress = liveProgress, stopOnKnown = stopOnKnown, colors = colors)
                    }
                }
                if (busy) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
                }
                if (message.isNotBlank()) {
                    item {
                        val containerColor = if (messageIsError) MaterialTheme.colorScheme.errorContainer else colors.green.container
                        val contentColor = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else colors.green.content
                        val borderColor = if (messageIsError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                        } else {
                            colors.green.content.copy(alpha = 0.3f)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = containerColor,
                            border = BorderStroke(1.dp, borderColor),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (messageIsError) {
                                    Icon(
                                        MaaIcons.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                    )
                                }
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                // 4. 核心统计 Bento 卡片（出狂率 + 65 软保底刻度）
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = maaCardBorder(),
                    ) {
                        Column(
                            Modifier.padding(MaaDesignTokens.Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                                verticalAlignment = Alignment.Top,
                            ) {
                                GachaMetric("总抽数", totals.pulls.toString(), if (pools.isNotEmpty()) "${pools.size} 个卡池" else "", Modifier.weight(1f))
                                GachaMetric("狂总数", totals.gold.toString(), "出狂率 ${totals.rate}", Modifier.weight(1f), accentColor = colors.gold.content)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                                verticalAlignment = Alignment.Top,
                            ) {
                                GachaMetric("平均出狂", totals.average, if (totals.gold > 0) "抽 / 狂" else "", Modifier.weight(1f))
                                val upAccent = when {
                                    upStats.total == 0 -> null
                                    upStats.wins * 2 >= upStats.total -> colors.green.content
                                    else -> MaterialTheme.colorScheme.error
                                }
                                val upSub = if (upStats.total > 0) upStats.summary else "常驻 / 未知池跳过"
                                GachaMetric("小保底不歪率", upStats.rate, upSub, Modifier.weight(1f), accentColor = upAccent)
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = maaCardBorder(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            "期望出UP",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            upStats.expectedUpSummary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    val expectedVal = upStats.expectedUpPulls
                                    val expectedColor = when {
                                        expectedVal == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        expectedVal <= 55.0 -> colors.green.content
                                        expectedVal <= 75.0 -> colors.gold.content
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Text(
                                        upStats.expectedUpText,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = expectedColor,
                                    )
                                }
                            }
                            if (!isAll) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.gold.content))
                                        Text("当前已垫", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "$pityCount / ${GachaStatistics.HARD_PITY} 抽",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.Bold,
                                            color = colors.gold.content,
                                        )
                                    }
                                    Text(
                                        "距保底 ${maxOf(0, GachaStatistics.HARD_PITY - pityCount)} 抽",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                PityProgressBar(pityCount = pityCount, colors = colors)
                                val latestGold = hits.firstOrNull()
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (latestGold != null) "上次出狂：${latestGold.record.name} · ${latestGold.record.time.take(10)}" else "暂无出狂记录",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${GachaStatistics.SOFT_PITY} 软保底",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. 视图切换头（概览 / 明细）
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (details) "全部明细 · ${filtered.size}" else "狂级出货 · ${hits.size}",
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                        )
                        MiniSegmentedToggle(
                            leftLabel = "概览",
                            rightLabel = "明细",
                            rightSelected = details,
                            colors = colors,
                            onSelect = { details = it },
                        )
                    }
                }

                if (!details) {
                    if (hits.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = MaaDesignTokens.Spacing.lg), contentAlignment = Alignment.Center) {
                                Text("暂无狂级记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(hits) { hit -> GachaHitCard(hit, colors, piRoot = piRoot, showCategory = isAll) }
                    }
                } else {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = maaCardBorder(),
                        ) {
                            Column(
                                modifier = Modifier.padding(MaaDesignTokens.Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        placeholder = { Text("搜索角色或卡池", maxLines = 1) },
                                        leadingIcon = { Icon(MaaIcons.Search, "搜索", modifier = Modifier.size(MaaDesignTokens.IconSize.sm)) },
                                        trailingIcon = {
                                            if (query.isNotEmpty()) {
                                                IconButton(onClick = { query = "" }) {
                                                    Icon(MaaIcons.Close, "清除", modifier = Modifier.size(MaaDesignTokens.IconSize.sm))
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1.2f),
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        GachaChoice(
                                            if (pool.isEmpty()) "全部卡池" else pool,
                                            listOf("" to "全部卡池") + pools.map { it to it },
                                            pool,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { pool = it }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    listOf("全部", "狂", "危", "普").forEach { r ->
                                        val on = rarity == r
                                        Surface(
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .clip(CircleShape)
                                                .clickable { rarity = r },
                                            shape = CircleShape,
                                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Text(
                                                r,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { groupByTen = !groupByTen },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Text(
                                                if (groupByTen) "按十连" else "逐条",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Icon(
                                                MaaIcons.ChevronDown,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = MaaDesignTokens.Spacing.xs, vertical = MaaDesignTokens.Spacing.xxsLg),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val countText = if (groupByTen) "${pullGroups.size} 组（共 ${filtered.size} 条）" else "${filtered.size} 条记录"
                            Text(
                                countText,
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (pool.isNotEmpty() || rarity != "全部" || query.isNotEmpty()) {
                                TextButton(
                                    onClick = { pool = ""; rarity = "全部"; query = "" },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Text("重置筛选", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = MaaDesignTokens.Spacing.lg), contentAlignment = Alignment.Center) {
                                Text("暂无匹配记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else if (groupByTen) {
                        items(pullGroups, key = { "${it.time}_${it.pool}_${it.records.first().index}" }) { group ->
                            val groupKey = "${group.time}_${group.pool}_${group.records.first().index}"
                            val isOpen = expandedGroups[groupKey] == true
                            TenPullGroupCard(
                                group = group,
                                expanded = isOpen,
                                colors = colors,
                                onToggle = { expandedGroups[groupKey] = !isOpen },
                            )
                        }
                    } else {
                        items(filtered) { row -> GachaDetailRow(row, colors) }
                    }
                }
            }

            // 底部拇指热区操作栏（采样模式切换 + 主采集/停止按钮）
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaaDesignTokens.Spacing.md, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val hint = when {
                        collecting -> null
                        running -> "其他任务运行中，请在任务结束后采集"
                        category == null || selectedId == "legacy" -> "请先在上方选择「新角色」「常驻」等具体类型后开始采集"
                        else -> "提示：采集前请手动打开游戏抽卡记录页，记录自动保存"
                    }
                    if (hint != null) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModeSegmentedSwitch(
                            stopOnKnown = stopOnKnown,
                            enabled = !busy && !running,
                            colors = colors,
                            onSelect = { stopOnKnown = it },
                            modifier = Modifier.weight(1f),
                        )

                        if (collecting) {
                            MaaButton(
                                onClick = onStop,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.weight(1.1f).height(46.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("停止采集", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        } else {
                            val canStart = !busy && !running && category != null && selectedId != "legacy"
                            MaaButton(
                                onClick = {
                                    if (!currentRunning && category != null) {
                                        collectStartMs = System.currentTimeMillis()
                                        liveProgress = null
                                        message = ""
                                        messageIsError = false
                                        onStart(selectedId, stopOnKnown)
                                    }
                                },
                                enabled = canStart,
                                modifier = Modifier.weight(1.1f).height(46.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) {
                                Icon(
                                    MaaIcons.Play,
                                    contentDescription = null,
                                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (category != null && selectedId != "legacy") "采集「${category.name}」" else "请先选池子",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) null else maaCardBorder(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFeatureSettings = "tnum"),
                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PityProgressBar(pityCount: Int, colors: GachaUiPalette) {
    val fraction = (pityCount.toFloat() / GachaStatistics.HARD_PITY).coerceIn(0f, 1f)
    val softFraction = GachaStatistics.SOFT_PITY.toFloat() / GachaStatistics.HARD_PITY
    val fillColor = if (pityCount >= GachaStatistics.SOFT_PITY) MaterialTheme.colorScheme.error else colors.gold.content
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.trackBg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fillColor),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = maxWidth * softFraction - 1.dp)
                .width(2.dp)
                .height(10.dp)
                .background(colors.softTick),
        )
    }
}

@Composable
private fun ModeSegmentedSwitch(
    stopOnKnown: Boolean,
    enabled: Boolean,
    colors: GachaUiPalette,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(MaaTheme.style.radii.button))
            .background(colors.segmentBg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegmentItem(
            title = "快速更新",
            selected = stopOnKnown,
            enabled = enabled,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = { onSelect(true) },
        )
        ModeSegmentItem(
            title = "完整补录",
            selected = !stopOnKnown,
            enabled = enabled,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = { onSelect(false) },
        )
    }
}

@Composable
private fun ModeSegmentItem(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveProgressPanel(progress: GachaProgress?, stopOnKnown: Boolean, colors: GachaUiPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = maaCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (progress?.page != null) "正在采集 · 第 ${progress.page} 页" else "正在启动采集…",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (progress != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "新增 +${progress.new}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = colors.green.content,
                        )
                        Text(
                            "· 已知 ${progress.known}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = colors.gold.content,
                trackColor = colors.trackBg,
            )
            val speed = progress?.secondsPerPage?.let { String.format(Locale.ROOT, "平均 %.1f s/页", it) }
            val statusHint = buildString {
                if (progress != null && progress.pages > 0) {
                    append("已扫 ${progress.pages} 页")
                    if (speed != null) append(" · $speed")
                } else {
                    append("正在识别当前游戏记录页…")
                }
                if (stopOnKnown) append(" · 命中已知记录后自动结束")
            }
            Text(statusHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniSegmentedToggle(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    colors: GachaUiPalette,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.segmentBg)
            .padding(2.dp),
    ) {
        listOf(false to leftLabel, true to rightLabel).forEach { (isRight, label) ->
            val on = rightSelected == isRight
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(isRight) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GachaHitCard(hit: GachaHit, colors: GachaUiPalette, piRoot: File, showCategory: Boolean = false) {
    val barColor = Color(GachaStatistics.intervalColor(hit.interval))
    val categoryPrefix = hit.record.poolCategory?.takeIf { showCategory && it.isNotBlank() }?.let { "$it · " }.orEmpty()
    val cachedAvatar = remember(hit.record.name) { SinnerAvatarCache.get(hit.record.name) }
    val avatarBitmap by produceState(cachedAvatar, hit.record.name, piRoot) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeSinnerAvatar(piRoot, hit.record.name) }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = maaCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.gold.container)
                    .border(1.dp, colors.gold.content.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                avatarBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = hit.record.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: Text(
                    text = hit.record.name.take(1),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.gold.content,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        hit.record.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    hit.upStatus?.let { status ->
                        val (label, bgColor, fgColor) = when (status) {
                            UpStatus.UP -> Triple("UP", colors.gold.container, colors.gold.content)
                            UpStatus.GUARANTEED -> Triple("大保底", colors.blue.container, colors.blue.content)
                            UpStatus.OFF -> Triple("歪", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(bgColor)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = fgColor,
                            )
                        }
                    }
                }
                Text(
                    "$categoryPrefix${hit.record.pool} · ${hit.record.time.take(16)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (hit.isFirstGold) {
                    Text("—", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("首狂·垫抽未知", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "${hit.interval} 抽",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = barColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(62.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(colors.trackBg),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((hit.interval.toFloat() / GachaStatistics.HARD_PITY).coerceIn(0.06f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(barColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TenPullGroupCard(group: GachaPullGroup, expanded: Boolean, colors: GachaUiPalette, onToggle: () -> Unit) {
    val bestRarity = group.best.rarity ?: "普"
    val tone = when (bestRarity) {
        "狂" -> colors.gold
        "危" -> colors.purple
        else -> colors.blue
    }
    val title = if (group.size == 1) {
        "单抽 · ${group.best.name}"
    } else {
        val prefix = if (group.size == 10) "十连" else "${group.size}连"
        val goldNames = group.records.filter { it.rarity == "狂" }.map { it.name }
        when {
            goldNames.isNotEmpty() -> "$prefix · 出 ${goldNames.joinToString("、")}"
            group.purple > 0 -> "$prefix · ${group.purple} 危"
            else -> "$prefix · 全普"
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (bestRarity == "狂") colors.goldCardBg else MaterialTheme.colorScheme.surface,
        border = if (bestRarity == "狂") BorderStroke(1.dp, colors.gold.content.copy(alpha = 0.45f)) else maaCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tone.container)
                        .border(1.dp, tone.content.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = group.best.name.take(1),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = tone.content,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${group.pool} · ${group.time}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    group.records.forEach { rec ->
                        val dotColor = when (rec.rarity) {
                            "狂" -> colors.gold.content
                            "危" -> colors.purple.content
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.5.dp)).background(dotColor))
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(2.dp))
                    listOf("狂", "危", "普").forEach { r ->
                        val names = group.records.filter { (it.rarity ?: "普") == r }.map { it.name }
                        if (names.isNotEmpty()) {
                            val formatted = names.groupingBy { it }.eachCount().entries.joinToString("、") { (n, c) ->
                                if (c > 1) "$n ×$c" else n
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RarityBadge(r, colors)
                                Text(
                                    formatted,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (r == "狂") FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RarityBadge(rarity: String, colors: GachaUiPalette) {
    val tone = when (rarity) {
        "狂" -> colors.gold
        "危" -> colors.purple
        else -> colors.blue
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tone.container)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(rarity, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tone.content)
    }
}

@Composable
private fun GachaMetric(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier,
    accentColor: Color? = null,
) {
    Column(modifier.padding(vertical = MaaDesignTokens.Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = accentColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun GachaChoice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        MaaOutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = MaaDesignTokens.Spacing.md, vertical = MaaDesignTokens.Spacing.sm),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(
                MaaIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (id == selected) FontWeight.SemiBold else null) },
                    onClick = { expanded = false; onSelect(id) },
                )
            }
        }
    }
}

@Composable
private fun GachaDetailRow(record: GachaRecord, colors: GachaUiPalette) {
    val rarity = record.rarity ?: "普"
    val tone = when (rarity) {
        "狂" -> colors.gold
        "危" -> colors.purple
        else -> colors.neutral
    }
    val containerColor = when (rarity) {
        "狂" -> colors.goldCardBg
        "危" -> colors.purpleCardBg
        else -> MaterialTheme.colorScheme.surface
    }
    val cardBorder = if (rarity == "普") maaCardBorder() else BorderStroke(1.dp, tone.content.copy(alpha = 0.35f))
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = cardBorder,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tone.container)
                    .border(1.dp, tone.content.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = record.name.take(1),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = tone.content,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(record.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    RarityBadge(rarity, colors)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = record.pool,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(record.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (record.index > 0) {
                Text(
                    text = "#${record.index}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun GachaReportDialog(
    title: String,
    dateRange: String,
    totals: GachaTotals,
    upStats: GachaUpStats,
    hits: List<GachaHit>,
    pityCount: Int?,
    piRoot: File,
    initialDark: Boolean,
    colors: GachaUiPalette,
    onSave: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var useDark by remember { mutableStateOf(initialDark) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(title, dateRange, totals, upStats, hits, pityCount, piRoot, useDark) {
        val next = withContext(Dispatchers.IO) {
            GachaReportRenderer.render(
                title = title,
                dateRange = dateRange,
                totals = totals,
                upStats = upStats,
                hits = hits,
                pityCount = pityCount,
                piRoot = piRoot,
                darkTheme = useDark,
            )
        }
        val prev = previewBitmap
        previewBitmap = next
        prev?.recycle()
    }
    DisposableEffect(Unit) {
        onDispose {
            previewBitmap?.recycle()
            previewBitmap = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = maaCardBorder(),
        ) {
            Column(
                modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("抽卡战报长图预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    MiniSegmentedToggle(
                        leftLabel = "浅色",
                        rightLabel = "深色",
                        rightSelected = useDark,
                        colors = colors,
                        onSelect = { useDark = it },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 440.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    previewBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "抽卡战报长图",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } ?: CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MaaOutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text("关闭")
                    }
                    MaaButton(
                        onClick = { onSave(useDark) },
                        enabled = previewBitmap != null,
                        modifier = Modifier.weight(1.3f).height(44.dp),
                    ) {
                        Text("保存长图 PNG", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
