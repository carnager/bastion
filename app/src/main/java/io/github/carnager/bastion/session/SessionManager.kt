package io.github.carnager.bastion.session

import android.util.Log
import io.github.carnager.bastion.data.ColorSchemes
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.ssh.Connector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SessionManager(
    private val store: VaultRepository,
    private val settings: Settings,
    private val connector: Connector,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()
    private var nextId = 1L

    fun open(host: Host): TerminalSession {
        store.update { vault ->
            vault.copy(hosts = vault.hosts.map { if (it.id == host.id) it.copy(lastUsed = System.currentTimeMillis()) else it })
        }
        // The host's own scheme, else the global one; follows edits to either while the session is open.
        val scheme = combine(settings.schemeId, store.vault.map { it.host(host.id)?.colorScheme }) { global, own ->
            ColorSchemes.orDefault(own ?: global)
        }.stateIn(scope, SharingStarted.Eagerly, ColorSchemes.orDefault(host.colorScheme ?: settings.schemeId.value))
        val session = TerminalSession(nextId++, host, connector, scope, scheme, onCommand = { command ->
            if (settings.suggestions.value) {
                scope.launch(Dispatchers.IO) {
                    store.update { v ->
                        v.copy(history = v.history + (host.id to CommandLine.remember(v.history[host.id].orEmpty(), command)))
                    }
                }
            }
        })
        _sessions.update { it + session }
        session.start()
        return session
    }

    fun get(id: Long): TerminalSession? = _sessions.value.firstOrNull { it.id == id }

    fun close(id: Long) {
        val session = get(id) ?: return
        Log.i(TAG, "Closing session $id to ${session.host.label}", Throwable("close() called from"))
        _sessions.update { list -> list.filterNot { it.id == id } }
        session.close()
    }
}

const val TAG = "Bastion"
