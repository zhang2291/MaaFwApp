package com.aliothmoon.maafw.notification

import android.app.NotificationManager
import android.os.Build
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.runner.ActiveExecution
import com.aliothmoon.maafw.runner.RunnerPhase

/** Live Update / 进度通知的一帧；不含 Context，单测直接对字段 */
data class RunProgressSnapshot(
    val title: RunProgressTitle,
    val contentText: String,
    val shortCriticalText: String?,
    val progress: Int,
    val indeterminate: Boolean,
    val barColor: Int,
)

enum class RunProgressTitle {
    Preparing,
    Running,
    Stopping,
}

val RunProgressTitle.stringRes: Int
    get() = when (this) {
        RunProgressTitle.Preparing -> R.string.notification_run_preparing
        RunProgressTitle.Running -> R.string.notification_run_running
        RunProgressTitle.Stopping -> R.string.notification_run_stopping
    }

object RunProgressSnapshots {
    const val PROGRESS_MAX = 1_000
    const val BAR_COLOR = 0xFF2196F3.toInt()

    fun from(
        phase: RunnerPhase,
        execution: ActiveExecution?,
        statusText: String?,
    ): RunProgressSnapshot {
        val title = titleOf(phase)
        val total = execution?.totalTaskCount ?: 0
        val done = execution?.completedTaskCount?.coerceIn(0, total.coerceAtLeast(0)) ?: 0
        val indeterminate = phase == RunnerPhase.Preparing || total <= 0
        val progressLabel = "$done/$total".takeIf { total > 0 }
        val taskLabel = execution?.currentTaskLabel
        val status = firstLine(statusText)?.takeIf { it != taskLabel && it != progressLabel }
        return RunProgressSnapshot(
            title = title,
            contentText = listOfNotNull(
                progressLabel,
                taskLabel,
                status,
            ).joinToString(" · "),
            shortCriticalText = progressLabel,
            progress = if (indeterminate) {
                0
            } else {
                progressValue(done, total)
            },
            indeterminate = indeterminate,
            barColor = BAR_COLOR,
        )
    }

    /**
     * 只计已结束的顶层任务。节点/OCR 回调没有可知总量，不能用它们估算任务内部百分比。
     */
    internal fun progressValue(done: Int, total: Int): Int {
        if (total <= 0) return 0
        val finished = done.coerceIn(0, total)
        return (finished.toLong() * PROGRESS_MAX / total).toInt()
    }

    private fun titleOf(phase: RunnerPhase): RunProgressTitle = when (phase) {
        RunnerPhase.Preparing -> RunProgressTitle.Preparing
        RunnerPhase.Stopping -> RunProgressTitle.Stopping
        else -> RunProgressTitle.Running
    }

    private fun firstLine(text: String?): String? =
        text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
}

/** 16 以下没有实时动态开关，请求会被忽略 */
fun NotificationManager.canRequestPromotedOngoing(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return true
    return canPostPromotedNotifications()
}
