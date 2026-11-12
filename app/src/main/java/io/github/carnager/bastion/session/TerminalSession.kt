package io.github.carnager.bastion.session

import android.util.Log
import androidx.compose.ui.graphics.Color
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.TermScheme
import io.github.carnager.bastion.ssh.Connector
import io.github.carnager.bastion.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelShell
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.time.Duration

sealed interface SessionStatus {
    data object Connecting : SessionStatus
    data object Connected : SessionStatus
    /** [error] is null when the remote shell simply exited. */
    data class Ended(val error: String?) : SessionStatus
}

class TerminalSession(
    val id: Long,
    val host: Host,
    private val connector: Connector,
    private val scope: CoroutineScope,
    /** Effective color scheme; changes apply to the open terminal immediately. */
    val scheme: StateFlow<TermScheme>,
    /** A command the user just ran (Enter on the prompt line, or the input field). */
    private val onCommand: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Connecting)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    /** Everything bound for the server, written by one I/O coroutine: MINA must never be called on the main thread. */
    private sealed interface Outgoing {
        class Bytes(val data: ByteArray) : Outgoing
        data class Resize(val columns: Int, val rows: Int) : Outgoing
    }

    private val outgoing = Channel<Outgoing>(Channel.UNLIMITED)
    @Volatile private var connection: SshConnection? = null
    @Volatile private var shell: ChannelShell? = null
    private var job: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        defaultForeground = Color(scheme.value.argb(scheme.value.foreground)),
        defaultBackground = Color(scheme.value.argb(scheme.value.background)),
        onKeyboardInput = { bytes -> beforeInput(bytes); send(bytes) },
        onResize = { size -> if (shell != null) outgoing.trySend(Outgoing.Resize(size.columns, size.rows)) },
        autoDetectUrls = true,
    )

    val screen: ScreenReader = ScreenReader(emulator)

    /** The shell's directory as reported by OSC 7, if the shell does that. */
    val osc7 = Osc7Sniffer()

    /** The prompt line and the one above it, from the last capture (prompts often show the directory). */
    @Volatile private var promptLines: List<String> = emptyList()

    /** Best guess at the shell's working directory: OSC 7, else a path in the prompt, else home. */
    fun guessCwd(): String = osc7.cwd ?: Osc7Sniffer.fromPrompt(promptLines) ?: "~"

    /** True when the directory comes from the shell itself rather than a guess. */
    val cwdIsReported: Boolean get() = osc7.cwd != null

    private val listCache = HashMap<Pair<FinderKind, String>, Pair<Long, List<String>>>()

    /** Candidates for the finder, listed on the server; cached briefly per kind and directory. */
    suspend fun list(kind: FinderKind, base: String, refresh: Boolean = false): List<String> {
        val key = kind to base
        synchronized(listCache) {
            listCache[key]?.let { (time, items) ->
                if (!refresh && System.currentTimeMillis() - time < LIST_CACHE_MS) return items
            }
        }
        val command = RemoteLists.command(kind, base) ?: return emptyList()
        val items = RemoteLists.parse(exec(command), kind)
        synchronized(listCache) { listCache[key] = System.currentTimeMillis() to items }
        return items
    }

    /** Runs [command] on a separate exec channel of the same SSH connection; nothing reaches the shell. */
    suspend fun exec(command: String, maxBytes: Int = 8 * 1024 * 1024): String = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val channel = conn.session.createExecChannel(command)
        try {
            channel.open().verify(OPEN_TIMEOUT)
            val out = java.io.ByteArrayOutputStream()
            val input = channel.invertedOut
            val buffer = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            channel.close(false)
        }
    }

    /** The prompt as captured at the first keystroke after the last Enter. */
    @Volatile var prompt: CommandLine.Prompt? = null
        private set
    private var typedSinceEnter = false

    private fun beforeInput(bytes: ByteArray) {
        if (shell == null) return
        val view = screen.read() ?: return
        if (!typedSinceEnter) {
            CommandLine.capturePrompt(view)?.let {
                prompt = it
                promptLines = listOf(view.lines.getOrElse(view.cursorRow - 1) { "" }, it.text)
            }
            typedSinceEnter = true
        }
        if (bytes.contains(CR)) {
            // The screen still shows the line as typed: the server hasn't echoed the newline yet.
            prompt?.let { p -> CommandLine.current(view, p)?.takeIf { it.isNotBlank() }?.let(onCommand) }
            typedSinceEnter = false
        }
    }

    /** What's typed after the prompt right now, or null if unknown (full-screen app, no prompt yet). */
    fun currentCommand(): String? = screen.read()?.let { CommandLine.current(it, prompt) }

    /** Replace the shell's input line with [text], and run it when [run]. */
    fun replaceLine(text: String, run: Boolean) {
        if (shell == null) return
        send(CommandLine.replaceLine(text, run))
        if (run) {
            if (text.isNotBlank()) onCommand(text.trim())
            typedSinceEnter = false
        } else {
            typedSinceEnter = true
        }
    }

    private val schemeJob = scope.launch(Dispatchers.Main) {
        scheme.collect { s ->
            runCatching { emulator.applyColorScheme(s.ansiArgb, s.argb(s.foreground), s.argb(s.background)) }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        _status.value = SessionStatus.Connecting
        emulator.writeInput("\u001b[2mConnecting to ${host.address}…\u001b[0m\r\n".encodeToByteArray())
        job = scope.launch(Dispatchers.IO) {
            try {
                val conn = connector.connect(host).also { connection = it }
                val dims = emulator.dimensions
                val channel = conn.session.createShellChannel().apply {
                    ptyType = "xterm-256color"
                    ptyColumns = dims.columns
                    ptyLines = dims.rows
                    isRedirectErrorStream = true
                }
                channel.open().verify(OPEN_TIMEOUT)
                shell = channel
                // The view may have resized while the channel was opening.
                emulator.dimensions.let { outgoing.trySend(Outgoing.Resize(it.columns, it.rows)) }
                _status.value = SessionStatus.Connected
                launch { pumpOutgoing(channel) }
                val input = channel.invertedOut
                val buffer = ByteArray(16 * 1024)
                while (isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        osc7.feed(buffer, read)
                        emulator.writeInput(buffer, 0, read)
                    }
                }
                Log.i(TAG, "Session $id to ${host.label}: remote closed the shell")
                finish(null)
            } catch (e: Exception) {
                Log.w(TAG, "Session $id to ${host.label} failed", e)
                finish(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun pumpOutgoing(channel: ChannelShell) {
        val out = channel.invertedIn
        for (item in outgoing) {
            when (item) {
                is Outgoing.Bytes -> {
                    out.write(item.data)
                    out.flush()
                }
                is Outgoing.Resize -> channel.sendWindowChange(item.columns, item.rows)
            }
        }
    }

    /** Text from the extra-keys row, snippets or the clipboard, sent as if typed. */
    fun send(bytes: ByteArray) {
        if (shell != null) outgoing.trySend(Outgoing.Bytes(bytes))
    }

    private fun finish(error: String?) {
        if (_status.value is SessionStatus.Ended) return
        shell = null
        connection?.close()
        connection = null
        val note = if (error == null) "Connection closed." else "Error: $error"
        runCatching { emulator.writeInput("\r\n\u001b[2m$note\u001b[0m\r\n".encodeToByteArray()) }
        _status.value = SessionStatus.Ended(error)
    }

    /** Reconnects into the same terminal, keeping the old output above. */
    fun reconnect() {
        if (_status.value !is SessionStatus.Ended) return
        while (outgoing.tryReceive().isSuccess) Unit
        start()
    }

    fun close() {
        job?.cancel()
        schemeJob.cancel()
        shell = null
        connection?.close()
        connection = null
        outgoing.close()
        emulator.close()
    }

    private companion object {
        const val CR: Byte = 0x0d
        const val LIST_CACHE_MS = 30_000L
        val OPEN_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
