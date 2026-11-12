package io.github.carnager.bastion

import android.app.Application
import io.github.carnager.bastion.data.Settings
import io.github.carnager.bastion.data.VaultStore
import io.github.carnager.bastion.session.SessionManager
import io.github.carnager.bastion.session.SessionService
import io.github.carnager.bastion.ssh.Connector
import io.github.carnager.bastion.ssh.Prompter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.apache.sshd.common.util.io.PathUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    lateinit var store: VaultStore
        private set
    val prompter = Prompter()
    lateinit var sessions: SessionManager
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; MINA needs the full one.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        // MINA resolves ~ for defaults it never gets to read here; give it somewhere real.
        PathUtils.setUserHomeFolderResolver { filesDir.toPath() }

        store = VaultStore(this)
        settings = Settings(this)
        sessions = SessionManager(store, settings, Connector(store, prompter))
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            sessions.sessions.map { it.size }.distinctUntilChanged().collect { SessionService.update(this@App, it) }
        }
    }
}
