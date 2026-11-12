package io.github.carnager.bastion.data

import kotlinx.serialization.Serializable

@Serializable
enum class AuthMethod { PASSWORD, KEY }

@Serializable
data class Host(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: AuthMethod = AuthMethod.PASSWORD,
    /** Empty means: ask on connect. */
    val password: String = "",
    val keyId: String? = null,
    /** Saved host to tunnel through (OpenSSH ProxyJump). That host may itself have a jump host. */
    val jumpHostId: String? = null,
    val lastUsed: Long = 0,
) {
    val label: String get() = name.ifBlank { hostname }
    val address: String get() = if (port == 22) "$username@$hostname" else "$username@$hostname:$port"
}

@Serializable
data class SshKey(
    val id: String,
    val name: String,
    /** OpenSSH or PEM private key text. */
    val privateKey: String,
    val passphrase: String = "",
    /** OpenSSH public key line, shown for copying to authorized_keys. */
    val publicKey: String = "",
)

@Serializable
data class KnownHost(
    /** "host:port" as the user saved it, not the address it was reached through. */
    val endpoint: String,
    val keyType: String,
    val fingerprint: String,
)

@Serializable
data class Vault(
    val hosts: List<Host> = emptyList(),
    val keys: List<SshKey> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
) {
    fun host(id: String?): Host? = id?.let { wanted -> hosts.firstOrNull { it.id == wanted } }
    fun key(id: String?): SshKey? = id?.let { wanted -> keys.firstOrNull { it.id == wanted } }
}
