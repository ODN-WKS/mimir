package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LFileNote
import io.github.odnwks.mimir.standalone.Attach
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.Saved
import io.github.odnwks.mimir.standalone.Store
import io.github.odnwks.mimir.standalone.Turn
import io.github.odnwks.mimir.standalone.TurnFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** D1: with a PC that answers, any file goes to the PC to be read; the phone shows how much was read. */
@RunWith(RobolectricTestRunner::class)
class D1FilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pc = FakePc()
    private val pdf = "%PDF-1.4 pretend".toByteArray()

    private fun engine(ready: Boolean = false): Engine = testEngine(tmp.newFolder(), ready = ready).also { it.pc = pc }

    private fun settle(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(e.state.value.writing)
    }

    @Test fun aPdfIsSentToThePcWithTheMessageAndTheChatKeepsOnlyThatItWasAttached() {
        pc.takesFiles = true
        pc.answer = { r, l ->
            l.filed(Filed.Talk("${r.turnId}.a", "pc", "Qwen 3.6 · Your PC", null, listOf(LFileNote("Lease.pdf", "Read pages 1 to 40 of 120"))), null)
            l.done(io.github.odnwks.mimir.link.LChatTurn("${r.turnId}.a", false, "It is a lease.", ran = "pc", at = 1), null)
        }
        val e = engine(ready = false)
        assertTrue(e.pcFiles())
        val file = (Attach.forPc("Lease.pdf", "application/pdf", pdf) as Attach.Result.Read).file
        assertTrue(e.send("What is this about?", files = listOf(file)))
        settle(e)
        val asked = pc.sent.single().turns.single { it.id == pc.sent.single().turnId }
        assertEquals(1, asked.files.size)
        assertEquals("Lease.pdf", asked.files[0].name)
        assertEquals("application/pdf", asked.files[0].mediaType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(pdf), asked.files[0].base64)
        // What the chat keeps, and what the PC keeps of the message, has no words of the file.
        assertEquals("What is this about?\n\n(File \"Lease.pdf\" was attached.)", asked.text)
        // The phone shows what the PC said it read, and lets the bytes go.
        val shown = e.state.value.chat.turns.first { it.user }.files.single()
        assertEquals("Read pages 1 to 40 of 120", shown.note)
        assertNull(shown.bytes)
        assertEquals("What is this about?\n\n(File \"Lease.pdf\" was attached.)", e.state.value.chat.turns.first { it.user }.forModel())
        // A later message sends no file again.
        pc.answer = { r, l -> l.done(io.github.odnwks.mimir.link.LChatTurn("${r.turnId}.a", false, "Page 3.", ran = "pc", at = 2), null) }
        e.send("And page 3?"); settle(e)
        assertTrue(pc.sent.last().turns.all { it.files.isEmpty() })
    }

    @Test fun anOlderPcIsNeverSentFilesAndAFileIsNotSentWhenThePcCannotTakeThem() {
        pc.takesFiles = false
        val e = engine(ready = true)
        assertFalse(e.pcFiles())
        e.send("List", files = listOf(TurnFile("list.md", "Eggs")))
        settle(e)
        assertTrue(pc.sent.single().turns.all { it.files.isEmpty() })
        assertTrue(pc.sent.single().turns.last().text.contains("File \"list.md\":\nEggs"))
    }

    @Test fun withAPcAnyFileIsAcceptedUpTo10MbAndWithoutOneThePhoneSaysWhatIsMissing() {
        for ((name, mime) in listOf("a.pdf" to "application/pdf", "b.docx" to null, "c.xlsx" to null, "d.zip" to "application/zip", "e.m4a" to "audio/mp4", "f.epub" to "application/epub+zip")) {
            val r = Attach.forPc(name, mime, byteArrayOf(1, 2, 3)) as Attach.Result.Read
            assertEquals(name, r.file.name)
            assertTrue(r.file.onPc)
        }
        assertEquals(Attach.Result.No(Attach.Problem.TooBig), Attach.forPc("big.pdf", "application/pdf", ByteArray(Attach.MAX_PC_BYTES + 1)))
        assertEquals(Attach.Result.No(Attach.Problem.Picture), Attach.forPc("a.jpg", "image/jpeg", byteArrayOf(1)))
        assertEquals(Attach.Result.No(Attach.Problem.Unreadable), Attach.forPc("empty.txt", "text/plain", ByteArray(0)))
        // No PC: today's rule, with a next step.
        assertEquals(Attach.Result.No(Attach.Problem.NotText), Attach.read("a.pdf", "application/pdf", "%PDF".toByteArray(), whole = true))
        val words = attachProblem(Attach.Problem.NotText)
        assertTrue(words, words.contains("Connect your PC to read PDFs"))
        assertTrue(attachProblem(Attach.Problem.TooBig).contains("10 MB"))
    }

    @Test fun aWaitingMessageKeepsItsFilesUntilThePcIsBackButASentOneNeverStoresTheirBytes() {
        val dir = tmp.newFolder()
        val store = Store(File(dir, "s.bin"), object : Store.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray) = String(bytes)
        })
        val waiting = Turn(true, "Read this", listOf(TurnFile("Lease.pdf", "", bytes = pdf, mime = "application/pdf")), id = "q1")
        val sent = Turn(true, "Read that", listOf(TurnFile("Old.pdf", "", bytes = pdf, mime = "application/pdf"), TurnFile("Done.pdf", "", note = "Read all of it")), id = "s1")
        store.save(Saved(Catalog.default.id, listOf(LocalChat("c", Catalog.default.id, listOf(sent), queued = listOf(waiting)))))
        val back = store.load().chats.single()
        assertTrue(back.queued.single().files.single().bytes.contentEquals(pdf))
        assertEquals("application/pdf", back.queued.single().files.single().mime)
        assertNull(back.turns.single().files[0].bytes)
        assertEquals("Read all of it", back.turns.single().files[1].note)
        assertTrue(back.turns.single().files[1].onPc)
    }
}
