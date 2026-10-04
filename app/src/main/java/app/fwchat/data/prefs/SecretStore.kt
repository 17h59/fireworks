package app.fwchat.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage d'un secret unique (la clé API). Implémentations: Keystore (prod), mémoire (tests). */
interface SecretStore {
    /** null = rien de stocké, ou secret illisible (clé Keystore perdue). */
    suspend fun read(): String?
    /** null = effacer. */
    suspend fun write(secret: String?)
}

/**
 * Chiffre le secret en AES-256/GCM avec une clé Android Keystore non exportable (alias dédié).
 * Le blob chiffré (IV + texte chiffré, base64) est stocké dans [file] (ex. filesDir/secrets/api_key.bin).
 * Le secret n'est jamais journalisé.
 */
class KeystoreSecretStore(
    private val file: File,
    private val alias: String = DEFAULT_ALIAS,
) : SecretStore {

    private val lock = Mutex()

    override suspend fun read(): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) return@withContext null
                val blob = Base64.decode(file.readText().trim(), Base64.NO_WRAP)
                if (blob.size <= IV_SIZE) return@withContext null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE, existingKey() ?: return@withContext null,
                    GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE),
                )
                String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
            } catch (e: Exception) {
                // Clé Keystore perdue/invalidée, blob corrompu ou restauré d'un autre appareil: considéré absent.
                null
            }
        }
    }

    override suspend fun write(secret: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (secret == null) {
                file.delete()
                deleteKey()
                return@withContext
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + encrypted
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(Base64.encodeToString(blob, Base64.NO_WRAP))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: Exception) {
            // Rien à supprimer ou Keystore indisponible.
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "fwchat_api_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
