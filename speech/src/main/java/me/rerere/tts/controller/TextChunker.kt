package me.rerere.tts.controller

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 *
 * @param maxChunkLength 最大 chunk 长度（字符数）
 * @param crossParagraph  true 时不按 \n\n 硬分段，允许跨段归并（适合 SystemTTS 等非流式合成场景）
 */
class TextChunker(
    private val maxChunkLength: Int = 150,
    private val crossParagraph: Boolean = false
) {
    private val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

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
            .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                    acc.add(StringBuilder(seg))
                } else {
                    acc.last().append(seg)
                }
                acc
            }
            .map { it.toString() }
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String
)

