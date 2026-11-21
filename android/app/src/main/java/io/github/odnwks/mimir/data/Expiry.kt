package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkTask
import java.io.File

/** How long the phone keeps what it holds only for a moment (PHD-09, PHD-10, PHD-11). */
object Expiry {
    const val SPEAK_DAYS = 7L
    const val RECORDING_HOURS = 24L
    private const val HOUR = 3_600_000L
    const val UNLISTED_MINUTES = 10L

    /** Read-aloud audio of task messages ("speak-*.ogg") older than [SPEAK_DAYS] days goes; returns how many. */
    fun speakFiles(dir: File?, now: Long): Int {
        val old = dir?.listFiles()?.filter { it.name.startsWith("speak-") && now - it.lastModified() > SPEAK_DAYS * 24 * HOUR } ?: return 0
        return old.count { it.delete() }
    }

    /**
     * Recordings waiting for the PC for more than [RECORDING_HOURS] hours go (the sound files, and any
     * file the list no longer names). Returns the ids that were in the list and are gone.
     */
    fun recordings(dir: File?, listed: List<String>, now: Long): Set<String> {
        if (dir == null) return emptySet()
        val expired = HashSet<String>()
        val files = dir.listFiles()?.filter { it.name.endsWith(".wav") }.orEmpty()
        for (f in files) {
            val id = f.name.removeSuffix(".wav")
            val age = now - f.lastModified()
            // A sound the list doesn't name yet may be one just kept (it is written before it is listed): only an old one goes.
            if (age > RECORDING_HOURS * HOUR || (id !in listed && age > UNLISTED_MINUTES * 60_000)) {
                f.delete()
                if (id in listed) expired += id
            }
        }
        // Listed but no sound any more.
        listed.filter { id -> files.none { it.name == "$id.wav" } }.forEach { expired += it }
        return expired
    }

    /**
     * A reply or recording for a Whisper is never kept on the phone to wait for the PC. A task the phone doesn't know
     * (the list is still loading) could be a Whisper, so it doesn't wait either: when unsure, refuse.
     */
    fun mayWait(task: LinkTask?): Boolean = task != null && !task.incognito
}
