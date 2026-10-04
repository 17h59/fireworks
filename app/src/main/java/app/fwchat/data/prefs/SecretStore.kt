package app.fwchat.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage d'un secret unique (la clé API). Implémentations: Keystore (prod), mémoire (tests). */
interface SecretStore {
    /**
     * null = rien de stocké.
     * @throws UnreadableSecretException un secret est stocké mais définitivement illisible (clé Keystore perdue
     * ou invalidée, blob corrompu ou restauré d'un autre appareil). Les erreurs transitoires (E/S) sont propagées telles quelles.
     */
    suspend fun read(): String?
    /** null = effacer. */
    suspend fun write(secret: String?)
}

/** Un secret est présent mais ne pourra plus jamais être déchiffré: il doit être effacé. */
class UnreadableSecretException(cause: Throwable? = null) : Exception("Secret illisible", cause)

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
            if (!file.isFile) return@withContext null
            val text = file.readText().trim()
            try {
                val blob = Base64.decode(text, Base64.NO_WRAP)
                if (blob.size <= IV_SIZE) throw UnreadableSecretException()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE, existingKey() ?: throw UnreadableSecretException(),
                    GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE),
                )
                String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
            } catch (e: UnreadableSecretException) {
                throw e
            } catch (e: KeyStoreException) {
                // Keystore momentanément indisponible: transitoire, on ne détruit rien.
                throw e
            } catch (e: GeneralSecurityException) {
                // AEADBadTag, clé invalidée/irrécupérable, blob restauré d'un autre appareil: définitif.
                throw UnreadableSecretException(e)
            } catch (e: IllegalArgumentException) {
                // Base64 invalide: blob corrompu.
                throw UnreadableSecretException(e)
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
