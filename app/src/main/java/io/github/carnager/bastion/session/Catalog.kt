package io.github.carnager.bastion.session

/**
 * Command templates with fields: `{unit}`, `{container}`, `{process}`, `{file}`, `{folder}` are
 * filled by picking from lists fetched from the server; anything else (`{pattern}` or
 * `{text:Package name}`) is typed. `${VAR}` and `{}` are left alone for the shell.
 */
enum class FieldType(val noun: String) {
    UNIT("service"), CONTAINER("container"), PROCESS("process"), FILE("file"), FOLDER("folder"), TEXT("value"),
}

data class Field(val token: String, val name: String, val type: FieldType, val label: String)

data class CatalogEntry(
    val id: String,
    val group: String,
    val title: String,
    val template: String,
    /** Tools that must exist on the server for this entry to show. */
    val requires: List<String> = emptyList(),
    /** Changes the system; the Run button turns red. */
    val danger: Boolean = false,
)

object Templates {
    private val FIELD = Regex("""(?<!\$)\{([A-Za-z][\w-]*)(?::([^{}]*))?\}""")

    fun fields(template: String): List<Field> = FIELD.findAll(template).map { m ->
        val name = m.groupValues[1]
        val type = when (name.lowercase()) {
            "unit", "service" -> FieldType.UNIT
            "container" -> FieldType.CONTAINER
            "process", "pid" -> FieldType.PROCESS
            "file" -> FieldType.FILE
            "folder", "dir", "directory" -> FieldType.FOLDER
            else -> FieldType.TEXT
        }
        val label = m.groupValues[2].ifBlank {
            if (type == FieldType.TEXT) name.replaceFirstChar { it.uppercase() } else type.noun.replaceFirstChar { it.uppercase() }
        }
        Field(m.value, name, type, label)
    }.distinctBy { it.token }.toList()

    /**
     * Fills every occurrence of each field, shell-quoted (so `*.conf` reaches find unexpanded).
     * Typed text that starts with a quote is taken as already quoted; pids go in as they are.
     */
    fun fill(template: String, values: Map<String, String>): String =
        FIELD.replace(template) { m ->
            val value = values[m.value] ?: return@replace m.value
            val field = fields(m.value).first()
            when (field.type) {
                FieldType.PROCESS -> value
                FieldType.TEXT -> if (value.startsWith("'") || value.startsWith("\"")) value else shellQuote(value)
                else -> shellQuote(value)
            }
        }
}

/** Built-in commands, shown when the tools they need exist on the server. */
object Catalog {
    /** Tools probed once per connection. */
    val PROBED = listOf(
        "systemctl", "journalctl", "docker", "podman", "pacman", "checkupdates", "apt", "dnf",
        "ss", "ip", "htop", "lsblk", "rg", "free",
    )

    fun probeCommand(): String =
        "sh -c " + shellQuote("for c in ${PROBED.joinToString(" ")}; do command -v \"\$c\" >/dev/null 2>&1 && echo \"\$c\"; done")

    private const val SVC = "Services"
    private const val LOGS = "Logs"
    private const val CONT = "Containers"
    private const val PROC = "Processes"
    private const val DISK = "Disk"
    private const val NET = "Network"
    private const val SYS = "System"
    private const val PKG = "Packages"
    private const val FILES = "Files"

    val all: List<CatalogEntry> = listOf(
        CatalogEntry("svc-status", SVC, "Status of a service", "systemctl status {unit}", listOf("systemctl")),
        CatalogEntry("svc-restart", SVC, "Restart a service", "sudo systemctl restart {unit}", listOf("systemctl"), danger = true),
        CatalogEntry("svc-start", SVC, "Start a service", "sudo systemctl start {unit}", listOf("systemctl")),
        CatalogEntry("svc-stop", SVC, "Stop a service", "sudo systemctl stop {unit}", listOf("systemctl"), danger = true),
        CatalogEntry("svc-enable", SVC, "Enable and start a service", "sudo systemctl enable --now {unit}", listOf("systemctl")),
        CatalogEntry("svc-failed", SVC, "Failed services", "systemctl --failed", listOf("systemctl")),
        CatalogEntry("svc-timers", SVC, "Timers", "systemctl list-timers", listOf("systemctl")),

        CatalogEntry("log-follow", LOGS, "Follow a service’s log", "journalctl -u {unit} -f", listOf("journalctl")),
        CatalogEntry("log-recent", LOGS, "Recent log of a service", "journalctl -u {unit} -n 200 --no-pager", listOf("journalctl")),
        CatalogEntry("log-errors", LOGS, "Errors since boot", "journalctl -b -p err --no-pager", listOf("journalctl")),
        CatalogEntry("log-all", LOGS, "Follow the whole journal", "journalctl -f", listOf("journalctl")),
        CatalogEntry("log-kernel", LOGS, "Kernel messages", "journalctl -k -n 100 --no-pager", listOf("journalctl")),
        CatalogEntry("log-file", LOGS, "Follow a file", "tail -f -n 100 {file}"),

        CatalogEntry("ctr-ps", CONT, "Running containers", "docker ps", listOf("docker")),
        CatalogEntry("ctr-all", CONT, "All containers", "docker ps -a", listOf("docker")),
        CatalogEntry("ctr-logs", CONT, "Follow a container’s log", "docker logs -f --tail 200 {container}", listOf("docker")),
        CatalogEntry("ctr-restart", CONT, "Restart a container", "docker restart {container}", listOf("docker"), danger = true),
        CatalogEntry("ctr-shell", CONT, "Shell in a container", "docker exec -it {container} sh", listOf("docker")),
        CatalogEntry("ctr-stats", CONT, "Container resource use", "docker stats --no-stream", listOf("docker")),

        CatalogEntry("proc-cpu", PROC, "Top CPU users", "ps -eo pid,user,comm,%cpu,%mem --sort=-%cpu | head -20"),
        CatalogEntry("proc-mem", PROC, "Top memory users", "ps -eo pid,user,comm,%mem,rss --sort=-%mem | head -20"),
        CatalogEntry("proc-htop", PROC, "Live process view", "htop", listOf("htop")),
        CatalogEntry("proc-kill", PROC, "Stop a process", "kill {process}", danger = true),

        CatalogEntry("disk-free", DISK, "Free space", "df -h"),
        CatalogEntry("disk-du", DISK, "What’s big in a folder", "du -h --max-depth=1 {folder} 2>/dev/null | sort -h"),
        CatalogEntry("disk-devices", DISK, "Drives and partitions", "lsblk", listOf("lsblk")),

        CatalogEntry("net-ports", NET, "Listening ports", "ss -tulpn", listOf("ss")),
        CatalogEntry("net-addr", NET, "IP addresses", "ip -br addr", listOf("ip")),
        CatalogEntry("net-route", NET, "Routes", "ip route", listOf("ip")),
        CatalogEntry("net-ping", NET, "Ping a host", "ping -c 4 {host:Host}"),

        CatalogEntry("sys-load", SYS, "Uptime and load", "uptime"),
        CatalogEntry("sys-mem", SYS, "Memory", "free -h", listOf("free")),
        CatalogEntry("sys-who", SYS, "Who’s logged in", "w"),
        CatalogEntry("sys-os", SYS, "OS and kernel", "uname -a; cat /etc/os-release"),
        CatalogEntry("sys-reboot", SYS, "Reboot", "sudo systemctl reboot", listOf("systemctl"), danger = true),

        CatalogEntry("pac-upgrade", PKG, "Update the system", "sudo pacman -Syu", listOf("pacman"), danger = true),
        CatalogEntry("pac-pending", PKG, "Pending updates", "checkupdates", listOf("checkupdates")),
        CatalogEntry("pac-search", PKG, "Search packages", "pacman -Ss {package:Package}", listOf("pacman")),
        CatalogEntry("pac-install", PKG, "Install a package", "sudo pacman -S {package:Package}", listOf("pacman"), danger = true),
        CatalogEntry("pac-orphans", PKG, "Orphaned packages", "pacman -Qdtq", listOf("pacman")),
        CatalogEntry("apt-upgrade", PKG, "Update the system", "sudo apt update && sudo apt upgrade", listOf("apt"), danger = true),
        CatalogEntry("apt-search", PKG, "Search packages", "apt search {package:Package}", listOf("apt")),
        CatalogEntry("apt-install", PKG, "Install a package", "sudo apt install {package:Package}", listOf("apt"), danger = true),
        CatalogEntry("dnf-upgrade", PKG, "Update the system", "sudo dnf upgrade", listOf("dnf"), danger = true),
        CatalogEntry("dnf-search", PKG, "Search packages", "dnf search {package:Package}", listOf("dnf")),
        CatalogEntry("dnf-install", PKG, "Install a package", "sudo dnf install {package:Package}", listOf("dnf"), danger = true),

        CatalogEntry("file-view", FILES, "View a file", "less {file}"),
        CatalogEntry("file-edit", FILES, "Edit a file", "\${EDITOR:-vi} {file}"),
        CatalogEntry("file-find", FILES, "Find files by name", "find {folder} -iname {pattern:Name, e.g. *.conf}"),
        CatalogEntry("file-grep", FILES, "Search text in files", "grep -rn {text:Text to find} {folder}"),
    )

    /**
     * Entries for a server with [tools]. Docker entries switch to podman where only podman
     * exists. Unknown tools (probe failed) show everything.
     */
    fun available(tools: Set<String>?): List<CatalogEntry> {
        if (tools == null) return all
        val podmanOnly = "docker" !in tools && "podman" in tools
        return all.mapNotNull { entry ->
            val needs = entry.requires.map { if (podmanOnly && it == "docker") "podman" else it }
            if (!needs.all { it in tools }) return@mapNotNull null
            if (podmanOnly && "docker" in entry.requires) entry.copy(template = entry.template.replace("docker ", "podman ")) else entry
        }
    }

    /** Lists the server offers for a field type: value to insert, and a line describing it. */
    fun sourceCommand(type: FieldType, engine: String = "docker"): String? = when (type) {
        FieldType.UNIT -> "systemctl list-units --all --type=service --no-legend --plain --no-pager 2>/dev/null"
        FieldType.CONTAINER -> "$engine ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' 2>/dev/null"
        FieldType.PROCESS -> "ps -eo pid=,comm=,%cpu=,user= --sort=-%cpu 2>/dev/null | head -400"
        else -> null
    }

    data class Choice(val value: String, val label: String, val detail: String)

    fun parseChoices(type: FieldType, output: String): List<Choice> = output.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        when (type) {
            FieldType.UNIT -> {
                // nginx.service loaded active running A high performance web server
                val parts = line.split(Regex("\\s+"), limit = 5)
                if (parts.size < 4) return@mapNotNull null
                val name = parts[0].removeSuffix(".service")
                Choice(name, name, "${parts[2]} · ${parts[3]}" + (parts.getOrNull(4)?.let { " — $it" } ?: ""))
            }
            FieldType.CONTAINER -> {
                val parts = raw.split('\t')
                Choice(parts[0], parts[0], listOfNotNull(parts.getOrNull(1), parts.getOrNull(2)).joinToString(" · "))
            }
            FieldType.PROCESS -> {
                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.size < 3) return@mapNotNull null
                Choice(parts[0], parts[1], "pid ${parts[0]} · ${parts[2]}% cpu" + (parts.getOrNull(3)?.let { " · $it" } ?: ""))
            }
            else -> null
        }
    }.toList()
}
