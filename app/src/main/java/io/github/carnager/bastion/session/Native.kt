package io.github.carnager.bastion.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One process from `ps`. */
data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpu: Double,
    val mem: Double,
    val rssKb: Long,
    val elapsedSeconds: Long,
    val name: String,
)

object Processes {
    const val LIST = "ps -eo pid=,user=,pcpu=,pmem=,rss=,etimes=,comm= 2>/dev/null"

    fun detail(pid: Int) = "tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null; echo; ls -ld /proc/$pid 2>/dev/null | awk '{print \$3}'"

    fun parse(output: String): List<ProcessInfo> = output.lineSequence().mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"), limit = 7)
        if (p.size < 7) return@mapNotNull null
        ProcessInfo(
            pid = p[0].toIntOrNull() ?: return@mapNotNull null,
            user = p[1],
            cpu = p[2].toDoubleOrNull() ?: 0.0,
            mem = p[3].toDoubleOrNull() ?: 0.0,
            rssKb = p[4].toLongOrNull() ?: 0,
            elapsedSeconds = p[5].toLongOrNull() ?: 0,
            name = p[6],
        )
    }.toList()

    /** kill's complaint when the process belongs to someone else. */
    fun notPermitted(output: String) = "not permitted" in output.lowercase()
}

/** One entry of a disk usage scan. */
data class UsageEntry(val name: String, val path: String, val kb: Long, val isDir: Boolean)

data class UsageScan(val path: String, val totalKb: Long, val entries: List<UsageEntry>) {
    val largestKb: Long get() = entries.maxOfOrNull { it.kb } ?: 0
}

object DiskUsage {
    /**
     * Sizes of everything directly in [path] (one filesystem, -x), then which of them are
     * folders. POSIX enough for GNU and busybox du.
     */
    fun script(path: String): String = """
        cd -- ${shellQuote(path)} || exit 3
        du -x -a -k -d 1 . 2>/dev/null
        echo '###'
        for f in * .[!.]* ..?*; do [ -d "${'$'}f" ] && [ ! -L "${'$'}f" ] && printf '%s\n' "${'$'}f"; done
    """.trimIndent()

    fun parse(path: String, output: String): UsageScan {
        val (sizes, dirs) = output.split("###\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val folderNames = dirs.lines().filter { it.isNotBlank() }.toSet()
        var total = 0L
        val entries = sizes.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val kb = line.substring(0, tab).trim().toLongOrNull() ?: return@mapNotNull null
            val rel = line.substring(tab + 1).removePrefix("./")
            if (rel == ".") {
                total = kb
                return@mapNotNull null
            }
            val full = if (path == "/") "/$rel" else path.trimEnd('/') + "/" + rel
            UsageEntry(rel, full, kb, rel in folderNames)
        }.sortedByDescending { it.kb }.toList()
        return UsageScan(path, total, entries)
    }
}

/** A container from `docker ps -a --format '{{json .}}'`. */
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: String,
    val created: String,
) {
    val running: Boolean get() = state == "running"
}

object Containers {
    fun list(engine: String) = "$engine ps -a --format '{{json .}}' 2>&1"
    fun logs(engine: String, name: String, lines: Int = 100) = "$engine logs --tail $lines --timestamps ${shellQuote(name)} 2>&1"

    /** The daemon refused us: the user isn't in the docker group (try sudo). */
    fun permissionDenied(output: String) = "permission denied" in output.lowercase() && "docker" in output.lowercase()

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(output: String): List<ContainerInfo> = output.lineSequence().mapNotNull { line ->
        val obj = runCatching { json.parseToJsonElement(line.trim()) as JsonObject }.getOrNull() ?: return@mapNotNull null
        fun s(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
        ContainerInfo(
            id = s("ID"),
            name = s("Names").substringBefore(','),
            image = s("Image"),
            state = s("State").lowercase(),
            status = s("Status"),
            ports = s("Ports"),
            created = s("CreatedAt").ifEmpty { s("RunningFor") },
        )
    }.toList()

    /** `docker logs --timestamps`: "2026-11-12T22:53:20.123456789Z message"; time stays as text. */
    fun parseLogs(output: String): List<Pair<String, String>> = output.lineSequence().filter { it.isNotBlank() }.map { line ->
        val space = line.indexOf(' ')
        if (space in 20..40 && line[4] == '-' && line[10] == 'T') line.substring(0, 19).replace('T', ' ') to Services.stripAnsi(line.substring(space + 1))
        else "" to Services.stripAnsi(line)
    }.toList()
}
