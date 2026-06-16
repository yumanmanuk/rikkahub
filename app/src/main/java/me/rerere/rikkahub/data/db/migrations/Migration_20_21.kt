package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

// [FORK] v20→v21: fork 曾在 v19 加入的 conversation_params 字段被 upstream 的 custom_system_prompt 替换，需声明删除
@DeleteColumn(tableName = "ConversationEntity", columnName = "conversation_params")
class Migration_20_21 : AutoMigrationSpec
