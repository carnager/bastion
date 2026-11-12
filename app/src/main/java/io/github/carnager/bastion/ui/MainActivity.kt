package io.github.carnager.bastion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.carnager.bastion.App

sealed interface Screen {
    data object Hosts : Screen
    /** [templateId]: duplicate that host into a new one. */
    data class EditHost(val id: String?, val templateId: String? = null) : Screen
    data object Keys : Screen
    data class Terminal(val sessionId: Long) : Screen
    data object Settings : Screen

    /** Screens that slide up like a sheet rather than in from the side. */
    val isSheet: Boolean get() = this is EditHost
}

class MainActivity : FragmentActivity() {
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val app = app
        setContent { BastionTheme { Root(app) } }
    }

    override fun onStart() {
        super.onStart()
        app.lock.onForeground()
        // Keep terminal contents out of the recent-apps preview while the lock is on.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!app.settings.lockEnabled.value)
    }

    override fun onStop() {
        super.onStop()
        app.lock.onBackground()
    }
}

@Composable
private fun Root(app: App) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Hosts)) }
    val vault by app.store.vault.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val prompt by app.prompter.current.collectAsState()
    val globalScheme by app.settings.schemeId.collectAsState()
    val locked by app.lock.locked.collectAsState()
    val fontSize by app.settings.fontSize.collectAsState()
    val suggestionsEnabled by app.settings.suggestions.collectAsState()

    if (locked) {
        Box(Modifier.fillMaxSize().background(extra.bg)) { LockScreen(onUnlocked = app.lock::unlocked) }
        return
    }

    fun push(screen: Screen) { stack = stack + screen }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replace(screen: Screen) { stack = stack.dropLast(1) + screen }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(extra.bg)) {
        AnimatedContent(
            targetState = stack,
            label = "nav",
            transitionSpec = {
                val forward = targetState.size >= initialState.size
                val sheet = (if (forward) targetState.last() else initialState.last()).isSheet
                when {
                    sheet && forward -> slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                    sheet -> fadeIn(tween(200)) togetherWith slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180))
                    forward -> slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(220)) togetherWith
                        slideOutHorizontally(tween(260)) { -it / 8 } + fadeOut(tween(180))
                    else -> slideInHorizontally(tween(260)) { -it / 8 } + fadeIn(tween(220)) togetherWith
                        slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(180))
                }
            },
            // Switching terminal tabs swaps content in place instead of animating a new screen.
            contentKey = { st -> st.map { if (it is Screen.Terminal) "terminal" else it.toString() } },
        ) { st ->
            when (val screen = st.last()) {
                is Screen.Terminal -> {
                    val session = sessions.firstOrNull { it.id == screen.sessionId }
                    if (session == null) {
                        LaunchedEffect(screen) { if (stack.lastOrNull() == screen) pop() }
                    } else {
                        TerminalScreen(
                            session = session,
                            sessions = sessions,
                            onSwitch = { id -> replace(Screen.Terminal(id)) },
                            onClose = {
                                val remaining = sessions.filterNot { it.id == session.id }
                                app.sessions.close(session.id)
                                if (remaining.isEmpty()) pop() else replace(Screen.Terminal(remaining.last().id))
                            },
                            onBack = ::pop,
                            globalSchemeId = globalScheme,
                            hostSchemeId = vault.host(session.host.id)?.colorScheme,
                            onSetGlobalScheme = app.settings::setSchemeId,
                            fontSize = fontSize,
                            onFontSize = app.settings::setFontSize,
                            history = vault.history[session.host.id].orEmpty(),
                            suggestionsEnabled = suggestionsEnabled,
                            onSetHostScheme = { id ->
                                app.store.update { v ->
                                    v.copy(hosts = v.hosts.map { if (it.id == session.host.id) it.copy(colorScheme = id) else it })
                                }
                            },
                        )
                    }
                }
                else -> Box(Modifier.fillMaxSize().background(extra.bg).safeDrawingPadding()) {
                    when (screen) {
                        Screen.Hosts -> HostListScreen(
                            vault = vault,
                            sessions = sessions,
                            onConnect = { host -> push(Screen.Terminal(app.sessions.open(host).id)) },
                            onOpenSession = { push(Screen.Terminal(it)) },
                            onCloseSession = app.sessions::close,
                            onEdit = { push(Screen.EditHost(it)) },
                            onDuplicate = { push(Screen.EditHost(null, templateId = it.id)) },
                            onDelete = { id -> deleteHost(app, id) },
                            onKeys = { push(Screen.Keys) },
                            onSettings = { push(Screen.Settings) },
                        )
                        is Screen.EditHost -> HostEditScreen(
                            vault = vault,
                            store = app.store,
                            hostId = screen.id,
                            template = vault.host(screen.templateId),
                            globalSchemeId = globalScheme,
                            onSave = { host ->
                                app.store.update { v ->
                                    v.copy(
                                        hosts = if (v.hosts.any { it.id == host.id }) v.hosts.map { if (it.id == host.id) host else it }
                                        else v.hosts + host,
                                    )
                                }
                                pop()
                            },
                            onDelete = { id -> deleteHost(app, id); pop() },
                            onBack = ::pop,
                        )
                        Screen.Keys -> KeysScreen(vault = vault, store = app.store, onBack = ::pop)
                        Screen.Settings -> SettingsScreen(settings = app.settings, onBack = ::pop)
                        is Screen.Terminal -> Unit
                    }
                }
            }
        }
        SnackHost()
    }

    prompt?.let { PromptDialog(it, app.prompter::answer) }
}

private fun deleteHost(app: App, id: String) {
    app.store.update { v ->
        // Hosts behind it keep the dangling jump host and refuse to connect, rather than silently going direct.
        v.copy(hosts = v.hosts.filterNot { it.id == id })
    }
}
