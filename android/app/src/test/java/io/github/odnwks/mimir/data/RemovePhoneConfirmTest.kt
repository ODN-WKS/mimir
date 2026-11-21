package io.github.odnwks.mimir.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHU-20: removing a phone must show a confirmation dialog, not proceed silently. */
class RemovePhoneConfirmTest {

    private val phones = listOf(
        Phone(id = "p1", name = "Sam's Galaxy S8", note = "", thisPhone = false),
        Phone(id = "p2", name = "Office tablet", note = "", thisPhone = false),
    )

    @Test
    fun confirmation_dialog_shown_before_remove() {
        val dialog = confirmRemovePhone("p1", phones)
        assertNotNull("must return a confirmation dialog", dialog)
    }

    @Test
    fun dialog_message_includes_phone_name() {
        val dialog = confirmRemovePhone("p1", phones)!!
        assertTrue(
            "message must include the phone name, was: ${dialog.message}",
            dialog.message.contains("Sam's Galaxy S8"),
        )
    }

    @Test
    fun dialog_message_warns_irreversible() {
        val dialog = confirmRemovePhone("p1", phones)!!
        assertTrue(
            "message must warn this can't be undone, was: ${dialog.message}",
            dialog.message.contains("can't be undone", ignoreCase = true),
        )
    }

    @Test
    fun no_dialog_for_unknown_phone_id() {
        assertNull(confirmRemovePhone("unknown-id", phones))
    }
}
