package com.aliothmoon.maafw.ui.inventory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.inventory.*
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val InventoryIconCache = object : android.util.LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = maxOf(1, value.width * value.height * 4)
}

@Volatile
private var CachedInventorySnapshot: Pair<Long, InventorySnapshot>? = null

private fun decodeInventoryIcon(path: String): ImageBitmap? {
    InventoryIconCache.get(path)?.let { return it }
    val file = File(path)
    if (!file.isFile) return null
    val bmp = runCatching {
        if (file.length() <= 128 * 1024L) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 144).coerceAtLeast(1)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        }
    }.getOrNull() ?: return null
    InventoryIconCache.put(path, bmp)
    return bmp
}

private val RaritySections = listOf(
    "狂" to "狂级核心材料",
    "危" to "危级养成材料",
    "普" to "普级与基础道具",
)

private data class CoreAssetMetric(
    val name: String,
    val displayValue: String,
    val pullHint: String? = null,
    val isUnrecognized: Boolean = false,
    val isAccent: Boolean = false,
    val delta: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(
    running: Boolean,
    collecting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InventoryStore(File(AppPaths.ROOT, "data"), File(AppPaths.ROOT, AppFiles.PI_DIR)) }
    var snapshot by remember { mutableStateOf<InventorySnapshot?>(CachedInventorySnapshot?.second) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<InventoryHistoryEntry>?>(null) }
    var baseline by remember { mutableStateOf<InventorySnapshot?>(null) }
    var baselineFileName by remember { mutableStateOf<String?>(null) }
    var baselineLabel by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<InventoryHistoryEntry?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<InventoryHistoryEntry?>(null) }
    var showUnchanged by rememberSaveable { mutableStateOf(false) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    fun refresh(force: Boolean = false) = action {
        val (loaded, messageText) = withContext(Dispatchers.IO) {
            val mtime = store.latestLastModified()
            val cached = CachedInventorySnapshot
            if (!force && mtime > 0L && cached != null && cached.first == mtime) {
                cached.second to ""
            } else {
                val fresh = store.load()
                if (fresh != null) CachedInventorySnapshot = mtime to fresh
                else CachedInventorySnapshot = null
                fresh to (if (fresh == null) "暂无仓库记录，请先打开游戏首页，再点击下方“开始识别”。" else "")
            }
        }
        snapshot = loaded
        message = messageText
    }

    var wasCollecting by remember { mutableStateOf(false) }
    LaunchedEffect(collecting) {
        if (collecting) {
            wasCollecting = true
        } else {
            refresh(force = wasCollecting)
            wasCollecting = false
        }
    }

    LaunchedEffect(snapshot) {
        val items = snapshot?.items ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            items.take(36).forEach { item ->
                val path = item.image?.path ?: return@forEach
                decodeInventoryIcon(path)
            }
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = snapshot
    suspend fun reloadHistoryEntries(): List<InventoryHistoryEntry> {
        val currentRunId = data?.runId.orEmpty()
        return withContext(Dispatchers.IO) { store.history() }
            .filter { currentRunId.isBlank() || it.fileName != "$currentRunId.json" }
    }
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
    suspend fun reloadHistoryEntries(): List<InventoryHistoryEntry> {
        val currentRunId = data?.runId.orEmpty()
        return withContext(Dispatchers.IO) { store.history() }
            .filter { currentRunId.isBlank() || it.fileName != "$currentRunId.json" }
    }
        if (uri != null && data != null) action {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data.markdown()) }
                    ?: error("无法写入文件")
            }
            message = "仓库汇总已导出"
        }
    }

    val data = snapshot
    suspend fun reloadHistoryEntries(): List<InventoryHistoryEntry> {
        val currentRunId = data?.runId.orEmpty()
        return withContext(Dispatchers.IO) { store.history() }
            .filter { currentRunId.isBlank() || it.fileName != "$currentRunId.json" }
    }
    var rarityFilter by rememberSaveable { mutableStateOf("全部") }
    val goldCount = remember(data) { data?.items.orEmpty().count { it.rarity == "狂" } }
    val purpleCount = remember(data) { data?.items.orEmpty().count { it.rarity == "危" } }
    val blueCount = remember(data) { data?.items.orEmpty().count { it.rarity == "普" } }
    val filtered = remember(data, rarityFilter, query) {
        data?.items.orEmpty().filter {
            (rarityFilter == "全部" || it.rarity == rarityFilter) &&
                (query.isBlank() || it.name.contains(query, true))
        }
    }
    val groupedByRarity = remember(filtered, rarityFilter) {
        if (rarityFilter == "全部") {
            RaritySections.mapNotNull { (rarity, title) ->
                val list = filtered.filter { it.rarity == rarity }
                if (list.isEmpty()) null else Triple(rarity, title, list)
            }
        } else {
            emptyList()
        }
    }

    val previous = baseline
    val allComparisons = remember(previous, data) {
        if (previous != null && data != null) InventoryComparison.compare(previous, data) else emptyList()
    }
    val currencyDeltas = remember(allComparisons) {
        allComparisons
            .filter { it.key.startsWith("currency:") && it.delta != null && it.delta != 0L }
            .associate { it.key.removePrefix("currency:") to it.delta }
    }
    val comparison = remember(allComparisons, showUnchanged, query) {
        allComparisons.filter { (showUnchanged || it.delta != 0L) && (query.isBlank() || it.name.contains(query, true)) }
    }

    val coreMetrics = remember(data, currencyDeltas) {
        if (data == null) emptyList()
        else listOf("异方晶", "狄斯币", "搜索令", "都市搜索令").map { name ->
            val item = data.items.firstOrNull { it.name == name }
            val rawCount = if (name in data.currencies) data.currencies[name] else item?.quantity
            val isUnrecognized = rawCount == null && (name in data.currencies || item == null)
            val displayValue = when {
                name == "狄斯币" || name == "异方晶" ->
                    rawCount?.let { String.format(Locale.ROOT, "%,d", it) } ?: "未识别"
                name == "搜索令" -> rawCount?.let { "$it 张" } ?: "未识别"
                else -> rawCount?.toString() ?: "未识别 / 0"
            }
            val pullHint = if (name == "异方晶" && rawCount != null) "≈ ${rawCount / 180} 抽" else null
            CoreAssetMetric(
                name = name,
                displayValue = displayValue,
                pullHint = pullHint,
                isUnrecognized = isUnrecognized,
                isAccent = name == "异方晶",
                delta = currencyDeltas[name],
            )
        }
    }

    history?.let { entries ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("选择历史快照") },
            text = {
                if (entries.isEmpty()) {
                    Text("暂无可对比的历史快照，请先完成另一次仓库识别。")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listItems(entries, key = { it.fileName }) { entry ->
                            val statusText = if (entry.status == "complete") "完整快照" else "部分识别快照"
                            val timeText = formatCapturedAt(entry.capturedAt)
                            val primaryTitle = entry.label.ifBlank { timeText }
                            val subTitle = if (entry.label.isNotBlank()) "$timeText · $statusText" else statusText
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !busy) {
                                        action {
                                            baseline = withContext(Dispatchers.IO) { store.loadHistory(entry.fileName) }
                                            baselineFileName = entry.fileName
                                            baselineLabel = entry.label
                                            showUnchanged = false
                                            history = null
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = maaCardBorder(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            primaryTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            subTitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        enabled = !busy,
                                        onClick = {
                                            renameTarget = entry
                                            renameInput = entry.label
                                            renameError = ""
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            MaaIcons.Edit,
                                            contentDescription = "重命名快照",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                        )
                                    }
                                    IconButton(
                                        enabled = !busy,
                                        onClick = { deleteTarget = entry },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            MaaIcons.Delete,
                                            contentDescription = "删除快照",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text("关闭") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) renameTarget = null },
            title = { Text("重命名历史快照") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text(
                        "快照时间：${formatCapturedAt(target.capturedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = {
                            if (it.length <= 40) {
                                renameInput = it
                                renameError = ""
                            }
                        },
                        label = { Text("快照名称") },
                        placeholder = { Text("例如：抽席宁前存货（留空恢复时间）") },
                        singleLine = true,
                        enabled = !busy,
                        isError = renameError.isNotEmpty(),
                        supportingText = { if (renameError.isNotEmpty()) Text(renameError) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        action {
                            val savedLabel = try {
                                withContext(Dispatchers.IO) { store.renameHistory(target.fileName, renameInput) }
                            } catch (e: Exception) {
                                renameError = e.message ?: "重命名失败"
                                return@action
                            }
                            if (baselineFileName == target.fileName) {
                                baselineLabel = savedLabel
                            }
                            history = reloadHistoryEntries()
                            renameTarget = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        val displayTitle = target.label.ifBlank { formatCapturedAt(target.capturedAt) }
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除历史快照？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text(
                        "确定删除“$displayTitle”（${formatCapturedAt(target.capturedAt)}）吗？",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "删除后该历史快照将被永久移除，无法撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        action {
                            withContext(Dispatchers.IO) { store.deleteHistory(target.fileName) }
                            if (baselineFileName == target.fileName) {
                                baseline = null
                                baselineFileName = null
                                baselineLabel = ""
                            }
                            history = reloadHistoryEntries()
                            deleteTarget = null
                            message = "已删除历史快照"
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    MaaCardSurface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部轻量工具栏（不与 TasksScreen 的分段标签重复标题）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = MaaDesignTokens.Spacing.lg, end = MaaDesignTokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                if (collecting) {
                    MaaPulsingDot(color = MaaTheme.palette.success.content)
                }
                val headerMeta = when {
                    collecting -> "正在识别仓库，结束后自动刷新"
                    data != null -> "${formatCompactTime(data.capturedAt)} 快照 · ${data.items.size} 项"
                    else -> "尚未采集仓库"
                }
                Text(
                    text = headerMeta,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (collecting) MaaTheme.palette.success.content else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = !busy, onClick = { refresh(force = true) }) {
                    Icon(MaaIcons.Refresh, contentDescription = "刷新", modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(MaaIcons.More, contentDescription = "导出", modifier = Modifier.size(MaaDesignTokens.IconSize.md))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("导出 JSON 记录") },
                            enabled = data != null && !busy,
                            onClick = { menu = false; exportJson.launch("仓库资源.json") },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 Markdown 汇总") },
                            enabled = data != null && !busy,
                            onClick = { menu = false; exportMd.launch("仓库资源.md") },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(MaaDesignTokens.Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                if (busy) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                    }
                }

                if (message.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = maaCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    MaaIcons.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                )
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                if (data != null) {
                    // C 风格核心资产 2×2 Bento 总览（含异方晶约合抽数 & 对比态增减）
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                            coreMetrics.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                                ) {
                                    row.forEach { metric ->
                                        InventoryMetricCard(metric = metric, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            val disCount = data.currencies["狄斯币"]
                            val essenceCount = data.currencies["狂厄精粹"]
                                ?: data.currencies["狂乱精粹"]
                                ?: data.items.firstOrNull { it.name == "狂厄精粹" || it.name == "狂乱精粹" }?.quantity
                            if (disCount != null && disCount > 0L) {
                                val lv90Text = buildString {
                                    if (essenceCount != null && essenceCount > 0L) {
                                        append(
                                            String.format(
                                                Locale.ROOT,
                                                "通用估算：当前精粹够 %.1f 名、狄斯币够 %.1f 名狂级升至 90 级（不依赖角色表）",
                                                essenceCount / 2_400_000.0,
                                                disCount / 2_000_000.0,
                                            ),
                                        )
                                    } else {
                                        append(
                                            String.format(
                                                Locale.ROOT,
                                                "通用估算：当前狄斯币约够 %.1f 名狂级升至 90 级（按常规养成消耗估算）",
                                                disCount / 2_000_000.0,
                                            ),
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaaTheme.palette.info.container,
                                    border = BorderStroke(0.75.dp, MaaTheme.palette.info.content.copy(alpha = 0.28f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            MaaIcons.Info,
                                            contentDescription = null,
                                            tint = MaaTheme.palette.info.content,
                                            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                        )
                                        Text(
                                            lv90Text,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                            color = MaaTheme.palette.info.content,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 搜索框 + 稀有度筛选胶囊
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("搜索材料或道具名称", maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        MaaIcons.Search,
                                        contentDescription = "搜索",
                                        modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                    )
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(
                                                MaaIcons.Close,
                                                contentDescription = "清除搜索",
                                                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(MaaTheme.style.radii.inner),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RarityFilterPill(
                                    label = "全部",
                                    count = data.items.size,
                                    selected = rarityFilter == "全部",
                                    dotColor = null,
                                    onClick = { rarityFilter = "全部" },
                                )
                                RarityFilterPill(
                                    label = "狂",
                                    count = goldCount,
                                    selected = rarityFilter == "狂",
                                    dotColor = rarityTone("狂").content,
                                    onClick = { rarityFilter = "狂" },
                                )
                                RarityFilterPill(
                                    label = "危",
                                    count = purpleCount,
                                    selected = rarityFilter == "危",
                                    dotColor = rarityTone("危").content,
                                    onClick = { rarityFilter = "危" },
                                )
                                RarityFilterPill(
                                    label = "普",
                                    count = blueCount,
                                    selected = rarityFilter == "普",
                                    dotColor = rarityTone("普").content,
                                    onClick = { rarityFilter = "普" },
                                )
                            }
                        }
                    }

                    if (previous != null) {
                        // 单入口历史对比面板（消除原本 3 处重复退出按钮）
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(MaaTheme.style.radii.card),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = maaCardBorder(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            MaaIcons.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                        )
                                        Text(
                                            "${baselineLabel.ifBlank { formatCompactTime(previous.capturedAt) }} → ${formatCompactTime(data.capturedAt)}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (showUnchanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showUnchanged = !showUnchanged },
                                        ) {
                                            Text(
                                                text = if (showUnchanged) "显示全部" else "仅看变化",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (showUnchanged) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                baseline = null
                                                baselineFileName = null
                                                baselineLabel = ""
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                MaaIcons.Close,
                                                contentDescription = "退出对比",
                                                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                                            )
                                        }
                                    }
                                    Text(
                                        "变化 = 当前 − 历史；缺失、未知和冲突不按 0 计。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (previous.status != "complete" || data.status != "complete") {
                                        Text(
                                            "包含部分识别快照，仅比较两侧已确认的数量。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaaTheme.palette.warning.content,
                                        )
                                    }
                                }
                            }
                        }

                        if (comparison.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = MaaDesignTokens.Spacing.lg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (query.isBlank()) "已确认物品没有数量变化" else "没有匹配的变化记录",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            items(comparison, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { row ->
                                InventoryChangeRow(row)
                            }
                        }
                    } else if (filtered.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = MaaDesignTokens.Spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "没有匹配的物品",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (rarityFilter == "全部") {
                        groupedByRarity.forEach { (rarity, title, groupItems) ->
                            item(key = "header:$rarity", span = { GridItemSpan(maxLineSpan) }) {
                                RaritySectionHeader(rarity = rarity, title = title, count = groupItems.size)
                            }
                            items(groupItems, key = { "${it.category}:${it.id}" }) { item ->
                                InventoryCell(item)
                            }
                        }
                    } else {
                        items(filtered, key = { "${it.category}:${it.id}" }) { item ->
                            InventoryCell(item)
                        }
                    }
                }
            }

            // B 风格底部拇指热区操作栏
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaaDesignTokens.Spacing.md, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (collecting) {
                        MaaButton(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.weight(1.35f).height(46.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("停止识别", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    } else {
                        MaaButton(
                            onClick = onStart,
                            enabled = !running && !busy,
                            modifier = Modifier.weight(1.35f).height(46.dp),
                        ) {
                            Icon(
                                MaaIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("开始识别", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }

                    MaaOutlinedButton(
                        enabled = !busy && !collecting,
                        onClick = {
                            action {
                                history = reloadHistoryEntries()
                            }
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            MaaIcons.History,
                            contentDescription = null,
                            modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (previous == null) "历史对比" else "更换快照",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (previous != null) {
                        MaaOutlinedButton(
                            onClick = {
                                baseline = null
                                baselineFileName = null
                                baselineLabel = ""
                            },
                            modifier = Modifier.height(46.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(
                                MaaIcons.Close,
                                contentDescription = "退出对比",
                                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryMetricCard(metric: CoreAssetMetric, modifier: Modifier = Modifier) {
    val goldTone = MaaTheme.palette.warning
    val delta = metric.delta
    Surface(
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(14.dp),
        color = when {
            metric.isUnrecognized -> MaterialTheme.colorScheme.surfaceContainerLow
            metric.isAccent && !MaaTheme.isDark -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (metric.isAccent && !metric.isUnrecognized) {
            BorderStroke(1.dp, goldTone.content.copy(alpha = 0.45f))
        } else {
            maaCardBorder()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaaDesignTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = metric.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (metric.pullHint != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(goldTone.container)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = metric.pullHint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = goldTone.content,
                            maxLines = 1,
                        )
                    }
                } else if (delta != null && delta != 0L) {
                    DeltaBadge(delta)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = metric.displayValue,
                    style = (if (metric.isUnrecognized) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
                        .copy(fontFeatureSettings = "tnum"),
                    fontWeight = if (metric.isUnrecognized) FontWeight.Normal else FontWeight.Bold,
                    color = when {
                        metric.isUnrecognized -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        metric.isAccent -> goldTone.content
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (metric.pullHint != null && delta != null && delta != 0L) {
                    Spacer(Modifier.width(6.dp))
                    DeltaBadge(delta)
                }
            }
        }
    }
}

@Composable
private fun RarityFilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    dotColor: Color?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) null else BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dotColor != null && !selected) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            }
            Text(
                text = "$label ($count)",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RaritySectionHeader(rarity: String, title: String, count: Int) {
    val tone = rarityTone(rarity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone.content))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 项",
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InventoryCell(item: InventoryItem) {
    val iconPath = item.image?.path
    val cachedBitmap = remember(iconPath) { iconPath?.let { InventoryIconCache.get(it) } }
    val bitmap by produceState(initialValue = cachedBitmap, key1 = iconPath) {
        if (value == null && iconPath != null) {
            value = withContext(Dispatchers.IO) { decodeInventoryIcon(iconPath) }
        }
    }
    val tone = rarityTone(item.rarity)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = maaCardBorder(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(MaaDesignTokens.Spacing.sm)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone.content),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, tone.content.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                    } ?: Icon(
                        Icons.Outlined.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.quantityText,
                    style = (if (item.conflicts.isEmpty()) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall)
                        .copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = if (item.conflicts.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (item.unknown) "未识别 · ${item.id.substringAfterLast(':').take(6)}" else item.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InventoryChangeRow(row: InventoryComparisonRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaTheme.style.radii.card),
        color = MaterialTheme.colorScheme.surface,
        border = maaCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "历史 ${row.previousText} → 当前 ${row.currentText}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.reason.isNotBlank()) {
                    Text(
                        row.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val delta = row.delta
            if (delta != null) {
                DeltaBadge(delta)
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaaTheme.palette.neutral.container)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        row.deltaText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaaTheme.palette.neutral.content,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaBadge(delta: Long) {
    val (bg, fg) = when {
        delta > 0 -> MaaTheme.palette.success.container to MaaTheme.palette.success.content
        delta < 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else -> MaaTheme.palette.neutral.container to MaaTheme.palette.neutral.content
    }
    val text = if (delta > 0) "+$delta" else delta.toString()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun rarityTone(rarity: String): MaaTone = when (rarity) {
    "狂" -> MaaTheme.palette.warning
    "危" -> MaaTheme.palette.violet
    else -> MaaTheme.palette.info
}

private val InventoryItem.rarity: String
    get() = when {
        name.contains("狂级") || name.contains("超阶") || name.contains("三阶") || name.contains("意识晶核") || name.contains("沉雾泪晶") || name.contains("暮辉晶") || name.contains("曲铜晶") || name.contains("狂乱") -> "狂"
        name.contains("危级") || name.contains("高阶") || name.contains("二阶") || name.contains("异化") || name.contains("赤晶") || name.contains("精粹") -> "危"
        name.contains("普级") || name.contains("低阶") || name.contains("一阶") || name.contains("中阶") || name.contains("密令") || name.contains("指令") -> "普"
        else -> "普"
    }

private fun formatCapturedAt(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrDefault(value)

private fun formatCompactTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)
