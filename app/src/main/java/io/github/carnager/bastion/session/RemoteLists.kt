package io.github.carnager.bastion.session

enum class FinderKind(val label: String) {
    BROWSE("Browse"), ACTIONS("Commands"), FILES("Files"), FOLDERS("Folders"), COMMANDS("Programs"), HISTORY("History"),
}

/** One entry of a remote folder, from SFTP. [isDir] follows symlinks. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
)

data class RemoteDir(val path: String, val home: String, val entries: List<RemoteEntry>) {
    /** [path] with the home folder shown as ~. */
    val display: String get() = tilde(path, home)

    companion object {
        fun tilde(path: String, home: String): String = when {
            path == home -> "~"
            home.length > 1 && path.startsWith("$home/") -> "~/" + path.removePrefix("$home/")
            else -> path
        }
    }
}

/** Shell scripts that list candidates on the server, run through a separate exec channel. */
object RemoteLists {
    const val LIMIT = 30000

    private fun cd(base: String): String = when {
        base == "~" -> "cd"
        base.startsWith("~/") -> "cd -- \"\$HOME\"/" + shellQuote(base.removePrefix("~/"))
        else -> "cd -- " + shellQuote(base)
    }

    /** Wrapped in `sh -c` so it runs the same under bash, zsh or fish login shells. */
    fun command(kind: FinderKind, base: String): String? {
        val script = when (kind) {
            FinderKind.FILES, FinderKind.FOLDERS -> {
                val type = if (kind == FinderKind.FILES) "f" else "d"
                // fd honours .gitignore and is fast; find is everywhere. Skip VCS and dependency dirs.
                """
                ${cd(base)} || exit 3
                if command -v fd >/dev/null 2>&1; then F=fd; elif command -v fdfind >/dev/null 2>&1; then F=fdfind; fi
                if [ -n "${'$'}F" ]; then
                  ${'$'}F --hidden --exclude .git --type $type --max-depth 10 --color never . 2>/dev/null
                else
                  find . -mindepth 1 -maxdepth 10 \( -name .git -o -name node_modules -o -name .cache -o -name __pycache__ \) -prune -o -type $type -print 2>/dev/null | sed 's|^\./||'
                fi | head -n $LIMIT
                """.trimIndent()
            }
            FinderKind.COMMANDS ->
                """
                if command -v bash >/dev/null 2>&1; then bash -c 'compgen -c'
                else IFS=:; for d in ${'$'}PATH; do ls -1 "${'$'}d" 2>/dev/null; done; fi | sort -u | head -n $LIMIT
                """.trimIndent()
            FinderKind.HISTORY, FinderKind.BROWSE, FinderKind.ACTIONS -> return null
        }
        return "sh -c " + shellQuote(script)
    }

    fun parse(output: String, kind: FinderKind): List<String> = output.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && it != "." }
        .map { if (kind == FinderKind.FOLDERS) it.trimEnd('/') + "/" else it }
        .toList()

    /** Joins a base directory and a relative path for display/insertion. */
    fun join(base: String, relative: String): String = when {
        base == "/" -> "/$relative"
        else -> base.trimEnd('/') + "/" + relative
    }
}
