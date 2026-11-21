package io.github.odnwks.mimir.link

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * How the phone keeps its files safely: every write goes to a temp file of its own (two writers
 * never share one), is flushed to the disk, and only then replaces the real file in one step. The
 * result of that last step is checked, so a failed save is an error and never a silent loss.
 */
object SafeFile {
    @Throws(IOException::class)
    fun write(file: File, bytes: ByteArray) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + "." + UUID.randomUUID().toString().take(8) + ".tmp")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Temp files a kill left half-written next to [file] (`<name>.<id>.tmp`) are deleted; run when the file is loaded, before any writer. */
    fun sweep(file: File) {
        file.absoluteFile.parentFile?.listFiles()?.filter { it.name.startsWith(file.name + ".") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /**
     * Keeps a file the phone couldn't read, next to where it was, as `<name>.bad-<time>`. Each one has a name of its
     * own, so a second unreadable file never replaces the first. Null if it could not be moved (then the file is still
     * where it was, and nothing may be saved over it).
     */
    fun setAside(file: File, now: () -> Long = System::currentTimeMillis): File? {
        if (!file.exists()) return null
        val dir = file.absoluteFile.parentFile
        var n = 0
        var bad: File
        do { bad = File(dir, "%s.bad-%013d%s".format(file.name, now(), if (n == 0) "" else "-$n")); n++ } while (bad.exists())
        return runCatching { Files.move(file.toPath(), bad.toPath()); bad }.getOrNull()
    }

    /** The set-aside copies of [file], oldest first. */
    fun aside(file: File): List<File> =
        file.absoluteFile.parentFile?.listFiles()?.filter { it.name.startsWith(file.name + ".bad") }?.sortedBy { it.name }.orEmpty()

    /**
     * Whether [e] means the file itself is damaged (a changed or cut file, or text that isn't what was saved), as against the
     * Keystore being busy or locked, which says nothing about the file. Only damage moves a file aside.
     */
    fun isDamage(e: Throwable?): Boolean =
        e is javax.crypto.AEADBadTagException || e is javax.crypto.BadPaddingException || e is org.json.JSONException || e is IllegalArgumentException || e is java.io.EOFException || e is IndexOutOfBoundsException || e is NegativeArraySizeException
}
