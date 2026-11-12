package io.github.carnager.bastion.ssh

import io.github.carnager.bastion.data.AuthMethod
import io.github.carnager.bastion.data.ChainResult
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.KnownHost
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.data.resolveChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.io.Closeable
import java.security.PublicKey
import java.time.Duration

/** [retryable]: worth trying again later (network); false for refused logins, cancelled prompts, bad setup. */
class SshFailure(message: String, cause: Throwable? = null, val retryable: Boolean = true) : Exception(message, cause)

/**
 * An authenticated session to the target plus the jump sessions it is tunnelled through.
 * Closing it closes the whole chain, target first.
 */
class SshConnection(val session: ClientSession, private val jumps: List<ClientSession>) : Closeable {
    override fun close() {
        runCatching { session.close(true) }
        jumps.asReversed().forEach { runCatching { it.close(true) } }
    }
}

class Connector(private val store: VaultRepository, private val prompter: Prompter) {
    private val client: SshClient = SshClient.setUpDefaultClient().apply {
        // Nothing from a desktop ~/.ssh: every host, key and known host comes from the vault.
        hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
        keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        serverKeyVerifier = VaultHostKeyVerifier()
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        // Keep-alives that expect an answer: phones lose connections silently (network switch,
        // NAT timeout), and only an unanswered request reveals that. 3 misses = connection dead.
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(this, Duration.ofSeconds(15))
        CoreModuleProperties.HEARTBEAT_REQUEST.set(this, "keepalive@openssh.com")
        CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(this, Duration.ofSeconds(10))
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(this, 3)
        // Send keystrokes immediately (like OpenSSH for interactive sessions) instead of
        // letting Nagle hold small packets back until the previous one is acknowledged.
        CoreModuleProperties.TCP_NODELAY.set(this, true)
        start()
    }

    suspend fun connect(target: Host): SshConnection = withContext(Dispatchers.IO) {
        val vault = store.vault.value
        val hops = when (val chain = resolveChain(target, vault)) {
            is ChainResult.Ok -> chain.hops
            is ChainResult.Error -> throw SshFailure(chain.message, retryable = false)
        }
        val open = mutableListOf<ClientSession>()
        try {
            var via: SshdSocketAddress? = null
            for ((index, hop) in hops.withIndex()) {
                val session = openSession(hop, via)
                open += session
                if (index < hops.lastIndex) {
                    val next = hops[index + 1]
                    // Loopback listener tunnelled through this hop, like `ssh -J`. The next hop's SSH
                    // runs end to end through it, so the jump host only ever sees encrypted bytes.
                    val tracker = session.createLocalPortForwardingTracker(
                        SshdSocketAddress(SshdSocketAddress.LOCALHOST_IPV4, 0),
                        SshdSocketAddress(next.hostname, next.port),
                    )
                    via = tracker.boundAddress
                }
            }
            SshConnection(open.last(), open.dropLast(1))
        } catch (e: Exception) {
            open.asReversed().forEach { runCatching { it.close(true) } }
            throw if (e is SshFailure) e else SshFailure(describe(e), e)
        }
    }

    private fun openSession(hop: Host, via: SshdSocketAddress?): ClientSession {
        val context = AttributeRepository.ofKeyValuePair(HOP, hop)
        val future = if (via == null) {
            client.connect(hop.username, hop.hostname, hop.port, context)
        } else {
            client.connect(hop.username, via.hostName, via.port, context)
        }
        val session = try {
            future.verify(CONNECT_TIMEOUT).session
        } catch (e: Exception) {
            val where = if (via == null) "" else " (through jump host)"
            throw SshFailure("Could not reach ${hop.label}$where: ${describe(e)}", e)
        }
        try {
            addIdentities(session, hop)
            session.auth().verify(AUTH_TIMEOUT)
        } catch (e: Exception) {
            session.close(true)
            if (e is SshFailure) throw e
            throw SshFailure("Login to ${hop.label} failed: ${describe(e)}", e, retryable = false)
        }
        return session
    }

    private fun addIdentities(session: ClientSession, hop: Host) {
        when (hop.auth) {
            AuthMethod.KEY -> {
                val key = store.vault.value.key(hop.keyId)
                    ?: throw SshFailure("${hop.label} has no key selected.", retryable = false)
                SshKeys.parse(key.name, key.privateKey, key.passphrase).forEach(session::addPublicKeyIdentity)
            }
            AuthMethod.PASSWORD -> {
                val password = hop.password.ifEmpty { askPassword(hop) }
                session.addPasswordIdentity(password)
                // Servers that only offer keyboard-interactive get the same password.
                session.userInteraction = PasswordInteraction(password)
            }
        }
    }

    private fun askPassword(hop: Host): String =
        when (val answer = prompter.askBlocking(Prompt.Password(hop.label, hop.address))) {
            is PromptAnswer.Text -> {
                if (answer.remember) {
                    store.update { vault ->
                        vault.copy(hosts = vault.hosts.map { if (it.id == hop.id) it.copy(password = answer.value) else it })
                    }
                }
                answer.value
            }
            else -> throw SshFailure("Login to ${hop.label} cancelled.", retryable = false)
        }

    /** Checks host keys against the vault, keyed by the saved host name rather than the tunnel address. */
    private inner class VaultHostKeyVerifier : ServerKeyVerifier {
        override fun verifyServerKey(session: ClientSession, remote: java.net.SocketAddress, key: PublicKey): Boolean {
            val hop = session.connectionContext?.getAttribute(HOP) ?: return false
            val endpoint = "${hop.hostname}:${hop.port}"
            val keyType = KeyUtils.getKeyType(key)
            val fingerprint = KeyUtils.getFingerPrint(key)
            val known = store.vault.value.knownHosts.firstOrNull { it.endpoint == endpoint }
            if (known != null && known.keyType == keyType && known.fingerprint == fingerprint) return true
            val answer = prompter.askBlocking(Prompt.HostKey(endpoint, keyType, fingerprint, known?.fingerprint))
            if (answer != PromptAnswer.Accepted) return false
            store.update { vault ->
                vault.copy(knownHosts = vault.knownHosts.filterNot { it.endpoint == endpoint } +
                    KnownHost(endpoint, keyType, fingerprint))
            }
            return true
        }
    }

    private companion object {
        val HOP = AttributeRepository.AttributeKey<Host>()
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val AUTH_TIMEOUT: Duration = Duration.ofSeconds(60)

        fun describe(e: Throwable): String {
            var cause: Throwable = e
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        }
    }
}

private class PasswordInteraction(private val password: String) : org.apache.sshd.client.auth.keyboard.UserInteraction {
    override fun interactive(
        session: ClientSession,
        name: String?,
        instruction: String?,
        lang: String?,
        prompt: Array<out String>,
        echo: BooleanArray,
    ): Array<String> = Array(prompt.size) { i -> if (echo[i]) "" else password }

    override fun getUpdatedPassword(session: ClientSession, prompt: String?, lang: String?): String? = null
}
