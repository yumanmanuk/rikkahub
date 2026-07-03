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

    @Serializable
    data class ErrorLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val title: String? = null,
        val message: String,
        val stackTrace: String? = null
    ) : LogEntry()
}

object Logging {
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    @Volatile
    private var requestLoggingEnabled = true

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logError(
        tag: String,
        title: String? = null,
        message: String,
        throwable: Throwable? = null
    ) {
        addLog(
            LogEntry.ErrorLog(
                tag = tag,
                title = title,
                message = message,
                stackTrace = throwable?.stackTraceToString()
            )
        )
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
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

    fun getErrorLogs(): List<LogEntry.ErrorLog> =
        _logsFlow.value.filterIsInstance<LogEntry.ErrorLog>()

    fun clear() {
        _logsFlow.value = emptyList()
    }

    fun clearErrorLogs() {
        _logsFlow.value = _logsFlow.value.filter { it !is LogEntry.ErrorLog }
    }
}
