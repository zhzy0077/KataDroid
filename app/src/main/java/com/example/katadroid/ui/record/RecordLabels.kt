package com.example.katadroid.ui.record

import android.content.res.Resources
import com.example.katadroid.R

internal fun RecordUiState.displayName(resources: Resources): String = when {
    isDemo -> resources.getString(R.string.demo_record)
    recordName.isBlank() || recordName == "自由对弈" -> resources.getString(R.string.free_play)
    else -> recordName
}

internal fun RecordNode.moveLabel(resources: Resources): String = point?.label ?: resources.getString(
    if (parentId == null) R.string.start_position else if (color == 0) R.string.comment_node else R.string.pass_move)

internal fun RecordNode.branchLabel(resources: Resources): String {
    // Migrate labels saved by the pre-localization version without changing SGF content.
    val key = mapOf("主线" to "main", "布局变化" to "opening", "左下变化" to "lower", "中腹变化" to "middle", "上方变化" to "upper", "右上变化" to "right", "右上变化 · 子分支" to "right-child", "左侧变化" to "left", "左侧变化 · 子分支" to "left-child", "B 分支" to "b", "C 分支" to "c", "B 分支 · 子分支" to "b-child")[branch] ?: branch
    val parent = key.removeSuffix("-child")
    val label = when (parent) {
        "main" -> resources.getString(R.string.branch_main)
        "opening" -> resources.getString(R.string.branch_opening)
        "lower" -> resources.getString(R.string.branch_lower)
        "middle", "center" -> resources.getString(R.string.branch_middle)
        "upper" -> resources.getString(R.string.branch_upper)
        "right" -> resources.getString(R.string.branch_right)
        "left" -> resources.getString(R.string.branch_left)
        "b", "c" -> resources.getString(R.string.branch_letter, parent.uppercase())
        else -> resources.getString(R.string.branch_number, kotlin.math.abs(lane))
    }
    return if (key.endsWith("-child")) resources.getString(R.string.branch_child, label) else label
}
