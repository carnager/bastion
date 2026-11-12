package io.github.carnager.bastion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        icon = { Icon(if (changed) Ico.alert else Ico.shield, null, tint = if (changed) extra.red else extra.link) },
        title = { Text(if (changed) "Host key changed" else "Trust this host?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(prompt.endpoint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (changed) {
                    Text(
                        "The server’s key is not the one saved earlier. If you didn’t reinstall or rekey this server, someone may be intercepting the connection.",
                        fontSize = 14.sp, color = extra.red,
                    )
                } else {
                    Text("First connection. Check that the fingerprint matches the server’s.", fontSize = 14.sp, color = extra.muted)
                }
                InfoCard(radius = 12.dp) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(prompt.keyType, fontSize = 12.sp, color = extra.muted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                        Text(prompt.fingerprint, style = MonoStyle, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
                    }
                }
                if (changed) Text("Was: ${prompt.previous}", style = MonoStyle, fontSize = 11.sp, color = extra.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(PromptAnswer.Accepted) }) {
                Text(if (changed) "Replace key" else "Trust", color = if (changed) extra.red else extra.link)
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(prompt.address, fontSize = 13.sp, color = extra.muted)
                TextInput(password, { password = it }, placeholder = "Password", secret = true, keyboard = KeyboardType.Password)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { remember = !remember },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        remember, { remember = it }, Modifier.size(20.dp),
                        colors = CheckboxDefaults.colors(checkedColor = extra.accent, uncheckedColor = extra.muted, checkmarkColor = Color.White),
                    )
                    Text("Save password", fontSize = 14.sp, color = extra.fg2)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(PromptAnswer.Text(password, remember)) }, enabled = password.isNotEmpty()) { Text("Log in") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(PromptAnswer.Rejected) }) { Text("Cancel") } },
    )
}
