package me.rerere.rikkahub.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
    ],
    version = 25,
    autoMigrations = []
    // 所有迁移均由 DataSourceModule.kt 中 addMigrations() 注册的手动 Migration 处理
    // Room AutoMigration 对每个 to-schema 与当前 Entity 做 diff，
    // 历史 schema 中存在的 custom_system_prompt 字段已在 v23→v24 手动删除，
    // 若保留 AutoMigration 条目 Room KSP 会报 "未声明 @DeleteColumn" 错误
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    companion object {
        const val VERSION = 25

        // v21→v22: 仅新增 workspace_cwd 列
        val Migration_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN workspace_cwd TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {}
            }
        }

        // [FORK] v18→v19 手动迁移：修复旧版 nullable 字段约束
        val Migration_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 仅 try-catch 新增列，兼容可能已存在的字段（旧备份恢复场景）
                try {
                    db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN suggestions TEXT NOT NULL DEFAULT '[]'")
                } catch (_: Exception) {}
            }
        }

        // [FORK] v22→v23 手动迁移：替代 AutoMigration(spec=Migration_22_23)
        // 在 fork 中同时需要处理 DeleteColumn + 新增列，AutoMigration 处理不了
        val Migration_22_23_Fork = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 删除 workspaces.shell_enabled（重建表方式）
                try {
                    db.execSQL("ALTER TABLE workspaces RENAME TO workspaces_old")
                    db.execSQL("""
                        CREATE TABLE workspaces (
                            `id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL,
                            `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL, `last_access_at` INTEGER,
                            `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("INSERT OR IGNORE INTO workspaces SELECT id,name,root,shell_status,created_at,updated_at,last_access_at,tool_approvals FROM workspaces_old")
                    db.execSQL("DROP TABLE workspaces_old")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
                } catch (_: Exception) {}
                // 删除 ConversationEntity.custom_system_prompt（重建表方式）
                try {
                    db.execSQL("ALTER TABLE ConversationEntity RENAME TO ConversationEntity_old")
                    db.execSQL("""
                        CREATE TABLE ConversationEntity (
                            `id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                            `title` TEXT NOT NULL, `nodes` TEXT NOT NULL,
                            `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL,
                            `suggestions` TEXT NOT NULL DEFAULT '[]',
                            `is_pinned` INTEGER NOT NULL DEFAULT 0,
                            `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
                            `lorebook_ids` TEXT NOT NULL DEFAULT '[]',
                            `conversation_tag_id` TEXT DEFAULT '',
                            `workspace_cwd` TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("INSERT OR IGNORE INTO ConversationEntity SELECT id,assistant_id,title,nodes,create_at,update_at,suggestions,is_pinned,mode_injection_ids,lorebook_ids,conversation_tag_id,workspace_cwd FROM ConversationEntity_old")
                    db.execSQL("DROP TABLE ConversationEntity_old")
                } catch (_: Exception) {}
            }
        }
    }
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
