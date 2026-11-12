package io.github.carnager.bastion.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** A systemd unit's state, from `systemctl show`. */
data class ServiceInfo(val unit: String, val props: Map<String, String>) {
    val description: String get() = props["Description"].orEmpty()
    val activeState: String get() = props["ActiveState"].orEmpty()
    val subState: String get() = props["SubState"].orEmpty()
    val loadState: String get() = props["LoadState"].orEmpty()
    val enabled: String get() = props["UnitFileState"].orEmpty()
    val running: Boolean get() = activeState == "active"
    val failed: Boolean get() = activeState == "failed"
    val mainPid: String? get() = props["MainPID"]?.takeIf { it != "0" && it.isNotEmpty() }
    val since: String? get() = props["ActiveEnterTimestamp"]?.takeIf { it.isNotBlank() && it != "n/a" }
    val unitFile: String? get() = props["FragmentPath"]?.takeIf { it.isNotBlank() }

    /** Bytes, or null when systemd doesn't track it ("[not set]" or the uint64 max). */
    val memory: Long? get() = props["MemoryCurrent"]?.toLongOrNull()?.takeIf { it in 0 until Long.MAX_VALUE }
    val cpuNanos: Long? get() = props["CPUUsageNSec"]?.toLongOrNull()?.takeIf { it in 0 until Long.MAX_VALUE }
    val tasks: Long? get() = props["TasksCurrent"]?.toLongOrNull()?.takeIf { it in 0 until Long.MAX_VALUE }
}

/** One journal entry. Priority 0–7: 3 and below are errors, 4 warnings. */
data class LogLine(val timeMicros: Long, val priority: Int, val message: String)

object Services {
    /** Groups whose members may read the whole journal. */
    val LOG_GROUPS = setOf("systemd-journal", "adm", "wheel")
    const val NO_LOG_ACCESS = "Your user can’t read system logs (it isn’t in systemd-journal, adm or wheel)."

    val PROPERTIES = listOf(
        "Id", "Description", "LoadState", "ActiveState", "SubState", "UnitFileState", "MainPID",
        "ActiveEnterTimestamp", "MemoryCurrent", "CPUUsageNSec", "TasksCurrent", "FragmentPath", "Result",
    )

    fun showCommand(unit: String): String =
        "systemctl show --no-pager -p ${PROPERTIES.joinToString(",")} -- ${shellQuote(unit)}"

    fun journalCommand(unit: String, lines: Int = 100): String =
        "journalctl -u ${shellQuote(unit)} -n $lines --no-pager -o json 2>&1"

    fun parseShow(unit: String, output: String): ServiceInfo = ServiceInfo(
        unit,
        output.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * journalctl's JSON lines. MESSAGE can be an array of bytes for non-UTF-8 text. Lines that
     * aren't JSON (e.g. "No journal files were opened due to insufficient permissions") come
     * back as a notice.
     */
    fun parseJournal(output: String): Pair<List<LogLine>, String?> {
        val lines = mutableListOf<LogLine>()
        val notices = mutableListOf<String>()
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (!line.startsWith("{")) {
                notices += line
                return@forEach
            }
            val obj = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: return@forEach
            val message = when (val m = obj["MESSAGE"]) {
                is JsonPrimitive -> m.content
                is JsonArray -> m.mapNotNull { it.jsonPrimitive.content.toIntOrNull()?.toByte() }.toByteArray().toString(Charsets.UTF_8)
                else -> ""
            }
            lines += LogLine(
                timeMicros = (obj["__REALTIME_TIMESTAMP"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                priority = (obj["PRIORITY"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 6,
                message = message,
            )
        }
        return lines to notices.joinToString("\n").ifBlank { null }
    }

    /** Running first, then failed, then other active states, then the rest; by name within. */
    fun sortUnits(choices: List<Catalog.Choice>): List<Catalog.Choice> =
        choices.sortedWith(
            compareBy<Catalog.Choice> {
                when {
                    it.detail.startsWith("active · running") -> 0
                    it.detail.startsWith("failed") -> 1
                    it.detail.startsWith("active") -> 2
                    else -> 3
                }
            }.thenBy { it.label },
        )
}

sealed interface SudoResult {
    data class Ok(val output: String) : SudoResult
    data object NeedsPassword : SudoResult
    data object WrongPassword : SudoResult
    data class Failed(val output: String, val code: Int) : SudoResult

    companion object {
        fun needsPassword(output: String) =
            "password is required" in output || "a terminal is required" in output || "askpass" in output

        fun wrongPassword(output: String) =
            "incorrect password" in output || "Sorry, try again" in output || "no password was provided" in output ||
                "Authentication failure" in output
    }
}

/** A JSON log message (caddy, many Go/Node services) flattened for display. */
data class StructuredLog(val level: String?, val message: String?, val fields: List<Pair<String, String>>) {
    /** The handful of fields worth showing collapsed. */
    val highlights: List<Pair<String, String>>
        get() {
            val wanted = listOf("status", "request.method", "request.host", "host", "request.uri", "uri", "path", "duration", "error", "err", "logger")
            val byKey = fields.toMap()
            return wanted.mapNotNull { k -> byKey[k]?.let { k.substringAfterLast('.') to it } }.take(5)
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val LEVEL_KEYS = listOf("level", "lvl", "severity", "loglevel")
        private val MESSAGE_KEYS = listOf("msg", "message", "event")

        fun parse(message: String): StructuredLog? {
            val trimmed = message.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val obj = runCatching { json.parseToJsonElement(trimmed) as JsonObject }.getOrNull() ?: return null
            val flat = mutableListOf<Pair<String, String>>()
            fun walk(prefix: String, element: kotlinx.serialization.json.JsonElement, depth: Int) {
                when (element) {
                    is JsonObject -> if (depth < 2) element.forEach { (k, v) -> walk(if (prefix.isEmpty()) k else "$prefix.$k", v, depth + 1) }
                        else flat += prefix to element.toString()
                    is JsonArray -> flat += prefix to element.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                    is JsonPrimitive -> flat += prefix to element.content
                }
            }
            walk("", obj, 0)
            val level = LEVEL_KEYS.firstNotNullOfOrNull { k -> flat.firstOrNull { it.first == k }?.second }
            val msg = MESSAGE_KEYS.firstNotNullOfOrNull { k -> flat.firstOrNull { it.first == k }?.second }
            val rest = flat.filterNot { it.first in LEVEL_KEYS || it.first in MESSAGE_KEYS || it.first == "ts" || it.first == "time" || it.first == "timestamp" }
            return StructuredLog(level, msg, rest)
        }

        /** syslog-like priority from a level name, if it says more than the journal does. */
        fun priorityOf(level: String?): Int? = when (level?.lowercase()) {
            "fatal", "panic", "critical", "crit" -> 2
            "error", "err" -> 3
            "warn", "warning" -> 4
            "info" -> 6
            "debug", "trace" -> 7
            else -> null
        }
    }
}
