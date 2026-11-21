package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHD-02 and PHD-03: a message waits until the PC answers, and a call never waits for ever. */
class DeliveryTest {
    /** A stand-in for the link: calls get ids; [kill] is the link dropping. */
    private class FakeLink {
        val book = CallBook()
        var next = 1L
        var up = true
        val sent = ArrayList<String>()
        fun call(json: String, cb: (Any?, String?) -> Unit): Boolean {
            if (!up) return false
            sent += json
            book.register(next++, cb)
            return true
        }
        fun pcAnswers(id: Long, ok: Any?, err: String? = null) = book.answered(id)?.invoke(ok, err)
        fun kill() { up = false; book.failAll() }
    }

    @Test fun aMessageStaysQueuedWhenTheLinkDiesBeforeTheAnswer() {
        val link = FakeLink()
        val delivery = Delivery()
        val outbox = mutableListOf("m1" to "hello")
        fun flush() {
            for ((id, text) in outbox.toList()) delivery.start(id, { cb -> link.call("chat:$id:$text", cb) }) { _, _ -> outbox.removeAll { it.first == id } }
        }
        flush()
        assertEquals(1, link.sent.size)
        // Handed to the link, but the PC never answered: the link is killed mid-send.
        link.kill()
        assertEquals(listOf("m1" to "hello"), outbox)
        // The link comes back and the message goes out again; now the PC says Ok.
        link.up = true
        flush()
        assertEquals(2, link.sent.size)
        link.pcAnswers(2, true)
        assertTrue(outbox.isEmpty())
    }

    @Test fun anItemAlreadyOnItsWayIsNotSentTwice() {
        val link = FakeLink()
        val delivery = Delivery()
        assertEquals(true, delivery.start("a", { cb -> link.call("a", cb) }) { _, _ -> })
        assertNull(delivery.start("a", { cb -> link.call("a", cb) }) { _, _ -> })
        assertEquals(1, link.sent.size)
    }

    @Test fun aLinkThatRefusesLeavesTheItemFreeToTryAgain() {
        val link = FakeLink().apply { up = false }
        val delivery = Delivery()
        assertEquals(false, delivery.start("a", { cb -> link.call("a", cb) }) { _, _ -> })
        link.up = true
        assertEquals(true, delivery.start("a", { cb -> link.call("a", cb) }) { _, _ -> })
    }

    @Test fun anErrorFromThePcEndsTheWait() {
        val link = FakeLink()
        val delivery = Delivery()
        var told: String? = null
        delivery.start("a", { cb -> link.call("a", cb) }) { _, err -> told = err }
        link.pcAnswers(1, null, "No such task")
        assertEquals("No such task", told)
    }

    @Test fun everyPendingCallIsToldWhenTheLinkGoes() {
        val link = FakeLink()
        val told = ArrayList<String?>()
        repeat(3) { link.call("c$it") { _, err -> told += err } }
        assertEquals(3, link.book.size())
        link.kill()
        assertEquals(listOf("Your PC went away, try again.", WENT_AWAY, WENT_AWAY).take(3).map { it }, told)
        assertEquals(0, link.book.size())
        assertFalse(link.up)
    }
}
