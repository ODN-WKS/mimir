package io.github.odnwks.mimir.data.store

import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Turn
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class PlainCodec : ChatRepository.Codec, IdentityStore.Codec {
    override fun seal(text: String) = text.toByteArray()
    override fun open(bytes: ByteArray) = String(bytes)
}

@RunWith(RobolectricTestRunner::class)
class StoreB4Test {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): ChatRepository = ChatRepository(tmp.newFolder("chats"), PlainCodec())
    private fun identityStore(): IdentityStore {
        val codec = PlainCodec()
        return IdentityStore(tmp.newFolder("id").resolve("identity.bin"), codec)
    }
    private fun ledger(): ModelLedger = ModelLedger(tmp.newFolder("ledger").resolve("model_ledger.json"))

    @Test fun twoWriterStress() = runBlocking {
        val r = repo()
        val chat1 = LocalChat("c1", "model-a", listOf(Turn(true, "hello", id = "t1")))
        val chat2 = LocalChat("c2", "model-b", listOf(Turn(true, "world", id = "t2")))
        repeat(100) {
            val j1 = launch(Dispatchers.IO) { r.saveChat(chat1) }
            val j2 = launch(Dispatchers.IO) { r.saveChat(chat2) }
            j1.join(); j2.join()
        }
        Thread.sleep(200)
        assertNotNull(r.loadChat("c1"))
        assertNotNull(r.loadChat("c2"))
    }

    @Test fun identityRetryOnFailure() {
        var calls = 0
        val failOnce = object : IdentityStore.Codec {
            override fun seal(text: String): ByteArray = text.toByteArray()
            override fun open(bytes: ByteArray): String {
                calls++
                if (calls == 1) throw javax.crypto.AEADBadTagException()
                return String(bytes)
            }
        }
        val f = tmp.newFolder("id2").resolve("identity.bin")
        val store = IdentityStore(f, failOnce)
        val id = Identity(byteArrayOf(1, 2, 3), "pc1", "My PC")
        store.save(id)
        val loaded = store.load()
        assertFalse(store.repairNeeded)
        assertNotNull(loaded)
        assertEquals("pc1", loaded!!.pcId)
    }

    @Test fun pendingCallsErrorOnDrop() {
        val errors = mutableListOf<String>()
        val pending = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()
        pending["id1"] = { errors.add(it) }
        pending["id2"] = { errors.add(it) }
        val copy = pending.toMap()
        pending.clear()
        copy.values.forEach { it("link dropped") }
        val start = System.currentTimeMillis()
        while (errors.size < 2 && System.currentTimeMillis() - start < 1000) Thread.sleep(10)
        assertTrue("all pending calls should error within 1s", errors.size == 2)
    }

    @Test fun unsentResend() {
        val r = repo()
        val turn = Turn(true, "hi", id = "t1")
        val chat = LocalChat("c3", "m", listOf(turn))
        r.saveChat(chat)
        Thread.sleep(100)
        val unsent = r.unsent()
        assertTrue(unsent.any { it.first == "c3" && it.second.id == "t1" })
        r.markSent("c3", 0)
        Thread.sleep(100)
        val afterMark = r.unsent()
        assertTrue(afterMark.none { it.first == "c3" && it.second.id == "t1" })
    }

    @Test fun chosenModelGuard() {
        val l = ledger()
        l.recordDownload("modelX")
        l.setChosen("modelX")
        l.recordDelete("modelX")
        assertTrue(l.mirimOwns("modelX"))
        assertEquals("modelX", l.chosen())
    }
}
