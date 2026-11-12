package io.github.carnager.bastion.session

/**
 * The shell's current input line, as seen on screen. The prompt is learned at the first
 * keystroke after each Enter: at that moment the line holds nothing but the prompt.
 */
object CommandLine {
    const val HISTORY_LIMIT = 500

    /** Common prompt endings, for lines typed before any prompt was learned. */
    private val MARKERS = listOf("$ ", "# ", "% ", "❯ ", "➜ ", "λ ", "» ", "→ ", "> ")

    data class Prompt(val text: String, val rightPrompt: String)

    /** The cursor's logical line (soft-wrapped rows joined) and the cursor's offset in it. */
    fun logicalLine(screen: Screen): Pair<String, Int> {
        var start = screen.cursorRow
        while (start > 0 && screen.softWrapped.getOrElse(start - 1) { false }) start--
        val before = (start until screen.cursorRow).joinToString("") { screen.lines[it] }
        var end = screen.cursorRow
        while (screen.softWrapped.getOrElse(end) { false } && end + 1 < screen.lines.size) end++
        val text = (start..end).joinToString("") { screen.lines.getOrElse(it) { "" } }
        return text to (before.length + screen.cursorCol).coerceAtMost(text.length)
    }

    /** Called right before the first keystroke after a prompt appeared. */
    fun capturePrompt(screen: Screen): Prompt? {
        if (screen.alternateScreen) return null
        val (text, cursor) = logicalLine(screen)
        val prompt = text.substring(0, cursor)
        if (prompt.isBlank()) return null
        return Prompt(prompt, text.substring(cursor).trim())
    }

    /** What's typed after the prompt, or null when it can't be told apart from the prompt. */
    fun current(screen: Screen, prompt: Prompt?): String? {
        if (screen.alternateScreen) return null
        val (text, cursor) = logicalLine(screen)
        return strip(withoutGhostText(text, cursor, logicalColors(screen)), prompt)
    }

    /** Colors of the cursor's logical line, joined like [logicalLine]; null if unknown. */
    fun logicalColors(screen: Screen): LongArray? {
        var start = screen.cursorRow
        while (start > 0 && screen.softWrapped.getOrElse(start - 1) { false }) start--
        var end = screen.cursorRow
        while (screen.softWrapped.getOrElse(end) { false } && end + 1 < screen.lines.size) end++
        val parts = (start..end).map { row ->
            val colors = screen.colors.getOrNull(row) ?: return null
            if (colors.size < screen.lines[row].length) return null
            colors.copyOf(screen.lines[row].length)
        }
        return parts.fold(LongArray(0)) { acc, part -> acc + part }
    }

    /**
     * Drops text after the cursor that is one color different from what precedes it: that's a
     * shell's inline suggestion (zsh-autosuggestions, fish), not input. Real text after the
     * cursor (the user moved left to edit) keeps the typed text's colors.
     */
    fun withoutGhostText(text: String, cursor: Int, colors: LongArray?): String {
        val tailEnd = text.trimEnd().length
        if (colors == null || cursor <= 0 || cursor >= tailEnd || tailEnd > colors.size) return text
        val tail = colors.copyOfRange(cursor, tailEnd)
        val ghost = tail.all { it == tail[0] } && tail[0] != colors[cursor - 1]
        return if (ghost) text.substring(0, cursor) else text
    }

    fun strip(line: String, prompt: Prompt?): String? {
        var text = line.trimEnd()
        if (prompt != null && text.startsWith(prompt.text.trimEnd())) {
            text = text.removePrefix(prompt.text.trimEnd())
            if (prompt.rightPrompt.isNotEmpty() && text.endsWith(prompt.rightPrompt)) {
                text = text.removeSuffix(prompt.rightPrompt)
            }
            return text.trim()
        }
        val marker = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 }?.let { it to m } }.minByOrNull { it.first }
            ?: return null
        return text.substring(marker.first + marker.second.length).trim()
    }

    /** Newest first, without duplicates, capped. */
    fun remember(history: List<String>, command: String): List<String> {
        val cmd = command.trim()
        if (cmd.isEmpty()) return history
        return (listOf(cmd) + history.filter { it != cmd }).take(HISTORY_LIMIT)
    }

    /**
     * History entries for what's typed so far: prefix matches first (they complete the line),
     * then entries containing it. Blank input lists the most recent ones when [recentWhenBlank].
     */
    fun suggestions(history: List<String>, typed: String, limit: Int = 6, recentWhenBlank: Boolean = false): List<String> {
        if (typed.isBlank()) return if (recentWhenBlank) history.take(limit) else emptyList()
        val prefix = history.filter { it.startsWith(typed) && it != typed }
        val inside = if (typed.trim().length >= 2) history.filter { typed.trim() in it && !it.startsWith(typed) } else emptyList()
        return (prefix + inside).distinct().take(limit)
    }

    private val SUDO_PROMPT = Regex("""(\[sudo] password for [^:]+|doas \([^)]+\) password|\[sudo: authenticate] Password):\s*$""")

    /**
     * The cursor sits on a sudo/doas password prompt. "Password:" alone counts only right
     * after a sudo command, so ssh or su prompts aren't mistaken for it.
     */
    fun atSudoPrompt(screen: Screen, lastCommand: String?): Boolean {
        if (screen.alternateScreen) return false
        val line = screen.cursorLine.substring(0, minOf(screen.cursorCol, screen.cursorLine.length)).trimEnd() + " "
        if (SUDO_PROMPT.containsMatchIn(line)) return true
        return line.trim() == "Password:" && lastCommand?.trimStart()?.startsWith("sudo ") == true
    }

    /** sudo rejected the password just typed (the line above the new prompt says so). */
    fun sudoRetry(screen: Screen): Boolean =
        screen.lines.getOrNull(screen.cursorRow - 1)?.trim()?.startsWith("Sorry, try again") == true

    /** Bytes that make the shell's line read [text]: end of line, kill to start, then the text. */
    fun replaceLine(text: String, run: Boolean): ByteArray =
        ("\u0005\u0015" + text + if (run) "\r" else "").encodeToByteArray()
}
