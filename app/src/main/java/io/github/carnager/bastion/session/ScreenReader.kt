package io.github.carnager.bastion.session

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator
import java.lang.reflect.Method

/** What's on screen, as far as the command line features need it. */
data class Screen(
    val lines: List<String>,
    /** Per line: continues on the next line because it was too long (not a real newline). */
    val softWrapped: List<Boolean>,
    val cursorRow: Int,
    val cursorCol: Int,
    /** Full-screen programs (vim, htop, less) use the alternate screen; leave those alone. */
    val alternateScreen: Boolean,
    /** Foreground color per column, only for the rows of the cursor's line (others null). */
    val colors: List<LongArray?> = emptyList(),
) {
    val cursorLine: String get() = lines.getOrElse(cursorRow) { "" }
}

/**
 * Reads termlib's screen snapshot. termlib keeps it `internal`, so this goes through
 * reflection; if a termlib update changes the shape, [read] returns null and the
 * features built on it switch themselves off instead of crashing.
 */
class ScreenReader(private val emulator: TerminalEmulator) {
    private val flow: StateFlow<*>? = runCatching {
        emulator.javaClass.getMethod("getSnapshot\$lib").invoke(emulator) as StateFlow<*>
    }.onFailure { Log.w(TAG, "termlib screen snapshot unavailable; command line features off", it) }.getOrNull()

    /** Emits on every screen change. */
    val changes: StateFlow<*>? get() = flow

    val available: Boolean get() = flow != null

    fun read(): Screen? {
        val snapshot = flow?.value ?: return null
        return runCatching {
            val type = snapshot.javaClass
            val lines = type.method("getLines").invoke(snapshot) as List<*>
            val texts = ArrayList<String>(lines.size)
            val wrapped = ArrayList<Boolean>(lines.size)
            val lineObjects = lines.toList()
            for (line in lineObjects) {
                if (line == null) {
                    texts += ""
                    wrapped += false
                    continue
                }
                val lineType = line.javaClass
                // Column text keeps one character per cell, so cursor columns index into it directly.
                val text = (lineType.optionalMethod("getColumnText\$lib") ?: lineType.method("getText")).invoke(line) as String
                texts += text
                wrapped += lineType.method("getSoftWrapped").invoke(line) as Boolean
            }
            val cursorRow = type.method("getCursorRow").invoke(snapshot) as Int
            // Colors tell typed text from ghost text (e.g. zsh-autosuggestions) after the cursor.
            var first = cursorRow
            while (first > 0 && wrapped.getOrElse(first - 1) { false }) first--
            var last = cursorRow
            while (wrapped.getOrElse(last) { false } && last + 1 < lineObjects.size) last++
            val colors = lineObjects.indices.map { row -> if (row in first..last) colorsOf(lineObjects[row], texts[row].length) else null }
            Screen(
                lines = texts,
                softWrapped = wrapped,
                cursorRow = cursorRow,
                cursorCol = type.method("getCursorCol").invoke(snapshot) as Int,
                alternateScreen = type.method("getAlternateScreen").invoke(snapshot) as Boolean,
                colors = colors,
            )
        }.onFailure { Log.w(TAG, "Could not read terminal screen", it) }.getOrNull()
    }

    private fun colorsOf(line: Any?, count: Int): LongArray? = runCatching {
        line ?: return null
        val cells = line.javaClass.method("getCells").invoke(line) ?: return null
        // Compose's Color is an inline class, so the getter carries a mangled name.
        val fg = cells.javaClass.methods.firstOrNull { it.name.startsWith("foreground") && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            ?: return null
        val size = cells.javaClass.method("getSize").invoke(cells) as Int
        LongArray(minOf(count, size)) { col -> fg.invoke(cells, col) as Long }
    }.getOrNull()

    private companion object {
        val methodCache = HashMap<Pair<Class<*>, String>, Method?>()

        fun Class<*>.optionalMethod(name: String): Method? = synchronized(methodCache) {
            methodCache.getOrPut(this to name) { runCatching { getMethod(name) }.getOrNull() }
        }

        fun Class<*>.method(name: String): Method = optionalMethod(name) ?: throw NoSuchMethodException(name)
    }
}
