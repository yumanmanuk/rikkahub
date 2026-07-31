package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v25→v26：
//   message_node: 新增 pinned_message_id 列（固定到上下文锚定的具体分支消息 id）
//   旧数据 is_pinned=1 但 pinned_message_id 为 NULL 时，
//   由 ConversationRepository.loadMessageNodes 在读取时回退到 selectIndex 对应消息（向后兼容），
//   此处不做任何数据回填/删除，仅新增可空列，保证零数据丢失风险。
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 新增列，兼容旧备份恢复场景（列已存在时忽略 duplicate 错误）
        try {
            db.execSQL("ALTER TABLE message_node ADD COLUMN `pinned_message_id` TEXT")
        } catch (_: Exception) {}
    }
}
