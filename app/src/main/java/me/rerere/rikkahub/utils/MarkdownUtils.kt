package me.rerere.rikkahub.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * 为 TTS 朗读场景剥离 Markdown。
 *
 * 与 stripMarkdown() 的差异:
 * - 围栏代码块: 替换为"包含一段 {语言} 代码,约 N 行"提示,而非空字符串,
 *   避免 AI 回复中的高亮代码块在 TTS 时被完全略过。
 * - 行内代码:  去掉反引号,保留原字符,让 TTS 按词读出。
 * - 其他剥离规则与 stripMarkdown() 保持一致。
 */
fun String.stripMarkdownForTts(): String {
    var result = this
    // 1) 围栏代码块: 替换为语言提示
    result = Regex("```(\\w*)\\n?([\\s\\S]*?)```").replace(result) { match ->
        val lang = match.groupValues[1].ifBlank { "plaintext" }
        val body = match.groupValues[2]
        val lineCount = body.lines().count { it.isNotBlank() }.coerceAtLeast(1)
        "\n[包含一段 $lang 代码,约 $lineCount 行]\n"
    }
    // 2) 行内代码: 去掉反引号,保留内容
    result = result.replace(Regex("`([^`\\n]+?)`"), "$1")
    // 3) 复用现有剥离规则
    return result
        // 移除图片和链接,但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩,以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        // 4) 转义 XML/SSML 保留字符，防止 Google Cloud TTS 将其当 SSML 标签解析触发 400
        // 必须先转义 & 再转义其余字符，否则会对已转义内容二次转义
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

fun String.extractThinkingTitle(): String? {
    // 匹配行内任意 **...** 片段（非贪婪，不跨行）
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    val lines = this.lines()

    // 从后往前查找最后一个包含加粗文本的行，取该行最后一个 **...** 段作为标题
    // 不使用行首行尾锚点，避免同一行多个 **段** 被全部捕获成一个超长标题
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()
        val matches = boldPattern.findAll(line).toList()
        if (matches.isNotEmpty()) {
            return matches.last().groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}

