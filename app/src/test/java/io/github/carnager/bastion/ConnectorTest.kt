package io.github.carnager.bastion

import io.github.carnager.bastion.data.AuthMethod
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.SshKey
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.ssh.Connector
import io.github.carnager.bastion.ssh.Prompt
import io.github.carnager.bastion.ssh.PromptAnswer
import io.github.carnager.bastion.ssh.Prompter
import io.github.carnager.bastion.ssh.SshFailure
import io.github.carnager.bastion.ssh.SshKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Real SSH against in-process MINA servers: a bastion that forwards, and a target behind it. */
class ConnectorTest {
    private class MemoryVault(initial: Vault) : VaultRepository {
        private val state = MutableStateFlow(initial)
        override val vault: StateFlow<Vault> = state
        @Synchronized override fun update(change: (Vault) -> Vault) { state.value = change(state.value) }
    }

    private lateinit var bastion: SshServer
    private lateinit var target: SshServer
    private val forwarded = Collections.synchronizedList(mutableListOf<String>())
    private val generated = SshKeys.generateEd25519("test")
    private val privateKey = generated.first
    private val publicKey = generated.second
    private val prompts = Collections.synchronizedList(mutableListOf<Prompt>())
    private val promptScope = CoroutineScope(Dispatchers.Default)
    private var answer: (Prompt) -> PromptAnswer = { PromptAnswer.Accepted }

    private fun server(configure: SshServer.() -> Unit) = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        configure()
        start()
    }

    @Before fun setUp() {
        bastion = server {
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pw, _ -> user == "jump" && pw == "secret" }
            forwardingFilter = object : AcceptAllForwardingFilter() {
                override fun canConnect(type: TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session): Boolean {
                    forwarded += "${address.hostName}:${address.port}"
                    return true
                }
            }
        }
        val authorized = SshKeys.parse("test", privateKey, "").first().public
        target = server {
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                user == "deep" && KeyUtils.compareKeys(key, authorized)
            }
        }
    }

    @After fun tearDown() {
        promptScope.cancel()
        bastion.stop(true)
        target.stop(true)
    }

    private fun vault(): Vault {
        val jump = Host(id = "jump", name = "jump", hostname = "127.0.0.1", port = bastion.port, username = "jump", password = "secret")
        val deep = Host(
            id = "deep", name = "deep", hostname = "127.0.0.1", port = target.port, username = "deep",
            auth = AuthMethod.KEY, keyId = "k", jumpHostId = "jump",
        )
        return Vault(hosts = listOf(jump, deep), keys = listOf(SshKey("k", "test", privateKey, publicKey = publicKey)))
    }

    private fun connector(store: VaultRepository): Connector {
        val prompter = Prompter()
        promptScope.launch {
            prompter.current.filterNotNull().collect { prompt ->
                prompts += prompt
                prompter.answer(answer(prompt))
            }
        }
        return Connector(store, prompter)
    }

    @Test fun connectsThroughJumpHostWithPerHopAuth() = runBlocking {
        val store = MemoryVault(vault())
        val connection = connector(store).connect(store.vault.value.host("deep")!!)
        try {
            assertTrue(connection.session.isAuthenticated)
            assertEquals(listOf("127.0.0.1:${target.port}"), forwarded.toList())
            // Both hops were trusted on first use and remembered under their saved names, not the tunnel port.
            assertEquals(
                setOf("127.0.0.1:${bastion.port}", "127.0.0.1:${target.port}"),
                store.vault.value.knownHosts.map { it.endpoint }.toSet(),
            )
            assertTrue(prompts.all { it is Prompt.HostKey && it.previous == null })
        } finally {
            connection.close()
        }
    }

    @Test fun knownHostsAreNotAskedAgain() = runBlocking {
        val store = MemoryVault(vault())
        val connector = connector(store)
        connector.connect(store.vault.value.host("deep")!!).close()
        val asked = prompts.size
        connector.connect(store.vault.value.host("deep")!!).close()
        assertEquals(asked, prompts.size)
    }

    @Test fun changedHostKeyIsReportedAndRejectionAborts() = runBlocking {
        val store = MemoryVault(vault())
        val connector = connector(store)
        connector.connect(store.vault.value.host("deep")!!).close()
        // Pretend the target's key used to be something else.
        store.update { v ->
            v.copy(knownHosts = v.knownHosts.map {
                if (it.endpoint.endsWith(":${target.port}")) it.copy(fingerprint = "SHA256:old") else it
            })
        }
        prompts.clear()
        answer = { PromptAnswer.Rejected }
        try {
            connector.connect(store.vault.value.host("deep")!!).close()
            fail("connected despite rejected host key")
        } catch (expected: SshFailure) {
        }
        val prompt = prompts.single() as Prompt.HostKey
        assertEquals("SHA256:old", prompt.previous)
        assertEquals("127.0.0.1:${target.port}", prompt.endpoint)
    }

    @Test fun failuresSayWhetherRetryingMakesSense() = runBlocking {
        val store = MemoryVault(vault())
        // Wrong password: retrying would only hammer the server.
        store.update { v -> v.copy(hosts = v.hosts.map { if (it.id == "jump") it.copy(password = "nope") else it }) }
        val login = runCatching { connector(store).connect(store.vault.value.host("jump")!!) }.exceptionOrNull() as SshFailure
        assertTrue(!login.retryable)
        // Nothing listening: the server may come back, so it's worth retrying.
        val gone = Host(id = "gone", name = "gone", hostname = "127.0.0.1", port = 1, username = "x", password = "y")
        store.update { v -> v.copy(hosts = v.hosts + gone) }
        val unreachable = runCatching { connector(store).connect(gone) }.exceptionOrNull() as SshFailure
        assertTrue(unreachable.retryable)
    }

    @Test fun wrongJumpPasswordNamesTheJumpHost() = runBlocking {
        val store = MemoryVault(vault())
        store.update { v -> v.copy(hosts = v.hosts.map { if (it.id == "jump") it.copy(password = "nope") else it }) }
        try {
            connector(store).connect(store.vault.value.host("deep")!!).close()
            fail("connected with wrong password")
        } catch (e: SshFailure) {
            assertTrue(e.message, e.message!!.startsWith("Login to jump failed"))
        }
        assertNull(forwarded.firstOrNull())
    }
}
