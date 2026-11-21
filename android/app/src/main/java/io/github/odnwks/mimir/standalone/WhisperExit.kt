package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.audio.ReadAloud

/** The phone's clipboard, as far as the emergency exit needs it (checks put a stand-in here). */
interface ClipboardWipe {
    /** The text on the clipboard now, or null when there is none. */
    fun text(): String?
    fun clear()
}

/**
 * The emergency exit (a long press on the eye in a Whisper): the Whisper is wiped, read aloud and
 * any answer being written stop, Tor stops, and the clipboard is cleared if its text came from
 * this Whisper. The caller then closes the app and removes it from Recents.
 */
object WhisperExit {
    /** Whether [clip] holds words from this Whisper: all of it or a piece, of what was said by either side. */
    fun clipCameFrom(chat: LocalChat, clip: String?): Boolean {
        val c = clip?.trim().orEmpty()
        return c.isNotEmpty() && chat.turns.any { it.text.contains(c) || (c.length > 8 && c.contains(it.text.trim()) && it.text.isNotBlank()) }
    }

    /** Returns false (and does nothing) when the chat isn't a Whisper. */
    fun run(engine: Engine, reader: ReadAloud?, clip: ClipboardWipe): Boolean {
        val s = engine.state.value
        if (!s.whisper) return false
        // Looked at before the chat is gone.
        val mine = clipCameFrom(s.chat, runCatching { clip.text() }.getOrNull())
        runCatching { reader?.stop() }
        engine.endWhisper()
        // Tor stops with a Whisper that used it; this makes sure even when the flag was lost.
        runCatching { io.github.odnwks.mimir.online.TorLink.stop() }
        if (mine) runCatching { clip.clear() }
        return true
    }
}
