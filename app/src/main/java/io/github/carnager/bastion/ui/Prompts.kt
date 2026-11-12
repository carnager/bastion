package io.github.carnager.bastion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.carnager.bastion.ssh.Prompt
import io.github.carnager.bastion.ssh.PromptAnswer

@Composable
fun PromptDialog(prompt: Prompt, onAnswer: (PromptAnswer) -> Unit) {
    when (prompt) {
        is Prompt.HostKey -> HostKeyDialog(prompt, onAnswer)
        is Prompt.Password -> PasswordDialog(prompt, onAnswer)
    }
}

@Composable
private fun HostKeyDialog(prompt: Prompt.HostKey, onAnswer: (PromptAnswer) -> Unit) {
    val changed = prompt.previous != null
    AlertDialog(
        onDismissRequest = { onAnswer(PromptAnswer.Rejected) },
        title = { Text(if (changed) "Host key changed!" else "New host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.endpoint, style = MaterialTheme.typography.titleSmall)
                if (changed) {
                    Text(
                        "The key differs from the one saved earlier. This can mean someone is intercepting the connection.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("Was: ${prompt.previous}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(prompt.keyType, style = MaterialTheme.typography.labelMedium)
                Text(prompt.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(PromptAnswer.Accepted) }) { Text(if (changed) "Replace key" else "Trust") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(PromptAnswer.Rejected) }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(prompt: Prompt.Password, onAnswer: (PromptAnswer) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(PromptAnswer.Rejected) },
        title = { Text(prompt.hostLabel) },
        text = {
            Column {
                Text(prompt.address, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Save password")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(PromptAnswer.Text(password, remember)) }) { Text("Log in") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(PromptAnswer.Rejected) }) { Text("Cancel") } },
    )
}
