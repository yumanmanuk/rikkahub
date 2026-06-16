package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

class PreferenceStoreV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 3
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val (migratedAssistants, extractedQuickMessages) =
            migrateAssistantsQuickMessages(prefs[SettingsStore.ASSISTANTS] ?: "[]")

        prefs[SettingsStore.ASSISTANTS] = migratedAssistants

        // 合并已有的全局快捷消息（防止重复）
        val existingQuickMessages = prefs[SettingsStore.QUICK_MESSAGES]?.let { json ->
            runCatching<JsonArray> {
                JsonInstant.parseToJsonElement(json).jsonArray
            }.getOrElse { JsonArray(emptyList()) }
        } ?: JsonArray(emptyList())

        val existingIds = existingQuickMessages.mapNotNull {
            (it as? JsonObject)?.get("id")?.toString()?.trim('"')
        }.toSet()

        val merged = JsonArray(
            existingQuickMessages + extractedQuickMessages.filter { element ->
                val id = (element as? JsonObject)?.get("id")?.toString()?.trim('"')
                id != null && id !in existingIds
            }
        )

        prefs[SettingsStore.QUICK_MESSAGES] = JsonInstant.encodeToString(merged)
        prefs[SettingsStore.VERSION] = 3

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 从旧格式 assistants JSON 中提取 quickMessages 字段（完整对象，无 id 字段），
 * 为每条消息生成新 UUID，将其替换为 quickMessageIds（仅 ID 列表），
 * 并返回补充了 id 的全局消息列表。
 */
internal fun migrateAssistantsQuickMessages(
    assistantsJson: String
): Pair<String, JsonArray> {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson to JsonArray(emptyList())

        val allQuickMessages = mutableListOf<JsonElement>()

        val migratedAssistants = JsonArray(
            root.map { assistant ->
                val assistantObj = assistant as? JsonObject
                    ?: return@map assistant

                // 如果不存在旧的 quickMessages 字段则无需迁移
                val oldQuickMessages = assistantObj["quickMessages"] as? JsonArray
                    ?: return@map assistant

                // 为每条旧消息使用稳定 UUID：基于 assistantId+索引+内容 hash 派生
                // 同一份 assistant JSON 多次迁移得到完全相同的 id，避免重复恢复时产生重复条目
                val assistantId = assistantObj["id"]?.toString()?.trim('"') ?: "unknown"
                val messagesWithIds = oldQuickMessages.mapIndexed { index, element ->
                    val obj = element as? JsonObject ?: return@mapIndexed element
                    val contentText = obj["content"]?.toString() ?: ""
                    val stableId = stableQuickMessageId(assistantId, index, contentText)
                    JsonObject(obj.toMutableMap().apply {
                        put("id", JsonPrimitive(stableId))
                    })
                }

                // 收集到全局列表
                allQuickMessages.addAll(messagesWithIds)

                // 提取 ID 列表构建 quickMessageIds
                val ids = JsonArray(
                    messagesWithIds.mapNotNull { element ->
                        (element as? JsonObject)?.get("id")
                    }
                )

                JsonObject(
                    assistantObj.toMutableMap().apply {
                        remove("quickMessages")
                        put("quickMessageIds", ids)
                    }
                )
            }
        )

        JsonInstant.encodeToString(migratedAssistants) to JsonArray(allQuickMessages)
    }.getOrElse { assistantsJson to JsonArray(emptyList()) }
}

/**
 * 基于内容派生稳定 UUID，确保同一份数据多次迁移产生相同 id。
 *
 * 使用 UUID.nameUUIDFromBytes（UUID v3 / MD5）：
 * - [assistantId] 前缀避免不同 assistant 的 id 冲突
 * - [index] 保留消息顺序
 * - [contentText] hashCode 提供内容熵
 */
internal fun stableQuickMessageId(assistantId: String, index: Int, contentText: String): String {
    val raw = "$assistantId|$index|${contentText.hashCode()}"
    return UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString()
}
