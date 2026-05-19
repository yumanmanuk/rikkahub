package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.io.File
import java.time.Instant
import java.time.LocalDateTime as JLocalDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

// ===== Google AI Studio JSON 数据结构 =====

@Serializable
data class GoogleRunSettings(
    val model: String? = null,
)

@Serializable
data class GoogleAiStudioExport(
    val runSettings: GoogleRunSettings? = null,
    // systemInstruction（系统提示）丢弃
    val chunkedPrompt: GoogleChunkedPrompt? = null,
)

@Serializable
data class GoogleChunkedPrompt(
    val chunks: List<GoogleChunk> = emptyList(),
)

@Serializable
data class GoogleChunk(
    val role: String? = null,
    val text: String? = null,
    val createTime: String? = null,
    // 内联图片（base64）— 仅用于统计跳过数量，不导入
    val inlineImage: GoogleInlineImage? = null,
    // Google Drive 图片引用（无法导入，仅用于统计跳过数量）
    val driveImage: GoogleDriveImage? = null,
    // 思考过程 chunk（isThought=true），导入时跳过
    val isThought: Boolean = false,
    // tokenCount / finishReason / parts / grounding 等字段均丢弃
)

@Serializable
data class GoogleInlineImage(
    val mimeType: String? = null,
    val data: String? = null,
)

@Serializable
data class GoogleDriveImage(
    val id: String? = null,
)


// ===== 宽松的 JSON 解析器 =====

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

// ===== 导入结果 =====

data class GoogleAiStudioImportResult(
    val conversations: List<Conversation>,
    val skippedImageParts: Int,
)

// ===== 导入逻辑 =====

object GoogleAiStudioImporter {

    /**
     * 从 Google AI Studio 导出的 JSON 文件导入对话。
     *
     * Google AI Studio 导出格式特征：
     * - 顶层字段：runSettings、systemInstruction、chunkedPrompt
     * - chunkedPrompt.chunks 是消息列表，每个 chunk 包含 role（"user"/"model"）和 text
     * - 同一轮对话的多个 chunk（如图片 + 文字）具有相同的 createTime 和 role
     * - driveImage/inlineImage 无法导入，会统计后跳过
     * - runSettings.model 字段记录了对话所用的模型 ID（如 "models/gemini-2.5-pro"）
     */
    fun import(
        file: File,
        assistantId: Uuid = DEFAULT_ASSISTANT_ID,
        // 可选：来自文件选择器的原始文件名（含或不含后缀）
        filename: String? = null,
        // 可选：写入所有 ASSISTANT 消息的模型显示名（如 "gemini-3.1-pro"）
        modelName: String? = null,
    ): GoogleAiStudioImportResult {
        val jsonString = file.readText()
        val export = lenientJson.decodeFromString<GoogleAiStudioExport>(jsonString)

        // 过滤掉思考过程 chunk（isThought=true），只保留正文 chunk
        val chunks = (export.chunkedPrompt?.chunks ?: emptyList()).filter { !it.isThought }
        if (chunks.isEmpty()) {
            return GoogleAiStudioImportResult(emptyList(), 0)
        }

        val groups = groupChunks(chunks)
        val messageNodes = mutableListOf<MessageNode>()
        var skippedImageParts = 0

        for (group in groups) {
            val role = group.first().role ?: continue
            val messageRole = when (role.lowercase()) {
                "user" -> MessageRole.USER
                "model" -> MessageRole.ASSISTANT
                else -> continue
            }

            // 统计被跳过的图片
            skippedImageParts += group.count { it.inlineImage?.data != null || it.driveImage?.id != null }

            val parts = buildMessageParts(group)
            if (parts.isEmpty()) continue

            val createdAt = group.firstOrNull()?.createTime
                ?.let { parseToKotlinxLocalDateTime(it) }
                ?: LocalDateTime(2024, 1, 1, 0, 0, 0)

            // 仅对 ASSISTANT 消息写入模型显示名
            val resolvedModelName = if (messageRole == MessageRole.ASSISTANT) modelName else null

            val uiMessage = UIMessage(
                role = messageRole,
                parts = parts,
                createdAt = createdAt,
                modelName = resolvedModelName,
            )
            messageNodes.add(MessageNode.of(uiMessage))
        }

        if (messageNodes.isEmpty()) {
            return GoogleAiStudioImportResult(emptyList(), skippedImageParts)
        }

        // 优先使用文件名作为标题（去掉后缀）；若无文件名则取第一条用户消息前 50 字
        val title = if (!filename.isNullOrBlank()) {
            filename.substringBeforeLast('.').ifBlank { filename }.trim()
        } else {
            messageNodes
                .firstOrNull { it.role == MessageRole.USER }
                ?.currentMessage
                ?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.firstOrNull()
                ?.text
                ?.take(50)
                ?.replace("\n", " ")
                ?.trim()
                ?: "Google AI Studio 导入"
        }

        val conversationTime = chunks.firstOrNull()?.createTime
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.now()

        val conversation = Conversation(
            assistantId = assistantId,
            title = title,
            messageNodes = messageNodes,
            createAt = conversationTime,
            updateAt = conversationTime,
        )

        return GoogleAiStudioImportResult(
            conversations = listOf(conversation),
            skippedImageParts = skippedImageParts,
        )
    }

    /**
     * 将 chunks 按 role + createTime 分组。
     * 连续的、role 相同且 createTime 相同（或为 null）的 chunks 合并为一组。
     */
    private fun groupChunks(chunks: List<GoogleChunk>): List<List<GoogleChunk>> {
        val groups = mutableListOf<MutableList<GoogleChunk>>()
        var currentGroup = mutableListOf<GoogleChunk>()
        var currentTime: String? = null
        var currentRole: String? = null

        for (chunk in chunks) {
            val chunkTime = chunk.createTime
            val chunkRole = chunk.role

            val sameGroup = currentRole == chunkRole &&
                    (chunkTime == null || chunkTime == currentTime)

            if (sameGroup && currentGroup.isNotEmpty()) {
                currentGroup.add(chunk)
            } else {
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup)
                }
                currentGroup = mutableListOf(chunk)
                currentTime = chunkTime
                currentRole = chunkRole
            }
        }
        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }
        return groups
    }

    /**
     * 将一组 chunks（同一轮对话）转换为 UIMessagePart 列表。
     * - driveImage/inlineImage 跳过（无法存储）
     * - text 非空的 chunk 转换为 Text part
     */
    private fun buildMessageParts(group: List<GoogleChunk>): List<UIMessagePart> {
        val parts = mutableListOf<UIMessagePart>()
        for (chunk in group) {
            val text = chunk.text
            if (!text.isNullOrBlank()) {
                parts.add(UIMessagePart.Text(text))
            }
        }
        return parts
    }

    /**
     * 将 ISO 8601 时间字符串（如 "2026-05-09T12:17:04.908Z"）
     * 转换为 kotlinx.datetime.LocalDateTime（UTC）。
     */
    private fun parseToKotlinxLocalDateTime(createTime: String): LocalDateTime? {
        return runCatching {
            val javaInstant = Instant.parse(createTime)
            val javaLdt = JLocalDateTime.ofInstant(javaInstant, ZoneOffset.UTC)
            LocalDateTime(
                year = javaLdt.year,
                monthNumber = javaLdt.monthValue,
                dayOfMonth = javaLdt.dayOfMonth,
                hour = javaLdt.hour,
                minute = javaLdt.minute,
                second = javaLdt.second,
                nanosecond = javaLdt.nano,
            )
        }.getOrNull()
    }
}
