package io.github.carnager.bastion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Read and change the vault. [VaultStore] on the phone, an in-memory one in tests. */
interface VaultRepository {
    val vault: StateFlow<Vault>
    fun update(change: (Vault) -> Vault)
}

/**
 * Hosts, keys and known host keys in one JSON document, encrypted with an AES key that never
 * leaves the Android Keystore. Writes go to a temp file first, so a crash can't truncate the vault.
 */
class VaultStore(context: Context) : VaultRepository {
    private val file = File(context.filesDir, "vault.bin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _vault = MutableStateFlow(load())
    override val vault: StateFlow<Vault> = _vault.asStateFlow()

    @Synchronized
    override fun update(change: (Vault) -> Vault) {
        val next = change(_vault.value)
        if (next == _vault.value) return
        save(next)
        _vault.value = next
    }

    private fun load(): Vault {
        if (!file.exists()) return Vault()
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        val plain = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        return json.decodeFromString(Vault.serializer(), plain.decodeToString())
    }

    private fun save(vault: Vault) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.encodeToString(Vault.serializer(), vault).encodeToByteArray())
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(cipher.iv)
            out.write(encrypted)
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
