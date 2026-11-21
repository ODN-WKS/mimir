package io.github.odnwks.mimir.online

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Locks and unlocks bytes. On the phone the key lives in the Android Keystore; checks swap in a plain one. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that never leaves the Android Keystore. The stored bytes are the 12-byte nonce, then the locked words. */
class KeystoreSealer(private val alias: String = "mimir_online_keys") : Sealer {
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** One online service the owner set up. [key] is the secret: it is never printed, logged or sent anywhere but [base]. */
class Setup(
    /** "openrouter", "groq" or "own". */
    val id: String,
    val name: String,
    val base: String,
    val key: String,
    /** The model that answers (null: none picked yet). */
    val model: String? = null,
    /** "Best free one right now": the app picks, and picks again if it stops being free. */
    val auto: Boolean = true,
) {
    fun copy(model: String? = this.model, auto: Boolean = this.auto, key: String = this.key) = Setup(id, name, base, key, model, auto)
    override fun toString() = "Setup($id, ${KeyVault.mask(key)})"
}

/**
 * The pasted keys, locked with [sealer] and kept in one file in the app's own storage (which is
 * left out of every backup). Never logged, never sent to the PC, never shown in full again.
 */
class KeyVault(private val file: File, private val sealer: Sealer) {
    /** The service ids kept next to the locked file (not secret): they say which keys need setting up again if the file can't be opened. */
    private val idsFile = File(file.parentFile, file.name + ".ids")

    /** What was read: the keys, or null when the file is there but can't be opened (the Keystore or the lock failed). */
    private fun readOrNull(): List<Setup>? {
        if (!file.isFile) return emptyList()
        return runCatching {
            val a = JSONArray(String(sealer.open(file.readBytes()), Charsets.UTF_8))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Setup(o.getString("id"), o.getString("name"), o.getString("base"), o.getString("key"), o.optString("model").ifEmpty { null }, o.optBoolean("auto", true))
            }
        }.getOrNull()
    }

    private fun read(): List<Setup> = readOrNull() ?: emptyList()

    /** The file is there but can't be opened right now. It is kept as it is, and never overwritten silently. */
    @Synchronized fun unreadable(): Boolean = readOrNull() == null

    /** The services whose keys need setting up again (empty when all is well). */
    @Synchronized fun needSetupAgain(): Set<String> =
        if (readOrNull() != null) emptySet() else runCatching { idsFile.readText().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    private fun write(all: List<Setup>) {
        if (file.isFile && readOrNull() == null) {
            // Not readable: it is put aside (kept), not overwritten, so one failure never wipes the other keys.
            val aside = File(file.parentFile, file.name + ".unreadable")
            aside.delete(); file.renameTo(aside)
        }
        if (all.isEmpty()) { file.delete(); idsFile.delete(); return }
        idsFile.writeText(all.joinToString(",") { it.id })
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.base).put("key", it.key).put("model", it.model ?: "").put("auto", it.auto)) }
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.writeBytes(sealer.seal(a.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized fun all(): List<Setup> = read()
    @Synchronized fun get(id: String): Setup? = read().firstOrNull { it.id == id }
    @Synchronized fun put(s: Setup) = write(read().filter { it.id != s.id } + s)
    @Synchronized fun remove(id: String) = write(read().filter { it.id != id })

    companion object {
        /** The key as shown after it is pasted: the first 3 and the last 4 characters, the rest hidden. */
        fun mask(key: String): String = if (key.length <= 12) "•".repeat(key.length.coerceAtLeast(4)) else key.take(3) + "••••••" + key.takeLast(4)
    }
}
