package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Files on a message to the model on this phone: text is read in, pictures and other files aren't. */
@RunWith(RobolectricTestRunner::class)
class AttachTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aTextFileIsReadIn() {
        val r = Attach.read("list.md", "text/markdown", "﻿Eggs\r\nMilk\r\n".toByteArray(), whole = true)
        assertEquals(Attach.Result.Read(TurnFile("list.md", "Eggs\nMilk", cut = false)), r)
        // A file the phone doesn't know the type of is read when it's text.
        assertTrue(Attach.read("notes", "application/octet-stream", "plain words".toByteArray(), whole = true) is Attach.Result.Read)
    }

    @Test fun picturesAndOtherFilesAreTurnedAwayWithTheReason() {
        assertEquals(Attach.Result.No(Attach.Problem.Picture), Attach.read("a.jpg", "image/jpeg", byteArrayOf(1, 2), whole = true))
        assertEquals(Attach.Result.No(Attach.Problem.Picture), Attach.read("a.mp4", "video/mp4", byteArrayOf(1, 2), whole = true))
        assertEquals(Attach.Result.No(Attach.Problem.NotText), Attach.read("a.pdf", "application/pdf", "%PDF".toByteArray(), whole = true))
        assertEquals(Attach.Result.No(Attach.Problem.NotText), Attach.read("a.bin", null, byteArrayOf(0, 1, -1, -2, -61), whole = true))
    }

    @Test fun onlyTheStartOfALongFileIsKeptAndItSaysSo() {
        val long = "é".repeat(Attach.MAX_CHARS + 10).toByteArray()
        val r = Attach.read("long.txt", "text/plain", long, whole = true) as Attach.Result.Read
        assertEquals(Attach.MAX_CHARS, r.file.text.length)
        assertTrue(r.file.cut)
        // Cut in the middle of a letter, the rest is still read.
        val half = Attach.read("cut.txt", "text/plain", "abcé".toByteArray().copyOf(4), whole = false) as Attach.Result.Read
        assertEquals("abc", half.file.text)
        assertTrue(half.file.cut)
    }

    @Test fun theModelReadsTheFileAfterTheMessageAndTheChatKeepsIt() {
        val turn = Turn(true, "Sum it up", listOf(TurnFile("list.md", "Eggs\nMilk"), TurnFile("big.txt", "Start", cut = true)))
        val p = Prompt.build(Format.ChatMl, listOf(turn))
        assertTrue(p.contains("Sum it up\n\nFile \"list.md\":\nEggs\nMilk\n\nFile \"big.txt\" (only its start):\nStart<|im_end|>"))
        val store = Store(File(tmp.newFolder(), "s.bin"), object : Store.Codec {
            override fun seal(text: String) = text.toByteArray()
            override fun open(bytes: ByteArray) = String(bytes)
        })
        store.save(Saved(Catalog.default.id, listOf(LocalChat("c", Catalog.default.id, listOf(turn, Turn(false, "Eggs and milk."), Turn(true, "", listOf(TurnFile("x.txt", "y"))))))))
        val back = store.load().chats.single()
        assertEquals(turn.copy(id = "c.0"), back.turns[0])
        assertEquals(false to "Eggs and milk.", back.turns[1].let { it.user to it.text })
        assertEquals("x.txt", back.turns[2].files.single().name)
        // A first message that is only a file takes its name as the topic.
        assertEquals("x.txt", LocalChat("d", "m", listOf(Turn(true, "", listOf(TurnFile("x.txt", "y"))))).topic)
        assertFalse(Prompt.build(Format.Gemma4, listOf(Turn(true, "", listOf(TurnFile("x.txt", "y"))))).contains("\n\nFile"))
    }
}
