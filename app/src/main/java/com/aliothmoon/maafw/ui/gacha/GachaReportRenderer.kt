package com.aliothmoon.maafw.ui.gacha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.aliothmoon.maafw.gacha.UpBannerCatalog
import com.aliothmoon.maafw.gacha.UpStatus
import java.io.File

/**
 * Offline long-poster generator for gacha arrest reports.
 * Uses plain Android Canvas so rendering is self-contained and respects the user's dark/light toggle.
 */
object GachaReportRenderer {

    private data class Palette(
        val bg: Int,
        val surface: Int,
        val border: Int,
        val text: Int,
        val sub: Int,
        val gold: Int,
        val goldBg: Int,
        val green: Int,
        val greenBg: Int,
        val red: Int,
        val redBg: Int,
    )

    private fun palette(dark: Boolean) = if (dark) {
        Palette(
            bg = Color.parseColor("#0B0C10"),
            surface = Color.parseColor("#16171E"),
            border = Color.parseColor("#2A2C36"),
            text = Color.parseColor("#F4F4F5"),
            sub = Color.parseColor("#91919B"),
            gold = Color.parseColor("#E5B769"),
            goldBg = Color.parseColor("#33E5B769"),
            green = Color.parseColor("#4ADE80"),
            greenBg = Color.parseColor("#284ADE80"),
            red = Color.parseColor("#F87171"),
            redBg = Color.parseColor("#28F87171"),
        )
    } else {
        Palette(
            bg = Color.parseColor("#F4F4F6"),
            surface = Color.parseColor("#FFFFFF"),
            border = Color.parseColor("#DCDCE3"),
            text = Color.parseColor("#141418"),
            sub = Color.parseColor("#63636B"),
            gold = Color.parseColor("#A67628"),
            goldBg = Color.parseColor("#20AD833E"),
            green = Color.parseColor("#059669"),
            greenBg = Color.parseColor("#1E059669"),
            red = Color.parseColor("#DC2626"),
            redBg = Color.parseColor("#1EDC2626"),
        )
    }

    fun render(
        title: String,
        dateRange: String,
        totals: GachaTotals,
        upStats: GachaUpStats,
        hits: List<GachaHit>,
        pityCount: Int?,
        piRoot: File,
        darkTheme: Boolean,
    ): Bitmap {
        val p = palette(darkTheme)
        val width = 1080
        val pad = 56f
        val innerW = width - pad * 2

        val cols = 4
        val gap = 20f
        val cellW = (innerW - gap * (cols - 1)) / cols
        val cellH = 236f
        val rows = if (hits.isEmpty()) 1 else (hits.size + cols - 1) / cols

        val headerH = 148f
        val kpiH = 152f
        val sectionTitleH = 74f
        val wallH = if (hits.isEmpty()) 120f else rows * cellH + (rows - 1) * gap
        val footerH = 108f
        val height = (pad + headerH + kpiH + sectionTitleH + wallH + footerH + pad).toInt()

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(p.bg)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = p.border
        }

        // Header
        var y = pad
        textPaint.color = p.sub
        textPaint.textSize = 24f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        canvas.drawText("MBCCTOOLS · ARREST REPORT", pad, y + 26f, textPaint)

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = p.text
        textPaint.textSize = 52f
        canvas.drawText(title, pad, y + 96f, textPaint)

        if (dateRange.isNotBlank()) {
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = p.sub
            textPaint.textSize = 26f
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(dateRange, width - pad, y + 96f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        y += headerH

        // 5-box KPI strip
        val kpiGap = 12f
        val kpiW = (innerW - kpiGap * 4) / 5f
        val kpis = listOf(
            Triple("总抽数", totals.pulls.toString(), p.text),
            Triple("狂总数", totals.gold.toString(), p.gold),
            Triple("平均出狂", totals.average, p.text),
            Triple("小保底不歪率", upStats.rate, if (upStats.total > 0) p.green else p.sub),
            Triple("期望出UP", upStats.expectedUpText, if (upStats.expectedUpPulls != null) p.gold else p.sub),
        )
        kpis.forEachIndexed { idx, (label, value, accent) ->
            val x = pad + idx * (kpiW + kpiGap)
            val rect = RectF(x, y, x + kpiW, y + kpiH)
            fillPaint.color = p.surface
            canvas.drawRoundRect(rect, 20f, 20f, fillPaint)
            canvas.drawRoundRect(rect, 20f, 20f, strokePaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = p.sub
            textPaint.textSize = 20f
            canvas.drawText(label, x + 16f, y + 44f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = accent
            textPaint.textSize = 34f
            canvas.drawText(value, x + 16f, y + 112f, textPaint)
        }
        y += kpiH + 24f

        // Section title
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = p.sub
        textPaint.textSize = 28f
        canvas.drawText("狂级出货档案（共 ${hits.size} 名）", pad, y + 32f, textPaint)
        y += sectionTitleH - 24f

        // Gold hit avatar wall
        if (hits.isEmpty()) {
            val rect = RectF(pad, y, width - pad, y + wallH)
            fillPaint.color = p.surface
            canvas.drawRoundRect(rect, 22f, 22f, fillPaint)
            canvas.drawRoundRect(rect, 22f, 22f, strokePaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = p.sub
            textPaint.textSize = 28f
            canvas.drawText("暂无狂级记录", width / 2f, y + wallH / 2f + 10f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        } else {
            val avatarCache = mutableMapOf<String, Bitmap?>()
            hits.forEachIndexed { idx, hit ->
                val row = idx / cols
                val col = idx % cols
                val cx = pad + col * (cellW + gap)
                val cy = y + row * (cellH + gap)
                val cardRect = RectF(cx, cy, cx + cellW, cy + cellH)
                fillPaint.color = p.surface
                canvas.drawRoundRect(cardRect, 22f, 22f, fillPaint)
                canvas.drawRoundRect(cardRect, 22f, 22f, strokePaint)

                // Avatar square (92x92 centered)
                val avaSize = 92f
                val ax = cx + (cellW - avaSize) / 2f
                val ay = cy + 20f
                val avaRect = RectF(ax, ay, ax + avaSize, ay + avaSize)
                val avatarBmp = avatarCache.getOrPut(hit.record.name) {
                    UpBannerCatalog.resolveAvatar(piRoot, hit.record.name)?.let { file ->
                        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                    }
                }
                if (avatarBmp != null) {
                    val clipPath = Path().apply { addRoundRect(avaRect, 20f, 20f, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    val src = Rect(0, 0, avatarBmp.width, avatarBmp.height)
                    canvas.drawBitmap(avatarBmp, src, avaRect, fillPaint)
                    canvas.restore()
                } else {
                    fillPaint.color = p.goldBg
                    canvas.drawRoundRect(avaRect, 20f, 20f, fillPaint)
                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textPaint.color = p.gold
                    textPaint.textSize = 40f
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(hit.record.name.take(1), ax + avaSize / 2f, ay + avaSize / 2f + 14f, textPaint)
                }

                // Name
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.color = p.text
                textPaint.textSize = 27f
                val displayName = if (hit.record.name.length > 6) hit.record.name.take(5) + "…" else hit.record.name
                canvas.drawText(displayName, cx + cellW / 2f, ay + avaSize + 38f, textPaint)

                // Pull + UP pill
                val pillColor = when {
                    hit.isFirstGold -> p.sub
                    hit.interval <= 30 -> p.green
                    hit.interval <= 55 -> p.gold
                    else -> p.red
                }
                val pillBg = when {
                    hit.isFirstGold -> p.surface
                    hit.interval <= 30 -> p.greenBg
                    hit.interval <= 55 -> p.goldBg
                    else -> p.redBg
                }
                val pullText = if (hit.isFirstGold) "首狂" else "${hit.interval} 抽"
                val upSuffix = when (hit.upStatus) {
                    UpStatus.UP -> " · UP"
                    UpStatus.GUARANTEED -> " · 大保底"
                    UpStatus.OFF -> " · 歪"
                    null -> ""
                }
                val badgeText = pullText + upSuffix
                val badgeRect = RectF(cx + 18f, ay + avaSize + 54f, cx + cellW - 18f, ay + avaSize + 94f)
                fillPaint.color = pillBg
                canvas.drawRoundRect(badgeRect, 12f, 12f, fillPaint)
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.color = pillColor
                textPaint.textSize = 22f
                canvas.drawText(badgeText, cx + cellW / 2f, badgeRect.bottom - 12f, textPaint)
            }
            avatarCache.values.forEach { it?.recycle() }
        }
        y += wallH + 36f

        // Footer
        canvas.drawLine(pad, y, width - pad, y, strokePaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textPaint.color = p.sub
        textPaint.textSize = 24f
        canvas.drawText("由 MBCCtools 本地离线生成", pad, y + 48f, textPaint)
        if (pityCount != null) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("当前已垫 $pityCount / ${GachaStatistics.HARD_PITY} 抽", width - pad, y + 48f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        return bmp
    }
}
