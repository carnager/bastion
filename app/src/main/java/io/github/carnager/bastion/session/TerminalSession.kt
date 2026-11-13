package io.github.carnager.bastion.session

import android.util.Log
import androidx.compose.ui.graphics.Color
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.TermScheme
import io.github.carnager.bastion.ssh.Connector
import io.github.carnager.bastion.ssh.SshConnection
import io.github.carnager.bastion.ssh.SshFailure
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
import org.apache.sshd.client.channel.PtyCapableChannelSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.time.Duration

sealed interface SessionStatus {
    data object Connecting : SessionStatus
    data object Connected : SessionStatus
    /** The connection dropped; trying again ([attempt] counts from 1). */
    data class Reconnecting(val attempt: Int, val reason: String) : SessionStatus
    /** [error] is null when the remote shell simply exited. */
    data class Ended(val error: String?) : SessionStatus
}

class TerminalSession(
    val id: Long,
    val host: Host,
    private val connector: Connector,
    private val scope: CoroutineScope,
    private val network: NetworkMonitor,
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
    @Volatile private var shell: PtyCapableChannelSession? = null
    private var job: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        defaultForeground = Color(scheme.value.argb(scheme.value.foreground)),
        defaultBackground = Color(scheme.value.argb(scheme.value.background)),
        onKeyboardInput = { bytes -> beforeInput(bytes); send(bytes) },
        onResize = { size -> if (shell != null) outgoing.trySend(Outgoing.Resize(size.columns, size.rows)) },
        autoDetectUrls = true,
    )

    val screen: ScreenReader = ScreenReader(emulator).apply {
        // tmux draws on the alternate screen; for tmux hosts, that's where the shell lives.
        alternateIsNormal = host.tmuxSession != null
    }

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

    @Volatile private var tools: Set<String>? = null

    /** Which of [Catalog.PROBED] exist on the server; probed once per connection, null if that failed. */
    suspend fun tools(): Set<String>? {
        tools?.let { return it }
        return runCatching { exec(Catalog.probeCommand()).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .getOrNull()?.also { tools = it }
    }

    /** Values the server offers for a picked field (services, containers, processes). */
    suspend fun choices(type: FieldType): List<Catalog.Choice> {
        val engine = if (tools()?.let { "docker" !in it && "podman" in it } == true) "podman" else "docker"
        val command = Catalog.sourceCommand(type, engine) ?: return emptyList()
        val choices = Catalog.parseChoices(type, exec("sh -c " + shellQuote(command)))
        return if (type == FieldType.UNIT) Services.sortUnits(choices) else choices
    }

    suspend fun overview(): Overview = Overviews.parse(exec("sh -c " + shellQuote(Overviews.SCRIPT)))

    suspend fun updates(): Overviews.Updates? = Overviews.parseUpdates(exec("sh -c " + shellQuote(Overviews.UPDATES_SCRIPT)))

    suspend fun processes(): List<ProcessInfo> = Processes.parse(exec(Processes.LIST))

    /** Full command line and owner of a process. */
    suspend fun processDetail(pid: Int): Pair<String, String> =
        exec("sh -c " + shellQuote(Processes.detail(pid))).lines().let { it.getOrElse(0) { "" }.trim() to it.getOrElse(1) { "" }.trim() }

    /** Sends [signal] as this user; output and exit status (not permitted → caller tries sudo). */
    suspend fun kill(pid: Int, signal: String): Pair<String, Int?> = execResult("sh -c " + shellQuote("kill -$signal $pid 2>&1"), null)

    suspend fun diskUsage(path: String): UsageScan = DiskUsage.parse(path, exec("sh -c " + shellQuote(DiskUsage.script(path))))

    suspend fun containerEngine(): String = if (tools()?.let { "docker" !in it && "podman" in it } == true) "podman" else "docker"

    /** Containers, or null when the daemon refuses this user (not in the docker group): use sudo then. */
    suspend fun containers(): List<ContainerInfo>? {
        val out = exec("sh -c " + shellQuote(Containers.list(containerEngine())))
        return if (Containers.permissionDenied(out)) null else Containers.parse(out)
    }

    suspend fun serviceInfo(unit: String): ServiceInfo = Services.parseShow(unit, exec(Services.showCommand(unit)))

    /** Recent journal entries of [unit], plus a notice if journalctl said something else (e.g. no permission). */
    suspend fun journal(unit: String, lines: Int = 100): Pair<List<LogLine>, String?> {
        val (entries, notice) = Services.parseJournal(exec("sh -c " + shellQuote(Services.journalCommand(unit, lines))))
        if (entries.isNotEmpty() || notice != null) return entries to notice
        // journalctl stays silent without a terminal when it may not read system logs.
        val groups = runCatching { exec("id -nG; id -u") }.getOrDefault("")
        val privileged = groups.split(Regex("\\s+")).any { it in Services.LOG_GROUPS || it == "0" }
        return entries to if (privileged) null else Services.NO_LOG_ACCESS

    }

    @Volatile private var sftp: SftpClient? = null

    private fun sftpClient(): SftpClient {
        sftp?.takeIf { it.isOpen }?.let { return it }
        val conn = connection ?: throw IllegalStateException("Not connected")
        return SftpClientFactory.instance().createSftpClient(conn.session).also { sftp = it }
    }

    /** Lists a folder over SFTP on this connection. "~" and "~/…" are resolved against the home folder. */
    suspend fun listDirectory(path: String): RemoteDir = withContext(Dispatchers.IO) {
        val client = sftpClient()
        val home = client.canonicalPath(".")
        val wanted = when {
            path == "~" -> home
            path.startsWith("~/") -> home.trimEnd('/') + "/" + path.removePrefix("~/")
            else -> path
        }
        val dir = client.canonicalPath(wanted)
        val entries = client.readDir(dir).mapNotNull { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            val full = if (dir == "/") "/$name" else "$dir/$name"
            val attrs = entry.attributes
            val isDir = attrs.isDirectory || (attrs.isSymbolicLink && runCatching { client.stat(full).isDirectory }.getOrDefault(false))
            RemoteEntry(name, full, isDir, attrs.isSymbolicLink, attrs.size, attrs.modifyTime?.toMillis() ?: 0L)
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
        RemoteDir(dir, home, entries)
    }

    private fun resolve(client: SftpClient, path: String): String {
        val home by lazy { client.canonicalPath(".") }
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home.trimEnd('/') + "/" + path.removePrefix("~/")
            else -> path
        }
    }

    /** Reads a file over SFTP for the viewer; at most [maxBytes], binary files come back without text. */
    suspend fun readFile(path: String, maxBytes: Int = 4 * 1024 * 1024): RemoteFile = withContext(Dispatchers.IO) {
        val client = sftpClient()
        val full = client.canonicalPath(resolve(client, path))
        val attrs = client.stat(full)
        val bytes = client.read(full).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val binary = bytes.take(8192).any { it == 0.toByte() }
        RemoteFile(
            path = full,
            text = if (binary) null else bytes.toString(Charsets.UTF_8),
            size = attrs.size,
            modified = attrs.modifyTime?.toMillis() ?: 0L,
            truncated = attrs.size > bytes.size,
        )
    }

    /**
     * Writes [text] back over SFTP unless the file changed since [expectedModified]. Writing
     * into the existing file keeps its owner and permissions.
     */
    suspend fun writeFile(path: String, text: String, expectedModified: Long, force: Boolean = false): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                val client = sftpClient()
                val current = runCatching { client.stat(path).modifyTime?.toMillis() ?: 0L }.getOrNull()
                if (!force && current != null && expectedModified != 0L && current != expectedModified) return@withContext SaveResult.Conflict
                client.write(path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                SaveResult.Saved
            } catch (e: org.apache.sshd.sftp.common.SftpException) {
                if (e.status == org.apache.sshd.sftp.common.SftpConstants.SSH_FX_PERMISSION_DENIED) SaveResult.PermissionDenied
                else SaveResult.Failed(e.message ?: "SFTP error ${e.status}")
            } catch (e: Exception) {
                SaveResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    /** Current modification time, to refresh the conflict check after saving. */
    suspend fun modifiedTime(path: String): Long = withContext(Dispatchers.IO) {
        runCatching { sftpClient().stat(path).modifyTime?.toMillis() ?: 0L }.getOrDefault(0L)
    }

    /** Uploads [text] to a private temp file and returns its path (for saving with sudo). */
    suspend fun writeTemp(text: String): String = withContext(Dispatchers.IO) {
        val client = sftpClient()
        val path = "/tmp/bastion-" + java.util.UUID.randomUUID().toString().take(12)
        // Create it empty and private (0600) first, so the content is never readable by others.
        client.write(path).use { }
        client.setStat(path, client.stat(path).perms(0b110_000_000))
        client.write(path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        path
    }

    /** Runs [command] on a separate exec channel of the same SSH connection; nothing reaches the shell. */
    suspend fun exec(command: String, maxBytes: Int = 8 * 1024 * 1024): String = execResult(command, null, maxBytes).first

    /** Like [exec], with optional [stdin] (closed after writing) and the exit status. */
    suspend fun execResult(command: String, stdin: String?, maxBytes: Int = 8 * 1024 * 1024): Pair<String, Int?> = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val channel = conn.session.createExecChannel(command)
        try {
            channel.open().verify(OPEN_TIMEOUT)
            channel.invertedIn.use { input -> if (stdin != null) input.write(stdin.toByteArray(Charsets.UTF_8)) }
            val out = java.io.ByteArrayOutputStream()
            val input = channel.invertedOut
            val buffer = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.EXIT_STATUS, org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), EXIT_WAIT)
            out.toString(Charsets.UTF_8.name()) to channel.exitStatus
        } finally {
            channel.close(false)
        }
    }

    /** sudo password for this session only: kept in memory, gone on disconnect. */
    @Volatile private var sudoPassword: String? = null

    /** True if sudo may work without asking: a password was given this session, or NOPASSWD. */
    suspend fun canSudoQuietly(): Boolean =
        sudoPassword != null || runCatching { execResult("sudo -n true", null).second == 0 }.getOrDefault(false)

    val hasSudoPassword: Boolean get() = sudoPassword != null

    fun forgetSudoPassword() {
        sudoPassword = null
    }

    /** Types the session's sudo password into a password prompt (not echoed, not recorded). */
    fun typeSudoPassword(): Boolean {
        val password = sudoPassword ?: return false
        send((password + "\r").encodeToByteArray())
        return true
    }

    /** The last command run in this session, to tell a sudo "Password:" prompt from others. */
    @Volatile var lastCommand: String? = null

    fun setSudoPassword(password: String) {
        sudoPassword = password
    }

    /**
     * Runs [command] as root without the terminal: `sudo -n` first (NOPASSWD rules, no
     * question), else `sudo -S` with the remembered password fed on stdin.
     */
    suspend fun execSudo(command: String): SudoResult {
        val inner = "sh -c " + shellQuote(command)
        val password = sudoPassword
        val (out, code) = if (password == null) {
            execResult("sh -c " + shellQuote("sudo -n -- $inner 2>&1"), null)
        } else {
            execResult("sh -c " + shellQuote("sudo -S -p '' -- $inner 2>&1"), password + "\n")
        }
        return when {
            password == null && SudoResult.needsPassword(out) -> SudoResult.NeedsPassword
            password != null && SudoResult.wrongPassword(out) -> {
                sudoPassword = null
                SudoResult.WrongPassword
            }
            code == 0 || code == null -> SudoResult.Ok(out)
            else -> SudoResult.Failed(out.trim(), code)
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
            CommandLine.capturePrompt(view, requireMarker = host.tmuxSession != null)?.let {
                prompt = it
                promptLines = listOf(view.lines.getOrElse(view.cursorRow - 1) { "" }, it.text)
            }
            typedSinceEnter = true
        }
        if (bytes.contains(CR)) {
            // The screen still shows the line as typed: the server hasn't echoed the newline yet.
            prompt?.let { p -> CommandLine.current(view, p)?.takeIf { it.isNotBlank() }?.let { lastCommand = it; onCommand(it) } }
            typedSinceEnter = false
        }
    }

    /** Text another screen wants opened in the terminal's input field; the terminal clears it. */
    val pendingEdit = MutableStateFlow<String?>(null)

    /** Types [text] at the cursor, with a separating space if needed. */
    fun insertAtCursor(text: String, trailingSpace: Boolean = true) {
        val current = currentCommand().orEmpty()
        val lead = if (current.isNotEmpty() && !current.endsWith(" ")) " " else ""
        send((lead + text + if (trailingSpace) " " else "").encodeToByteArray())
    }

    /** What's typed after the prompt right now, or null if unknown (full-screen app, no prompt yet). */
    fun currentCommand(): String? = screen.read()?.let { CommandLine.current(it, prompt) }

    /** Replace the shell's input line with [text], and run it when [run]. */
    fun replaceLine(text: String, run: Boolean) {
        if (shell == null) return
        send(CommandLine.replaceLine(text, run))
        if (run) {
            if (text.isNotBlank()) {
                lastCommand = text.trim()
                onCommand(text.trim())
            }
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

    private sealed interface Outcome {
        /** The shell (or tmux) ended normally, e.g. `exit` or a tmux detach. */
        data object Exited : Outcome
        /** The connection broke; worth reconnecting. */
        data class Lost(val reason: String) : Outcome
        /** Not worth retrying: login refused, prompt cancelled, broken setup. */
        data class Fatal(val reason: String) : Outcome
    }

    @Volatile private var stopReconnecting = false

    fun start() {
        if (job?.isActive == true) return
        stopReconnecting = false
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    private suspend fun CoroutineScope.runLoop() {
        var attempt = 0
        _status.value = SessionStatus.Connecting
        note("Connecting to ${host.address}…")
        while (isActive) {
            val outcome = connectOnce(reconnected = attempt > 0) { attempt = 0 }
            dropConnection()
            when (outcome) {
                Outcome.Exited -> return end(null)
                is Outcome.Fatal -> return end(outcome.reason)
                is Outcome.Lost -> {
                    if (!host.autoReconnect || stopReconnecting) return end(outcome.reason)
                    attempt++
                    if (attempt > MAX_ATTEMPTS) return end("Gave up reconnecting: ${outcome.reason}")
                    if (attempt == 1) note("Connection lost (${outcome.reason}). Reconnecting…")
                    _status.value = SessionStatus.Reconnecting(attempt, outcome.reason)
                    waitBeforeRetry(attempt)
                    if (stopReconnecting) return end(outcome.reason)
                }
            }
        }
    }

    /** One connection: connect, open the shell (or tmux), read until it ends. */
    private suspend fun CoroutineScope.connectOnce(reconnected: Boolean, onConnected: () -> Unit): Outcome {
        try {
            val conn = connector.connect(host).also { connection = it }
            val dims = emulator.dimensions
            val channel: PtyCapableChannelSession = host.tmuxSession?.let { name ->
                conn.session.createExecChannel("sh -c " + shellQuote(tmuxScript(name))).apply { setUsePty(true) }
            } ?: conn.session.createShellChannel()
            channel.ptyType = "xterm-256color"
            channel.ptyColumns = dims.columns
            channel.ptyLines = dims.rows
            channel.isRedirectErrorStream = true
            channel.open().verify(OPEN_TIMEOUT)
            shell = channel
            // The view may have resized while the channel was opening.
            emulator.dimensions.let { outgoing.trySend(Outgoing.Resize(it.columns, it.rows)) }
            _status.value = SessionStatus.Connected
            onConnected()
            if (reconnected) note(if (host.tmuxSession != null) "Reconnected, back in tmux." else "Reconnected (new shell).")
            val pump = launch { runCatching { pumpOutgoing(channel) } }
            try {
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
            } finally {
                pump.cancel()
            }
            // A shell that ended on its own sends an exit status; a dead connection doesn't.
            return if (channel.exitStatus != null || channel.exitSignal != null) {
                Log.i(TAG, "Session $id to ${host.label}: shell exited")
                Outcome.Exited
            } else {
                Outcome.Lost("connection closed")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SshFailure) {
            Log.w(TAG, "Session $id to ${host.label}: ${e.message}", e)
            return if (e.retryable) Outcome.Lost(e.message ?: "unreachable") else Outcome.Fatal(e.message ?: "failed")
        } catch (e: Exception) {
            Log.w(TAG, "Session $id to ${host.label} dropped", e)
            return Outcome.Lost(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Backoff (1, 2, 4 … 30 s), cut short by a network switch; never before the phone is online. */
    private suspend fun waitBeforeRetry(attempt: Int) {
        val seconds = minOf(30L, 1L shl minOf(attempt - 1, 5))
        val changesBefore = network.changes.value
        withTimeoutOrNull(seconds * 1000) { network.changes.first { it != changesBefore } }
        network.network.first { it != null }
    }

    /** After a network switch the connection may be dead without knowing it: check, and drop it if so. */
    fun checkAlive() {
        if (_status.value != SessionStatus.Connected) return
        scope.launch(Dispatchers.IO) {
            val alive = withTimeoutOrNull(6_000) { runCatching { execResult("true", null) }.isSuccess } == true
            if (!alive && _status.value == SessionStatus.Connected) {
                Log.i(TAG, "Session $id to ${host.label}: no answer after network change")
                connection?.close()
            }
        }
    }

    /** Stop trying to reconnect (the user gave up waiting). */
    fun stopReconnect() {
        stopReconnecting = true
        if (_status.value is SessionStatus.Reconnecting) {
            job?.cancel()
            end("Stopped reconnecting")
        }
    }

    private fun note(text: String) {
        runCatching { emulator.writeInput("\r\n\u001b[2m$text\u001b[0m\r\n".encodeToByteArray()) }
    }

        private suspend fun pumpOutgoing(channel: PtyCapableChannelSession) {
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

    /** Closes the current connection but keeps what a reconnect can reuse (sudo password, history). */
    private fun dropConnection() {
        shell = null
        tools = null
        runCatching { sftp?.close() }
        sftp = null
        runCatching { connection?.close() }
        connection = null
    }

    private fun end(error: String?) {
        if (_status.value is SessionStatus.Ended) return
        dropConnection()
        sudoPassword = null
        note(if (error == null) "Connection closed." else "Error: $error")
        _status.value = SessionStatus.Ended(error)
    }

    /** Reconnects into the same terminal, keeping the old output above. */
    fun reconnect() {
        if (_status.value !is SessionStatus.Ended) return
        while (outgoing.tryReceive().isSuccess) Unit
        start()
    }

    fun close() {
        stopReconnecting = true
        runCatching { sftp?.close() }
        sftp = null
        job?.cancel()
        schemeJob.cancel()
        shell = null
        connection?.close()
        connection = null
        outgoing.close()
        emulator.close()
    }

    internal companion object {
        const val CR: Byte = 0x0d
        const val LIST_CACHE_MS = 30_000L
        val EXIT_WAIT: Duration = Duration.ofSeconds(5)
        val OPEN_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val MAX_ATTEMPTS = 40

        /** Attach to (or create) the tmux session; without tmux, fall back to a login shell. */
        internal fun tmuxScript(name: String): String =
            "if command -v tmux >/dev/null 2>&1; then exec tmux new-session -A -s ${shellQuote(name)}; " +
                "else echo 'tmux is not installed here, starting a normal shell.'; exec \"\${SHELL:-/bin/sh}\" -l; fi"
    }
}
