package io.github.carnager.bastion

import io.github.carnager.bastion.session.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxScriptTest {
    private fun sh(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun scriptIsValidShell() {
        val (code, out) = sh("sh", "-n", "-c", TerminalSession.tmuxScript("my-session"))
        assertEquals(out, 0, code)
    }

    @Test fun fallsBackToALoginShellWithoutTmux() {
        // No tmux in PATH, SHELL pointing at something harmless that just reports how it was called.
        val script = TerminalSession.tmuxScript("x")
        val (_, out) = sh("env", "-i", "PATH=/nonexistent", "SHELL=/bin/echo", "/bin/sh", "-c", script)
        assertTrue(out, out.contains("tmux is not installed"))
        assertTrue(out, out.trim().endsWith("-l"))
    }
}
