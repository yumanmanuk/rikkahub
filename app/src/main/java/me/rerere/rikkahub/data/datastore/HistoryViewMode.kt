package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

// [FORK] 历史页视图模式：时间视图（默认）和标签视图
@Serializable
enum class HistoryViewMode {
    TIMELINE,
    TAG,
}
