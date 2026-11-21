package io.github.odnwks.mimir.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's files open however big they are (the S8's Keystore couldn't open what it sealed above about
 * 70 KB, so a chat file past that was unreadable after the next start; found on the phone, 2026-11-20).
 */
class EnvelopeTest {
    /** A stand-in Keystore that, like the S8's, can't take more than 60 KB at once. */
    private class SmallKeystore : KeyWrap {
        private val mask = 0x5a.toByte()
        override fun seal(plain: ByteArray): ByteArray { require(plain.size <= 60 * 1024) { "the Keystore can't take ${plain.size} bytes" }; return ByteArray(12) + plain.map { (it.toInt() xor mask.toInt()).toByte() } }
        override fun open(sealed: ByteArray): ByteArray = sealed.drop(12).map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
    }

    private val env = Envelope(SmallKeystore())

    @Test fun aBigFileSealsAndOpensAgain() {
        for (kb in listOf(1, 64, 100, 300, 2000)) {
            val text = (0 until kb * 1024).joinToString("") { ('a' + it % 26).toString() }
            assertEquals("$kb KB", text, env.open(env.seal(text)))
        }
    }

    @Test fun aFileSavedTheEarlierWayStillOpens() {
        val old = SmallKeystore().seal("the old chats".toByteArray())
        assertEquals("the old chats", env.open(old))
    }

    @Test fun everySaveUsesAKeyAndStartOfItsOwn() {
        val a = env.seal("same words")
        val b = env.seal("same words")
        assertNotEquals(a.toList(), b.toList())
        assertFalse(String(a).contains("same words"))
    }

    @Test fun aChangedFileIsRefusedNotOpenedWrong() {
        val bytes = env.seal("my chats")
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        val r = runCatching { env.open(bytes) }
        assertTrue(r.isFailure)
    }
}
