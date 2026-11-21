package io.github.odnwks.mimir.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Seals and opens SMALL byte strings with a key that never leaves the phone's Keystore. */
interface KeyWrap {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a Keystore key usable while the phone is locked (so the link and a reply can work then). */
class KeystoreWrap(private val alias: String) : KeyWrap {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * The phone's files (chats, pairing), however big. The Keystore on some phones (the Galaxy S8, found
 * on 2026-11-20) cannot open what it sealed once that is more than about 70 KB: the file was saved,
 * and then could never be read. So the text is sealed with a fresh random key of its own (in the app, any size), and
 * only that small key is sealed in the Keystore and kept at the file's start. A file saved the earlier way
 * (the Keystore alone, from before) is still opened.
 */
class Envelope(private val wrap: KeyWrap, private val random: SecureRandom = SecureRandom()) {
    fun seal(text: String): ByteArray {
        val dataKey = ByteArray(32).also(random::nextBytes)
        val wrapped = wrap.seal(dataKey)
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, iv))
        val body = c.doFinal(text.toByteArray())
        return MAGIC + byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()) + wrapped + iv + body
    }

    fun open(bytes: ByteArray): String {
        if (bytes.size < MAGIC.size + 2 + 12 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return String(wrap.open(bytes))
        val n = ((bytes[MAGIC.size].toInt() and 0xff) shl 8) or (bytes[MAGIC.size + 1].toInt() and 0xff)
        val from = MAGIC.size + 2
        require(from + n + 12 < bytes.size) { "file too short" }
        val dataKey = wrap.open(bytes.copyOfRange(from, from + n))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, bytes, from + n, 12))
        return String(c.doFinal(bytes, from + n + 12, bytes.size - from - n - 12))
    }

    private companion object {
        /** "MSV2": the start of a file sealed with its own key (the earlier files start with random bytes). */
        val MAGIC = byteArrayOf(0x4d, 0x53, 0x56, 0x32)
    }
}
