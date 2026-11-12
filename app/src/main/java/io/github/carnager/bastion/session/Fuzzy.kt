package io.github.carnager.bastion.session

/**
 * fzf-style fuzzy matching: the query's characters must appear in order; matches score higher
 * when consecutive, at word/path boundaries, and in a path's last segment. Smart case: a query
 * with an uppercase letter matches case-sensitively.
 */
object Fuzzy {
    data class Match(val text: String, val score: Int, val positions: IntArray)

    private const val SCORE_MATCH = 16
    private const val BONUS_BOUNDARY = 10
    private const val BONUS_CONSECUTIVE = 12
    private const val BONUS_FIRST_CHAR = 8
    private const val BONUS_BASENAME = 6
    private const val PENALTY_GAP_START = 3
    private const val PENALTY_GAP_EXTEND = 1

    fun match(query: String, text: String): Match? {
        if (query.isEmpty()) return Match(text, 0, IntArray(0))
        val caseSensitive = query.any { it.isUpperCase() }
        fun eq(a: Char, b: Char) = if (caseSensitive) a == b else a.lowercaseChar() == b.lowercaseChar()

        // Forward pass: earliest end of a match; backward pass: latest start, for the tightest window.
        var qi = 0
        var end = -1
        for (i in text.indices) {
            if (eq(text[i], query[qi]) && ++qi == query.length) {
                end = i
                break
            }
        }
        if (end < 0) return null
        qi = query.length - 1
        var start = end
        for (i in end downTo 0) {
            if (eq(text[i], query[qi])) {
                start = i
                if (--qi < 0) break
            }
        }

        // Within the window, prefer boundary positions: greedy from the left, but jump to a
        // boundary occurrence of the same character when one comes before the next needed one.
        val positions = IntArray(query.length)
        var pos = start
        for (k in query.indices) {
            var chosen = -1
            var i = pos
            while (i <= end) {
                if (eq(text[i], query[k])) {
                    if (chosen < 0) chosen = i
                    if (isBoundary(text, i)) {
                        chosen = i
                        break
                    }
                    if (k + 1 < query.length && i + 1 <= end && eq(text[i + 1], query[k + 1])) break
                }
                i++
            }
            if (chosen < 0) return null
            positions[k] = chosen
            pos = chosen + 1
        }

        val basename = text.trimEnd('/').lastIndexOf('/') + 1
        var score = 0
        for (k in positions.indices) {
            val p = positions[k]
            score += SCORE_MATCH
            if (isBoundary(text, p)) score += BONUS_BOUNDARY
            if (k > 0) {
                val gap = p - positions[k - 1] - 1
                score += if (gap == 0) BONUS_CONSECUTIVE else -(PENALTY_GAP_START + (gap - 1) * PENALTY_GAP_EXTEND)
            }
            if (p >= basename) score += BONUS_BASENAME
        }
        if (positions[0] == 0) score += BONUS_FIRST_CHAR
        // Shorter candidates win ties.
        score -= text.length / 8
        return Match(text, score, positions)
    }

    private fun isBoundary(text: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = text[i - 1]
        val c = text[i]
        return prev == '/' || prev == '_' || prev == '-' || prev == '.' || prev == ' ' ||
            (prev.isLowerCase() && c.isUpperCase()) || (!prev.isLetterOrDigit() && c.isLetterOrDigit())
    }

    /** Best [limit] matches, best first. */
    fun filter(query: String, items: List<String>, limit: Int = 200): List<Match> {
        if (query.isBlank()) return items.take(limit).map { Match(it, 0, IntArray(0)) }
        val q = query.trim()
        return items.asSequence()
            .mapNotNull { match(q, it) }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.text.length })
            .take(limit)
            .toList()
    }
}

/** Tracks the shell's directory from OSC 7 ("ESC ] 7 ; file://host/path BEL") in the output. */
class Osc7Sniffer {
    @Volatile var cwd: String? = null
        private set

    private val pending = StringBuilder()
    private var inOsc = false

    fun feed(bytes: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            if (!inOsc) {
                // Look for ESC ] 7 ;
                pending.append(b.toChar())
                val p = pending.toString()
                when {
                    PREFIX.startsWith(p) -> if (p == PREFIX) { inOsc = true; pending.clear() }
                    else -> { pending.clear(); if (b == 0x1b) pending.append('\u001b') }
                }
            } else {
                when {
                    b == 0x07 || (b == '\\'.code && pending.endsWith("\u001b")) -> {
                        val body = pending.toString().removeSuffix("\u001b")
                        parse(body)?.let { cwd = it }
                        pending.clear()
                        inOsc = false
                    }
                    pending.length > 4096 -> { pending.clear(); inOsc = false }
                    else -> pending.append(b.toChar())
                }
            }
        }
    }

    companion object {
        private const val PREFIX = "\u001b]7;"

        /** "file://host/some%20dir" -> "/some dir". Bytes arrived as Latin-1 chars; decode as UTF-8. */
        fun parse(url: String): String? {
            val raw = String(url.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (!raw.startsWith("file://")) return null
            val path = raw.removePrefix("file://").substringAfter('/', "").let { "/$it" }
            return runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull()
        }

        /** A path shown in the prompt (or the line above it): the last token starting with ~ or /. */
        fun fromPrompt(promptLines: List<String>): String? {
            val regex = Regex("""(?<![\w.])(~[^\s$#%>❯:\]\)]*|/[^\s$#%>❯:\]\)]*)""")
            return promptLines.asReversed().firstNotNullOfOrNull { line -> regex.findAll(line).lastOrNull()?.value?.trimEnd('/')?.ifEmpty { "/" } }
        }
    }
}

/** Shell quoting for inserting paths: plain words stay as they are, and a leading ~/ stays unquoted so it expands. */
fun shellQuote(s: String): String {
    fun safe(c: Char) = c.isLetterOrDigit() || c in "/._-+:@,=%"
    fun quote(x: String) = "'" + x.replace("'", "'\\''") + "'"
    if (s == "~") return s
    if (s.startsWith("~/")) {
        val rest = s.removePrefix("~/")
        return if (rest.all(::safe)) s else "~/" + quote(rest)
    }
    return if (s.isNotEmpty() && s.all(::safe)) s else quote(s)
}
