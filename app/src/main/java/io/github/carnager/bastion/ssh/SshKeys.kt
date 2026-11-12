package io.github.carnager.bastion.ssh

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.PublicKey

object SshKeys {
    /** Parses an OpenSSH/PEM private key. Throws with a readable message if that fails. */
    fun parse(name: String, text: String, passphrase: String): List<KeyPair> {
        val pairs = try {
            SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName(name.ifBlank { "key" }),
                text.trim().byteInputStream(),
                if (passphrase.isEmpty()) FilePasswordProvider.EMPTY else FilePasswordProvider.of(passphrase),
            )?.toList().orEmpty()
        } catch (e: Exception) {
            val encrypted = "ENCRYPTED" in text || "aes" in text.lowercase()
            throw IllegalArgumentException(
                if (passphrase.isEmpty() && encrypted) "Key is encrypted: enter its passphrase."
                else "Could not read key: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        require(pairs.isNotEmpty()) { "No private key found in that text." }
        return pairs
    }

    fun publicKeyLine(key: PublicKey, comment: String): String =
        PublicKeyEntry.toString(key) + if (comment.isBlank()) "" else " $comment"

    fun fingerprint(key: PublicKey): String = KeyUtils.getFingerPrint(key)

    /** A fresh Ed25519 key pair as (OpenSSH private key text, public key line). */
    fun generateEd25519(comment: String): Pair<String, String> {
        val generator = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA)
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out)
        return out.toString(Charsets.UTF_8.name()) to publicKeyLine(pair.public, comment)
    }
}
