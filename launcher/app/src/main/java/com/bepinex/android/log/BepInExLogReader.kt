package com.bepinex.android.log

import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads and tails BepInEx runtime LogOutput.log.
 *
 * BepInEx writes its logs to `BepInEx/LogOutput.log` via the DiskLogListener.
 * This reader polls the file periodically and exposes new lines as a StateFlow.
 * Polling is automatically suspended when there are no active observers.
 */
object BepInExLogReader {

    data class LogLine(
        val timestamp: String,
        val level: LogLevel,
        val source: String,
        val message: String
    )

    enum class LogLevel(val label: String) {
        FATAL("Fatal"),
        ERROR("Error"),
        WARNING("Warning"),
        MESSAGE("Message"),
        INFO("Info"),
        DEBUG("Debug"),
        UNKNOWN("");

        companion object {
            fun parse(levelStr: String): LogLevel = when (levelStr.lowercase()) {
                "fatal" -> FATAL
                "error" -> ERROR
                "warning", "warn" -> WARNING
                "message", "msg" -> MESSAGE
                "info" -> INFO
                "debug" -> DEBUG
                else -> UNKNOWN
            }
        }
    }

    /** BepInEx log format: [LogLevel :Source] message */
    private val LOG_PATTERN = Regex("^\\[([A-Za-z]+)\\s*:?(\\S+)?\\]\\s?(.*)")

    private val targetPackage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val lines: StateFlow<List<LogLine>> = targetPackage
        .flatMapLatest { pkg ->
            if (pkg == null) {
                flowOf(emptyList())
            } else {
                flow {
                    var lastFileSize = 0L
                    var currentLines = emptyList<LogLine>()
                    emit(currentLines)

                    while (true) {
                        try {
                            val logFile = BepInExPaths.getLogFile(pkg)
                            if (logFile.exists()) {
                                val currentSize = logFile.length()
                                if (currentSize > lastFileSize) {
                                    RandomAccessFile(logFile, "r").use { raf ->
                                        raf.seek(lastFileSize)
                                        val newBytes = ByteArray((currentSize - lastFileSize).toInt())
                                        raf.readFully(newBytes)
                                        val newContent = String(newBytes, Charsets.UTF_8)
                                        val parsedLines = newContent.lines()
                                            .filter { it.isNotBlank() }
                                            .mapNotNull { parseLine(it) }
                                        
                                        if (parsedLines.isNotEmpty()) {
                                            currentLines = currentLines + parsedLines
                                            emit(currentLines)
                                        }
                                        lastFileSize = currentSize
                                    }
                                } else if (currentSize < lastFileSize) {
                                    // File was truncated or deleted
                                    lastFileSize = 0L
                                    currentLines = emptyList()
                                    emit(currentLines)
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore lock/read errors
                        }
                        delay(500)
                    }
                }
            }
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun startWatching(packageName: String, scope: CoroutineScope? = null) {
        targetPackage.value = packageName
    }

    fun stopWatching() {
        // We can just leave the target package, Flow will suspend if no observers anyway
    }

    fun clear() {
        // Clearing is now managed per-file truncation, or we can just reset package
        targetPackage.value = null
    }

    private fun parseLine(raw: String): LogLine? {
        val match = LOG_PATTERN.find(raw) ?: return LogLine(
            timestamp = "",
            level = LogLevel.UNKNOWN,
            source = "",
            message = raw
        )
        val level = LogLevel.parse(match.groupValues[1])
        val source = match.groupValues[2].ifEmpty { "" }
        val message = match.groupValues[3]
        return LogLine(timestamp = "", level = level, source = source, message = message)
    }
}
