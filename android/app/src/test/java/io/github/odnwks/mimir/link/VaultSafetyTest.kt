package io.github.odnwks.mimir.link

import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** PHD-01 and PHE-01: the phone's files are never replaced by a fresh one because of a hiccup, and never torn by two saves. */
@RunWith(RobolectricTestRunner::class)
class VaultSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain(var failOpen: Boolean = false, var failSeal: Boolean = false) : Vault.Codec {
        override fun seal(text: String): ByteArray { if (failSeal) error("keystore"); return text.toByteArray() }
        override fun open(bytes: ByteArray): String { if (failOpen) error("keystore"); return String(bytes) }
    }

    private fun vault(f: File, c: Vault.Codec, key: Byte = 7) = Vault(f, c, { ByteArray(32) { key } }, tries = 3, pause = {})

    @Test fun aMissingFileMakesOneFreshIdentity() {
        val f = File(tmp.root, "link.vault")
        val v = vault(f, Plain())
        val d = v.load()
        assertFalse(v.unreadable)
        assertTrue(f.exists())
        assertEquals(32, d.key.size)
    }

    private class Damaged : Vault.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray): String = throw javax.crypto.AEADBadTagException("tag mismatch")
    }

    private fun aside(dir: File, name: String) = dir.list()!!.filter { it.startsWith("$name.bad") }.sorted()

    @Test fun aDamagedPairingFileIsKeptAsideWholeAndNeverReplaced() {
        val f = File(tmp.root, "link.vault")
        val first = vault(f, Plain(), key = 1)
        first.save(first.load().copy(pcId = "pc-1", pcName = "My PC", outbox = listOf("m1" to "hello")))
        val before = f.readBytes()
        val v = vault(f, Damaged(), key = 2)
        val d = v.load()
        assertTrue(v.unreadable)
        assertEquals(null, d.pcId)
        assertTrue(v.keptAs!!.exists())
        assertArrayEquals(before, v.keptAs!!.readBytes())
        // A second damaged file does not replace the first: both are kept.
        val again = vault(f, Damaged(), key = 3)
        again.load()
        assertEquals(2, aside(tmp.root, "link.vault").size)
        assertArrayEquals(before, File(tmp.root, aside(tmp.root, "link.vault").first()).readBytes())
    }

    @Test fun aBusyKeystoreLeavesThePairingFileWhereItIsAndRefusesToSaveOverIt() {
        val f = File(tmp.root, "link.vault")
        val first = vault(f, Plain(), key = 1)
        first.save(first.load().copy(pcId = "pc-1"))
        val before = f.readBytes()
        val v = vault(f, Plain(failOpen = true), key = 2)
        val d = v.load()
        assertTrue(v.unreadable); assertTrue(v.readOnly)
        assertEquals(null, d.pcId)
        assertTrue("nothing moved", aside(tmp.root, "link.vault").isEmpty())
        v.save(d.copy(pcId = "someone"))
        assertFalse(v.lastSaveOk)
        assertArrayEquals("the original is untouched", before, f.readBytes())
    }

    @Test fun aReadThatWorksOnTheSecondTryIsNotCalledUnreadable() {
        val f = File(tmp.root, "link.vault")
        val v0 = vault(f, Plain(), key = 1)
        v0.save(v0.load().copy(pcId = "pc-1"))
        var calls = 0
        val flaky = object : Vault.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray): String { if (calls++ == 0) error("busy"); return String(bytes) }
        }
        val v = vault(f, flaky)
        assertEquals("pc-1", v.load().pcId)
        assertFalse(v.unreadable)
        assertTrue(aside(tmp.root, "link.vault").isEmpty())
    }

    @Test fun badJsonDoesNotCrash() {
        val f = File(tmp.root, "link.vault")
        f.writeText("{not json")
        val v = vault(f, Plain())
        val d = v.load()
        assertTrue(v.unreadable)
        assertNotNull(d)
        assertEquals("{not json", File(tmp.root, aside(tmp.root, "link.vault").single()).readText())
    }

    @Test fun aFailedVaultSaveIsRememberedAndRetried() {
        val f = File(tmp.root, "link.vault")
        val c = Plain()
        val v = vault(f, c)
        val d = v.load()
        c.failSeal = true
        v.save(d.copy(pcId = "x"))
        assertFalse(v.lastSaveOk)
        c.failSeal = false
        v.save(d.copy(pcId = "x"))
        assertTrue(v.lastSaveOk)
        assertEquals("x", vault(f, Plain()).load().pcId)
    }

    @Test fun twoSavesNeverRunAtTheSameTime() {
        val f = File(tmp.root, "standalone.bin")
        val inside = java.util.concurrent.atomic.AtomicInteger(); val most = java.util.concurrent.atomic.AtomicInteger()
        val store = Store(f, object : Store.Codec {
            override fun seal(text: String): ByteArray {
                most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(5)
                inside.decrementAndGet()
                return text.toByteArray()
            }
            override fun open(bytes: ByteArray) = String(bytes)
        })
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (0 until 4).map { t -> pool.submit { repeat(10) { store.save(Saved("m", listOf(LocalChat("c$t", "m", listOf(Turn(true, "t$t")))))) } } }
        jobs.forEach { it.get() }
        pool.shutdown()
        assertEquals("one writer at a time", 1, most.get())
        assertFalse(store.load().chats.isEmpty())
        assertEquals(emptyList<String>(), f.parentFile!!.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test fun aChatFileThatCouldNotBeReadIsNeverSavedOver() {
        val f = File(tmp.root, "standalone.bin")
        f.writeText("{\"chats\":[]}")
        val busy = Store(f, object : Store.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray): String = error("keystore busy")
        })
        busy.load()
        assertTrue(busy.unreadable && busy.readOnly)
        val r = runCatching { busy.save(Saved("m", emptyList())) }
        assertTrue(r.isFailure)
        assertEquals("{\"chats\":[]}", f.readText())
    }

    @Test fun anUnreadableChatFileIsKeptAsBad() {
        val f = File(tmp.root, "standalone.bin")
        f.writeText("garbage")
        val store = Store(f, object : Store.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray) = String(bytes)
        })
        assertEquals(0, store.load().chats.size)
        assertTrue(store.unreadable)
        assertEquals("garbage", File(tmp.root, aside(tmp.root, "standalone.bin").single()).readText())
        // A second unreadable file is kept beside it, not over it.
        f.writeText("more garbage")
        store.load()
        assertEquals(2, aside(tmp.root, "standalone.bin").size)
    }

    @Test fun halfWrittenTempFilesLeftByAKillAreSweptWhenTheFileIsLoaded() {
        val f = File(tmp.root, "standalone.bin")
        val store = Store(f, object : Store.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray) = String(bytes)
        })
        store.save(Saved("m", emptyList()))
        File(tmp.root, "standalone.bin.ab12cd34.tmp").writeText("half")
        File(tmp.root, "link.vault.99999999.tmp").writeText("other file's, not touched")
        store.load()
        assertEquals(listOf("link.vault.99999999.tmp", "standalone.bin"), tmp.root.list()!!.sorted())
    }
}
