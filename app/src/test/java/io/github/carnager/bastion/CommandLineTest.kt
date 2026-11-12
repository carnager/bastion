package io.github.carnager.bastion

import io.github.carnager.bastion.session.CommandLine
import io.github.carnager.bastion.session.Screen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandLineTest {
    private fun screen(vararg lines: String, row: Int, col: Int, wrapped: List<Boolean>? = null, alt: Boolean = false) =
        Screen(lines.toList(), wrapped ?: lines.map { false }, row, col, alt)

    @Test fun promptIsWhatPrecedesTheCursorAtFirstKeystroke() {
        val s = screen("carnager@proteus ~", "❯ ", row = 1, col = 2)
        assertEquals(CommandLine.Prompt("❯ ", ""), CommandLine.capturePrompt(s))
    }

    @Test fun learnedPromptIsStripped() {
        val prompt = CommandLine.Prompt("user@box:~/src$ ", "")
        val s = screen("user@box:~/src$ git status --short      ", row = 0, col = 30)
        assertEquals("git status --short", CommandLine.current(s, prompt))
    }

    @Test fun rightPromptIsStripped() {
        val prompt = CommandLine.Prompt("❯ ", "12:04")
        assertEquals("ls -la", CommandLine.strip("❯ ls -la                         12:04", prompt))
    }

    @Test fun softWrappedCommandIsJoined() {
        val prompt = CommandLine.Prompt("$ ", "")
        val s = screen("$ echo aaaaaaaa", "bbbb", row = 1, col = 4, wrapped = listOf(true, false))
        assertEquals("echo aaaaaaaabbbb", CommandLine.current(s, prompt))
    }

    @Test fun markerHeuristicWithoutLearnedPrompt() {
        assertEquals("make -j8", CommandLine.strip("root@host:/srv# make -j8", null))
        assertEquals("ls > out.txt", CommandLine.strip("~ $ ls > out.txt", null))
        assertNull(CommandLine.strip("plain output line", null))
    }

    @Test fun fullScreenAppsAreIgnored() {
        assertNull(CommandLine.current(screen("~ $ vim", row = 0, col = 7, alt = true), null))
    }

    @Test fun historyIsNewestFirstAndDeduplicated() {
        var h = emptyList<String>()
        h = CommandLine.remember(h, "ls")
        h = CommandLine.remember(h, "git status")
        h = CommandLine.remember(h, "ls")
        h = CommandLine.remember(h, "   ")
        assertEquals(listOf("ls", "git status"), h)
    }

    @Test fun suggestionsPreferPrefixThenSubstring() {
        val h = listOf("git status", "systemctl status nginx", "git stash pop", "ls")
        assertEquals(listOf("git status", "git stash pop", "systemctl status nginx"), CommandLine.suggestions(h, "git st").let {
            it + CommandLine.suggestions(h, "status").filter { s -> s !in it }
        }.take(3))
        assertEquals(listOf("git status", "git stash pop"), CommandLine.suggestions(h, "git"))
        assertEquals(emptyList<String>(), CommandLine.suggestions(h, ""))
        assertEquals(h.take(2), CommandLine.suggestions(h, "", limit = 2, recentWhenBlank = true))
    }

    @Test fun replaceLineClearsThenTypes() {
        assertArrayEquals("\u0005\u0015ls -l\r".toByteArray(), CommandLine.replaceLine("ls -l", run = true))
        assertArrayEquals("\u0005\u0015ls -l".toByteArray(), CommandLine.replaceLine("ls -l", run = false))
    }
}

class GhostTextTest {
    @Test fun dimTailAfterCursorIsDropped() {
        // "❯ ls" typed in white, " /etc/systemd" suggested in grey, cursor right after "ls".
        val text = "❯ ls /etc/systemd"
        val colors = LongArray(text.length) { if (it < 4) 1L else 8L }
        org.junit.Assert.assertEquals("❯ ls", CommandLine.withoutGhostText(text, 4, colors))
    }

    @Test fun realTextAfterCursorStays() {
        // Cursor moved back into "ls -la": the rest has the typed text's color.
        val text = "❯ ls -la"
        val colors = LongArray(text.length) { 1L }
        org.junit.Assert.assertEquals(text, CommandLine.withoutGhostText(text, 4, colors))
    }

    @Test fun unknownColorsChangeNothing() {
        org.junit.Assert.assertEquals("❯ ls x", CommandLine.withoutGhostText("❯ ls x", 4, null))
    }
}

class SudoPromptTest {
    private fun at(line: String, last: String? = null, above: String = "") =
        CommandLine.atSudoPrompt(Screen(listOf(above, line), listOf(false, false), 1, line.length, false), last)

    @Test fun recognisesSudoAndDoasPrompts() {
        org.junit.Assert.assertTrue(at("[sudo] password for carnager: "))
        org.junit.Assert.assertTrue(at("doas (carnager@proteus) password: "))
        org.junit.Assert.assertTrue(at("Password:", last = "sudo systemctl restart caddy"))
    }

    @Test fun otherPasswordPromptsAreLeftAlone() {
        org.junit.Assert.assertFalse(at("Password:", last = "su -"))
        org.junit.Assert.assertFalse(at("carnager@host's password: ", last = "ssh host"))
        org.junit.Assert.assertFalse(at("❯ echo [sudo] password for x: done"))
    }

    @Test fun retryIsNoticed() {
        val s = Screen(listOf("Sorry, try again.", "[sudo] password for carnager: "), listOf(false, false), 1, 30, false)
        org.junit.Assert.assertTrue(CommandLine.sudoRetry(s))
    }
}
