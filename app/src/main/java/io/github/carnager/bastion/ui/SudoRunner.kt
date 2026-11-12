package io.github.carnager.bastion.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.SudoResult
import io.github.carnager.bastion.session.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs commands as root on the hidden channel, asking for the sudo password natively when
 * needed and showing failures. Use [run] from screens; put [Dialogs] in their composition.
 */
@Stable
class SudoRunner(private val session: TerminalSession, private val scope: CoroutineScope) {
    /** What's running right now, for a status line ("Restarting…"). */
    var busy by mutableStateOf<String?>(null)
        private set
    private var ask by mutableStateOf<Pair<Boolean, () -> Unit>?>(null) // (wrong before, retry)
    private var failure by mutableStateOf<String?>(null)

    /** Runs [command]; [onOk] gets its output. Password prompts and errors are handled here. */
    fun run(label: String, command: String, onOk: (String) -> Unit) {
        scope.launch {
            busy = label
            val result = try {
                session.execSudo(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SudoResult.Failed(e.message ?: e.javaClass.simpleName, -1)
            }
            busy = null
            when (result) {
                is SudoResult.Ok -> onOk(result.output)
                SudoResult.NeedsPassword -> ask = false to { run(label, command, onOk) }
                SudoResult.WrongPassword -> ask = true to { run(label, command, onOk) }
                is SudoResult.Failed -> failure = result.output.ifBlank { "Exit status ${result.code}" }
            }
        }
    }

    @Composable
    fun Dialogs() {
        ask?.let { (wrong, retry) ->
            SudoPasswordDialog(
                host = session.host.label,
                wrong = wrong,
                onSubmit = { password ->
                    ask = null
                    session.setSudoPassword(password)
                    retry()
                },
                onDismiss = { ask = null },
            )
        }
        failure?.let { output ->
            AlertDialog(
                onDismissRequest = { failure = null },
                title = { Text("That didn’t work") },
                text = { Text(output.take(1500), style = MonoStyle, fontSize = 12.5.sp) },
                confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
            )
        }
    }
}

@Composable
fun rememberSudoRunner(session: TerminalSession): SudoRunner {
    val scope = rememberCoroutineScope()
    return remember(session) { SudoRunner(session, scope) }
}
