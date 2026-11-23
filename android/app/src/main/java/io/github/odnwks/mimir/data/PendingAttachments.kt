package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LFile
import io.github.odnwks.mimir.link.LPicture
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.standalone.TurnFile
import io.github.odnwks.mimir.standalone.TurnPicture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * The pictures and files of a task message that is waiting for the PC: kept on this phone, sealed the
 * way the waiting words are, one file for each message, until the PC has taken the message. They go with
 * the message when the PC is back, even after the app was closed; nothing is dropped quietly. The PC
 * wipes hidden details from each one when it receives it.
 */
class PendingAttachments(private val dir: File?, private val codec: Vault.Codec) {
    class Held(val pictures: List<LPicture>, val files: List<LFile>) {
        val isEmpty: Boolean get() = pictures.isEmpty() && files.isEmpty()
    }

    /** Keeps [held] for message [id]; false when it could not be written (the message must not be taken then). */
    fun keep(id: String, held: Held): Boolean {
        if (held.isEmpty) return true
        val d = dir ?: return false
        return runCatching {
            d.mkdirs()
            val o = JSONObject()
                .put("pictures", JSONArray(held.pictures.map { JSONObject().put("t", it.mediaType).put("b", it.base64) }))
                .put("files", JSONArray(held.files.map { JSONObject().put("n", it.name).put("t", it.mediaType).put("b", it.base64) }))
            val tmp = File(d, "$id.tmp")
            tmp.writeBytes(codec.seal(o.toString()))
            check(tmp.renameTo(File(d, "$id.att")))
        }.isSuccess
    }

    /**
     * What is kept for message [id]: an empty [Held] when nothing was kept, or null when something was
     * kept but can't be read back (the message must not go without its pictures).
     */
    fun read(id: String): Held? {
        val f = dir?.let { File(it, "$id.att") }?.takeIf { it.isFile } ?: return Held(emptyList(), emptyList())
        return runCatching {
            val o = JSONObject(codec.open(f.readBytes()))
            val ps = o.getJSONArray("pictures"); val fs = o.getJSONArray("files")
            Held(
                (0 until ps.length()).map { ps.getJSONObject(it).let { p -> LPicture(p.getString("t"), p.getString("b")) } },
                (0 until fs.length()).map { fs.getJSONObject(it).let { x -> LFile(x.getString("n"), x.getString("t"), x.getString("b")) } },
            )
        }.getOrNull()
    }

    /** For showing only (the words under a waiting message): empty when nothing is kept or it is unreadable. */
    fun load(id: String): Held = read(id) ?: Held(emptyList(), emptyList())

    fun has(id: String): Boolean = dir?.let { File(it, "$id.att").isFile } == true

    /** What is shown for the message while it waits: the file names and the number of pictures. */
    fun words(id: String): String = said.getOrPut(id) {
        load(id).let { h ->
            (h.files.map { it.name } + listOfNotNull(h.pictures.size.takeIf { it > 0 }?.let { if (it == 1) "A picture" else "$it pictures" })).joinToString()
        }
    }

    private val said = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun drop(id: String) { said.remove(id); dir?.let { File(it, "$id.att").delete(); File(it, "$id.tmp").delete() } }

    /** Everything kept for a message that is no longer waiting goes (a task removed, a pairing ended). */
    fun dropAllExcept(waiting: Set<String>, ageMs: Long = 60_000) {
        val old = System.currentTimeMillis() - ageMs
        dir?.listFiles()?.forEach { f -> if (f.name.removeSuffix(".att").removeSuffix(".tmp") !in waiting && f.lastModified() < old) f.delete() }
    }

    companion object {
        private val b64 = Base64.getEncoder()

        fun held(pictures: List<TurnPicture>, files: List<TurnFile>) = Held(
            pictures.map { LPicture("image/jpeg", b64.encodeToString(it.jpeg)) },
            files.mapNotNull { f -> f.bytes?.let { LFile(f.name, f.mime, b64.encodeToString(it)) } },
        )
    }
}
