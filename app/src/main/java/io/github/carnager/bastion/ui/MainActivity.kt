package io.github.carnager.bastion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import io.github.carnager.bastion.App

sealed interface Screen {
    data object Hosts : Screen
    data class EditHost(val id: String?) : Screen
    data object Keys : Screen
    data class Terminal(val sessionId: Long) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val app = application as App
        setContent { BastionTheme { Root(app) } }
    }
}

@Composable
private fun Root(app: App) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Hosts) }
    val vault by app.store.vault.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val prompt by app.prompter.current.collectAsState()

    fun push(screen: Screen) = stack.add(screen)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Hosts -> HostListScreen(
            vault = vault,
            sessions = sessions,
            onConnect = { host -> push(Screen.Terminal(app.sessions.open(host).id)) },
            onOpenSession = { push(Screen.Terminal(it)) },
            onCloseSession = app.sessions::close,
            onEdit = { push(Screen.EditHost(it)) },
            onDelete = { id -> app.store.update { v -> v.copy(hosts = v.hosts.filterNot { it.id == id }) } },
            onKeys = { push(Screen.Keys) },
        )
        is Screen.EditHost -> HostEditScreen(
            vault = vault,
            hostId = screen.id,
            onSave = { host ->
                app.store.update { v ->
                    v.copy(hosts = if (v.hosts.any { it.id == host.id }) v.hosts.map { if (it.id == host.id) host else it } else v.hosts + host)
                }
                pop()
            },
            onManageKeys = { push(Screen.Keys) },
            onBack = ::pop,
        )
        Screen.Keys -> KeysScreen(vault = vault, store = app.store, onBack = ::pop)
        is Screen.Terminal -> {
            val session = sessions.firstOrNull { it.id == screen.sessionId }
            if (session == null) {
                LaunchedEffect(screen) { pop() }
            } else {
                TerminalScreen(
                    session = session,
                    sessions = sessions,
                    onSwitch = { id -> stack[stack.lastIndex] = Screen.Terminal(id) },
                    onClose = {
                        val remaining = sessions.filterNot { it.id == session.id }
                        app.sessions.close(session.id)
                        if (remaining.isEmpty()) pop() else stack[stack.lastIndex] = Screen.Terminal(remaining.last().id)
                    },
                    onBack = ::pop,
                )
            }
        }
    }

    prompt?.let { PromptDialog(it, app.prompter::answer) }
}
