package me.rerere.common.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        addLog(entry)
    }

    private fun addLog(entry: LogEntry) {
        val current = _logsFlow.value.toMutableList()
        current.add(0, entry)
        if (current.size > MAX_RECENT_LOGS) {
            current.removeLastOrNull()
        }
        _logsFlow.value = current
    }

    fun getRecentLogs(): List<LogEntry> = _logsFlow.value

    fun getTextLogs(): List<LogEntry.TextLog> =
        _logsFlow.value.filterIsInstance<LogEntry.TextLog>()

    fun getRequestLogs(): List<LogEntry.RequestLog> =
        _logsFlow.value.filterIsInstance<LogEntry.RequestLog>()

    fun clear() {
        _logsFlow.value = emptyList()
    }
}
