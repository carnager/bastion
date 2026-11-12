package io.github.carnager.bastion.session

import android.util.Log
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.ssh.Connector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SessionManager(private val store: VaultRepository, private val connector: Connector) {
    private val scope = CoroutineScope(SupervisorJob())
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()
    private var nextId = 1L

    fun open(host: Host): TerminalSession {
        store.update { vault ->
            vault.copy(hosts = vault.hosts.map { if (it.id == host.id) it.copy(lastUsed = System.currentTimeMillis()) else it })
        }
        val session = TerminalSession(nextId++, host, connector, scope)
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
