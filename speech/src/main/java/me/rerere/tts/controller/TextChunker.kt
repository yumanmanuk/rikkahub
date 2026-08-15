package me.rerere.tts.controller

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 *
 * @param maxChunkLength 最大 chunk 长度（字符数）
 * @param crossParagraph  true 时不按 \n\n 硬分段，允许跨段归并（适合 SystemTTS 等非流式合成场景）
 * @param maxChunkBytes  最大 chunk 字节数（UTF-8），默认 maxChunkLength * 3（中文字符约 3 字节），
 *                       用于 Google Chirp3-HD 等按字节计量的 API，与 maxChunkLength 同时生效取更严格者
 */
class TextChunker(
    private val maxChunkLength: Int = 150,
    private val crossParagraph: Boolean = false,
    private val maxChunkBytes: Int = maxChunkLength * 3
) {
    private val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

    // 中文次级边界：书名号、引号等，在无主要标点时作为候选切点
    private val secondaryBoundary = "(?<=[、《》「」])".toRegex()

    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        val chunks = if (crossParagraph) {
            splitSingleBlock(text)
        } else {
            text.split("\n\n").flatMap { paragraph ->
                if (paragraph.isBlank()) emptyList() else splitSingleBlock(paragraph)
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }

    private fun splitSingleBlock(text: String): List<String> {
        return text
            .split(punctuationRegex)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { seg ->
                // 对超过任一上限的 seg 进行语义感知切分，避免破坏汉字/词边界
                if (seg.length > maxChunkLength || seg.toByteArray(Charsets.UTF_8).size > maxChunkBytes) {
                    smartSplit(seg)
                } else {
                    listOf(seg)
                }
            }
            .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                val last = acc.lastOrNull()
                val mergedLen = (last?.length ?: 0) + seg.length
                val mergedBytes = if (last != null) {
                    (last.toString() + seg).toByteArray(Charsets.UTF_8).size
                } else {
                    seg.toByteArray(Charsets.UTF_8).size
                }
                if (acc.isEmpty() || mergedLen > maxChunkLength || mergedBytes > maxChunkBytes) {
                    acc.add(StringBuilder(seg))
                } else {
                    acc.last().append(seg)
                }
                acc
            }
            .map { it.toString() }
    }

    /**
     * 智能切分长段：优先在语义边界处切，避免切坏汉字/单词。
     *
     * 切分优先级：
     * 1. 次级标点后（、《》「」）
     * 2. 空白字符后
     * 3. 字母大小写转折处（如 helloWorld 在 W 前切）
     * 4. 兜底：按字符硬切（保证不越界，不截断 surrogate pair）
     */
    private fun smartSplit(seg: String): List<String> {
        if (seg.length <= maxChunkLength && seg.toByteArray(Charsets.UTF_8).size <= maxChunkBytes) {
            return listOf(seg)
        }
        val out = mutableListOf<String>()
        var start = 0
        while (start < seg.length) {
            if (seg.length - start <= maxChunkLength
                && seg.substring(start).toByteArray(Charsets.UTF_8).size <= maxChunkBytes
            ) {
                out.add(seg.substring(start))
                break
            }
            val maxEnd = findMaxEnd(seg, start)
            val cut = findCutPoint(seg, start, maxEnd)
            out.add(seg.substring(start, cut))
            start = cut
        }
        return out
    }

    /**
     * 在 [start, seg.length) 范围内，找到满足字符/字节双上限的最大结束索引（不含）。
     */
    private fun findMaxEnd(seg: String, start: Int): Int {
        var end = (start + maxChunkLength).coerceAtMost(seg.length)
        // 字节上限收紧：逐步回退直到字节数满足要求
        while (end > start && seg.substring(start, end).toByteArray(Charsets.UTF_8).size > maxChunkBytes) {
            end--
        }
        return end.coerceAtLeast(start + 1)
    }

    /**
     * 在 [start, maxEnd) 区间内，反向寻找最优语义切点。
     */
    private fun findCutPoint(seg: String, start: Int, maxEnd: Int): Int {
        val sub = seg.substring(start, maxEnd)
        // 至少保留一半，避免切点退回到开头
        val minReasonable = sub.length / 2

        // 1) 次级标点后切
        val secMatches = secondaryBoundary.findAll(sub).toList()
        secMatches.lastOrNull { it.range.first >= minReasonable }?.let {
            return start + it.range.first + 1
        }

        // 2) 空白字符后切
        for (i in sub.indices.reversed()) {
            if (sub[i].isWhitespace() && i >= minReasonable) return start + i + 1
        }

        // 3) 字母大小写转折处切（如 helloWorld → 在 W 前切）
        for (i in sub.length - 1 downTo 1) {
            val cur = sub[i]
            val prev = sub[i - 1]
            if (cur.isLetter() && prev.isLetter()
                && cur.isUpperCase() && prev.isLowerCase()
                && i >= minReasonable
            ) {
                return start + i
            }
        }

        // 4) 兜底：直接在 maxEnd 处切
        return maxEnd
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String
)
