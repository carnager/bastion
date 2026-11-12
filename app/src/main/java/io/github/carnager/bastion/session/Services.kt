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
