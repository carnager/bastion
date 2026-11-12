package io.github.carnager.bastion.session

import androidx.compose.ui.graphics.Color
import io.github.carnager.bastion.data.Host
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
) {
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Connecting)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var connection: SshConnection? = null
    @Volatile private var shell: ChannelShell? = null
    private var job: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        defaultForeground = Color(0xFFE6E6E6),
        defaultBackground = Color(0xFF101010),
        onKeyboardInput = { bytes -> if (shell != null) outgoing.trySend(bytes) },
        onResize = { size -> shell?.let { runCatching { it.sendWindowChange(size.columns, size.rows) } } },
        autoDetectUrls = true,
    )

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
                _status.value = SessionStatus.Connected
                launch { pumpOutgoing(channel) }
                val input = channel.invertedOut
                val buffer = ByteArray(16 * 1024)
                while (isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) emulator.writeInput(buffer, 0, read)
                }
                finish(null)
            } catch (e: Exception) {
                finish(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun pumpOutgoing(channel: ChannelShell) {
        val out = channel.invertedIn
        for (bytes in outgoing) {
            out.write(bytes)
            out.flush()
        }
    }

    /** Text from the extra-keys row, snippets or the clipboard, sent as if typed. */
    fun send(bytes: ByteArray) {
        if (shell != null) outgoing.trySend(bytes)
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
        shell = null
        connection?.close()
        connection = null
        outgoing.close()
        emulator.close()
    }

    private companion object {
        val OPEN_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
