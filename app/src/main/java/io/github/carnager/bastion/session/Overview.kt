package io.github.carnager.bastion.session

/** A server at a glance, from one script run on the hidden exec channel. */
data class Overview(
    val os: String?,
    val kernel: String?,
    val uptimeSeconds: Long?,
    val load: List<Double>,
    val cores: Int?,
    val memTotalKb: Long?,
    val memAvailableKb: Long?,
    val swapTotalKb: Long?,
    val swapFreeKb: Long?,
    val disks: List<Disk>,
    /** Null when the server has no systemd. */
    val runningServices: Int?,
    val failedServices: List<String>,
    val containers: List<Container>?,
    val topProcesses: List<Proc>,
) {
    data class Disk(val mount: String, val type: String, val sizeKb: Long, val usedKb: Long) {
        val fraction: Float get() = if (sizeKb <= 0) 0f else usedKb.toFloat() / sizeKb
    }

    data class Container(val name: String, val state: String, val status: String) {
        val running: Boolean get() = state == "running"
    }

    data class Proc(val pid: String, val name: String, val cpu: String, val mem: String)

    val memUsedFraction: Float?
        get() = if (memTotalKb != null && memAvailableKb != null && memTotalKb > 0) (memTotalKb - memAvailableKb).toFloat() / memTotalKb else null
}

object Overviews {
    /** POSIX sh; every section tolerates missing tools. */
    val SCRIPT = """
        echo '### os'; (. /etc/os-release 2>/dev/null; echo "${'$'}PRETTY_NAME"); uname -r
        echo '### uptime'; cat /proc/uptime 2>/dev/null
        echo '### load'; cat /proc/loadavg 2>/dev/null; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null
        echo '### mem'; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null
        echo '### disk'; df -PTk -x tmpfs -x devtmpfs -x overlay -x squashfs -x efivarfs -x ramfs 2>/dev/null
        if command -v systemctl >/dev/null 2>&1; then
          echo '### running'; systemctl list-units --type=service --state=running --no-legend --plain --no-pager 2>/dev/null | wc -l
          echo '### failed'; systemctl list-units --type=service --state=failed --no-legend --plain --no-pager 2>/dev/null
        fi
        if command -v docker >/dev/null 2>&1; then E=docker; elif command -v podman >/dev/null 2>&1; then E=podman; fi
        if [ -n "${'$'}E" ]; then echo '### containers'; ${'$'}E ps -a --format '{{.Names}}	{{.State}}	{{.Status}}' 2>/dev/null; fi
        echo '### top'; ps -eo pid=,comm=,%cpu=,%mem= --sort=-%cpu 2>/dev/null | head -6
    """.trimIndent()

    /** Pending updates; a separate run since it can take seconds (checkupdates syncs a temp db). */
    val UPDATES_SCRIPT = """
        if command -v checkupdates >/dev/null 2>&1; then echo pacman; checkupdates 2>/dev/null | wc -l
        elif command -v pacman >/dev/null 2>&1; then echo pacman-local; pacman -Qu 2>/dev/null | wc -l
        elif command -v apt >/dev/null 2>&1; then echo apt; apt list --upgradable 2>/dev/null | grep -c upgradable
        elif command -v dnf >/dev/null 2>&1; then echo dnf; dnf -q check-update 2>/dev/null | grep -c '^[a-zA-Z0-9]'
        else echo none; fi
    """.trimIndent()

    data class Updates(val manager: String, val count: Int) {
        /** pacman -Qu only knows what the last database sync knew (no checkupdates installed). */
        val staleHint: String?
            get() = if (manager == "pacman-local") "as of the last pacman -Sy · install pacman-contrib for a live check" else null
        val managerName: String get() = manager.removeSuffix("-local")

        val upgradeCommand: String?
            get() = when (manager) {
                "pacman", "pacman-local" -> "sudo pacman -Syu"
                "apt" -> "sudo apt update && sudo apt upgrade"
                "dnf" -> "sudo dnf upgrade"
                else -> null
            }
    }

    fun parseUpdates(output: String): Updates? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val manager = lines.firstOrNull() ?: return null
        if (manager == "none") return null
        return Updates(manager, lines.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun sections(output: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        output.lineSequence().forEach { line ->
            if (line.startsWith("### ")) {
                current = mutableListOf<String>().also { map[line.removePrefix("### ").trim()] = it }
            } else if (line.isNotBlank()) {
                current?.add(line)
            }
        }
        return map
    }

    fun parse(output: String): Overview {
        val s = sections(output)
        val os = s["os"].orEmpty()
        val mem = s["mem"].orEmpty().associate { line ->
            val (k, v) = line.split(':', limit = 2)
            k.trim() to (v.trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }
        val loadLines = s["load"].orEmpty()
        return Overview(
            os = os.getOrNull(0)?.takeIf { it.isNotBlank() },
            kernel = os.getOrNull(1),
            uptimeSeconds = s["uptime"]?.firstOrNull()?.substringBefore(' ')?.toDoubleOrNull()?.toLong(),
            load = loadLines.firstOrNull()?.split(' ')?.take(3)?.mapNotNull { it.toDoubleOrNull() }.orEmpty(),
            cores = loadLines.getOrNull(1)?.trim()?.toIntOrNull(),
            memTotalKb = mem["MemTotal"],
            memAvailableKb = mem["MemAvailable"],
            swapTotalKb = mem["SwapTotal"],
            swapFreeKb = mem["SwapFree"],
            disks = s["disk"].orEmpty().drop(1).mapNotNull { line ->
                // Filesystem Type 1024-blocks Used Available Capacity Mounted-on
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 7) return@mapNotNull null
                Overview.Disk(mount = p.subList(6, p.size).joinToString(" "), type = p[1], sizeKb = p[2].toLongOrNull() ?: 0, usedKb = p[3].toLongOrNull() ?: 0)
            }.filter { it.sizeKb > 0 }.distinctBy { it.mount },
            runningServices = s["running"]?.firstOrNull()?.trim()?.toIntOrNull(),
            failedServices = s["failed"].orEmpty().mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.removeSuffix(".service") }
                .filter { it.isNotEmpty() && it != "●" },
            containers = s["containers"]?.map { line ->
                val p = line.split('\t')
                Overview.Container(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            },
            topProcesses = s["top"].orEmpty().mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 4) null else Overview.Proc(p[0], p[1], p[2], p[3])
            },
        )
    }
}
