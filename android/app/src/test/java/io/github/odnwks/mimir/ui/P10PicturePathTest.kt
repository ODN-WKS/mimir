package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class P10PicturePathTest {
    private val p = "/home/deck/.local/share/mimir/task-files/1795472443-2-2/6-picture-1.jpg"

    @Test fun onePictureLineIsDropped() {
        val (words, names) = AttachedNames.split("hello\n\nAttached: $p\n[1 picture attached]")
        assertEquals("hello", words)
        assertEquals(listOf("picture-1.jpg"), names)
    }

    @Test fun threePicturesLineIsDropped() {
        val t = "hi\n\nAttached: /a/1-picture-1.jpg, /a/2-picture-2.jpg, /a/3-picture-3.jpg\n[3 pictures attached]"
        val (words, names) = AttachedNames.split(t)
        assertEquals("hi", words)
        assertEquals(listOf("picture-1.jpg", "picture-2.jpg", "picture-3.jpg"), names)
    }

    @Test fun picturesAndFiles() {
        val (words, names) = AttachedNames.split("hi\n\nAttached: /a/1-plan.pdf, /a/2-picture-1.jpg\n[1 picture attached]")
        assertEquals("hi", words)
        assertEquals(listOf("plan.pdf", "picture-1.jpg"), names)
    }
}
