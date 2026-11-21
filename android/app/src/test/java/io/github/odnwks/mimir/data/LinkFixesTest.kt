package io.github.odnwks.mimir.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/** The link hears about network changes, and the words found wrong on the S8. */
@RunWith(RobolectricTestRunner::class)
class LinkFixesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_link_hears_when_the_phones_network_comes_and_goes() {
        val heard = ArrayList<String>()
        assertTrue(Stations.watchNetwork(context) { heard += it })
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(cm).networkCallbacks
        assertTrue("no network callback was registered", callbacks.isNotEmpty())
        val wifi = ShadowNetwork.newInstance(1)
        val mobile = ShadowNetwork.newInstance(2)
        callbacks.forEach { it.onAvailable(wifi) }
        callbacks.forEach { it.onLost(wifi) }
        callbacks.forEach { it.onAvailable(mobile) }
        assertEquals(listOf("network available", "network lost", "network available"), heard)
    }

    @Test
    fun counts_say_one_and_many_right() {
        assertEquals("1 task", count(1, "task"))
        assertEquals("0 tasks", count(0, "task"))
        assertEquals("1 step", count(1, "step"))
        assertEquals("3 lines", count(3, "line"))
    }

    @Test
    fun a_command_asks_to_allow_the_command_not_an_edit() {
        assertEquals("Allow this edit", Permission("i", "Edit a.txt", "a.txt", 2).allowWords)
        assertEquals("Allow this command", Permission("i", "Run a command", "", 0, PermissionKind.Command, "ls").allowWords)
        assertEquals("Allow", Permission("i", "Use a tool", "", 0, PermissionKind.Other).allowWords)
    }

    @Test
    fun a_stopped_task_isnt_shown_as_done() {
        assertEquals(Chip.Stopped, Chip.of(busy = false, status = "Stopped"))
        assertEquals(Chip.Done, Chip.of(busy = false, status = "Done"))
        assertEquals(Chip.Running, Chip.of(busy = true, status = "Stopped"))
    }
}
