package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v24→v25：
//   ConversationEntity: 新增 folder_id 列
//   conversation_folder: 新建表（含 assistant_id 索引）
//   message_node: 新增 is_pinned 列（固定到上下文）
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 新增 folder_id 列，兼容旧备份恢复场景（列已存在时忽略）
        try {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''")
        } catch (_: Exception) {}

        // 创建 conversation_folder 表（如已存在则跳过）
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_folder` (
                    `id` TEXT NOT NULL,
                    `assistant_id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sort_index` INTEGER NOT NULL DEFAULT 0,
                    `create_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` ON `conversation_folder` (`assistant_id`)"
            )
        } catch (_: Exception) {}

        // 新增 message_node.is_pinned 列（固定到上下文，旧备份恢复场景列已存在时忽略）
        try {
            db.execSQL("ALTER TABLE message_node ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) {}
    }
}
