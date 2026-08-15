package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// [FORK] v23→v24: 手动迁移替代 AutoMigration(spec = Migration_22_23::class)
// 根本原因：Room AutoMigration 在 @DeleteColumn 触发表重建的同时，还会对 "新增列"
// 额外生成 ALTER TABLE ADD COLUMN 语句，导致 duplicate column name 崩溃。
// 两张表的变更：
//   ConversationEntity: 删除 custom_system_prompt, 新增 conversation_params
//   workspaces        : 删除 shell_enabled
// 改用表重建（CREATE→INSERT→DROP→RENAME）统一处理增删列，
// 并对每列检查是否存在，兼容 fork 旧数据库中可能已含 conversation_params 的情况。
val Migration_23_24_Fork = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateConversationEntity(db)
        migrateWorkspaces(db)
    }

    private fun migrateConversationEntity(db: SupportSQLiteDatabase) {
        // 查询实际存在的列，兼容 fork 旧版本数据库
        val existingCols = mutableSetOf<String>()
        db.query("SELECT name FROM pragma_table_info('ConversationEntity')").use { c ->
            while (c.moveToNext()) existingCols.add(c.getString(0))
        }

        // 创建新表（完整 v24 schema）
        db.execSQL("DROP TABLE IF EXISTS ConversationEntity_new")
        db.execSQL(
            """
            CREATE TABLE ConversationEntity_new (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                `title` TEXT NOT NULL,
                `nodes` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `update_at` INTEGER NOT NULL,
                `suggestions` TEXT NOT NULL DEFAULT '[]',
                `is_pinned` INTEGER NOT NULL DEFAULT 0,
                `conversation_params` TEXT NOT NULL DEFAULT '{}',
                `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
                `lorebook_ids` TEXT NOT NULL DEFAULT '[]',
                `conversation_tag_id` TEXT DEFAULT '',
                `workspace_cwd` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // 对可能不存在的列使用默认值，对已有 conversation_params 的旧库保留原值
        val assistantIdExpr = if ("assistant_id" in existingCols) "assistant_id" else "'0950e2dc-9bd5-4801-afa3-aa887aa36b4e'"
        val suggestionsExpr = if ("suggestions" in existingCols) "suggestions" else "'[]'"
        val isPinnedExpr = if ("is_pinned" in existingCols) "is_pinned" else "0"
        val conversationParamsExpr = if ("conversation_params" in existingCols) "COALESCE(conversation_params, '{}')" else "'{}'"
        val modeInjectionIdsExpr = if ("mode_injection_ids" in existingCols) "mode_injection_ids" else "'[]'"
        val lorebookIdsExpr = if ("lorebook_ids" in existingCols) "lorebook_ids" else "'[]'"
        val conversationTagIdExpr = if ("conversation_tag_id" in existingCols) "conversation_tag_id" else "NULL"
        val workspaceCwdExpr = if ("workspace_cwd" in existingCols) "workspace_cwd" else "''"

        db.execSQL(
            """
            INSERT OR IGNORE INTO ConversationEntity_new
                (id, assistant_id, title, nodes, create_at, update_at, suggestions,
                 is_pinned, conversation_params, mode_injection_ids, lorebook_ids,
                 conversation_tag_id, workspace_cwd)
            SELECT
                id,
                $assistantIdExpr,
                title,
                nodes,
                create_at,
                update_at,
                $suggestionsExpr,
                $isPinnedExpr,
                $conversationParamsExpr,
                $modeInjectionIdsExpr,
                $lorebookIdsExpr,
                $conversationTagIdExpr,
                $workspaceCwdExpr
            FROM ConversationEntity
            """.trimIndent()
        )

        db.execSQL("DROP TABLE ConversationEntity")
        db.execSQL("ALTER TABLE ConversationEntity_new RENAME TO ConversationEntity")
    }

    private fun migrateWorkspaces(db: SupportSQLiteDatabase) {
        // workspaces 表可能不存在（从未安装过 workspace 功能的旧版本用户）
        // 无论表是否存在，最终都需要满足 v24 schema（含索引），否则 Room 验证会崩溃
        val tableExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='workspaces'"
        ).use { it.moveToFirst() }

        if (!tableExists) {
            // 表不存在：直接创建完整的 v24 表和索引
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS workspaces (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `root` TEXT NOT NULL,
                    `shell_status` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `last_access_at` INTEGER,
                    `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
            return
        }

        val existingCols = mutableSetOf<String>()
        db.query("SELECT name FROM pragma_table_info('workspaces')").use { c ->
            while (c.moveToNext()) existingCols.add(c.getString(0))
        }

        // 创建新表（v24 schema，不含 shell_enabled）
        db.execSQL("DROP TABLE IF EXISTS workspaces_new")
        db.execSQL(
            """
            CREATE TABLE workspaces_new (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `root` TEXT NOT NULL,
                `shell_status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_access_at` INTEGER,
                `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        val lastAccessAtExpr = if ("last_access_at" in existingCols) "last_access_at" else "NULL"
        val toolApprovalsExpr = if ("tool_approvals" in existingCols) "tool_approvals" else "'{}'"

        db.execSQL(
            """
            INSERT OR IGNORE INTO workspaces_new
                (id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals)
            SELECT
                id, name, root, shell_status, created_at, updated_at, $lastAccessAtExpr, $toolApprovalsExpr
            FROM workspaces
            """.trimIndent()
        )

        db.execSQL("DROP TABLE workspaces")
        db.execSQL("ALTER TABLE workspaces_new RENAME TO workspaces")

        // 重建索引（原表索引在 DROP 时已删除）
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_root` ON `workspaces` (`root`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_updated_at` ON `workspaces` (`updated_at`)")
    }
}
