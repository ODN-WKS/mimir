package io.github.odnwks.mimir.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A natural voice the phone reads aloud with, as on the PC (say-update): the same model,
 * speed and pauses. It is built into the app (no download).
 */
data class NaturalVoice(
    val id: String,
    /** What people see: "Ingrid" (the id stays "alba", the model's own name). */
    val name: String,
    /** One line: what it sounds like. */
    val note: String,
    /** The model inside the app (assets), read from there as it is. */
    val model: String,
    /** Its tokens, inside the app too. */
    val tokens: String,
    /** How much faster than spoken it plays, keeping its pitch (Ingrid: +10%). */
    val tempo: Float,
    val threads: Int,
    val licence: String,
    /** Files the app carries for it that the engine reads from a folder (assets/voices/…): asset name to the name it gets there. */
    val carried: Map<String, String>,
    /** What the sample button says. */
    val sample: String,
)

/**
 * The read-aloud voice built into the app: Ingrid (owner, 2026-11-19: no Einar for now, and Ingrid
 * in the app itself, ready on first launch). The other choice is the phone's own voice.
 */
object VoiceCatalog {
    /**
     * Piper en_GB alba (medium), the voice the PC app reads with; Ingrid here, 10% quicker
     * than its own pace. Which model file ships is set in one place: the app's build file
     * (ingridModel), which puts it at [model].
     */
    val ingrid = NaturalVoice(
        id = "alba", name = "Ingrid", note = "Female, soft Scottish · calm and clear",
        model = "voices/ingrid-model.ort", tokens = "voices/alba-tokens.txt",
        tempo = 1.10f, threads = 2, licence = "CC BY 4.0 (University of Edinburgh data)",
        carried = mapOf("espeak-ng-data" to "espeak-ng-data"),
        sample = "Hello, I'm Ingrid. This is how I sound when I read your messages aloud.",
    )

    val all: List<NaturalVoice> = listOf(ingrid)
    val default: NaturalVoice get() = ingrid

    fun byId(id: String?): NaturalVoice? = all.firstOrNull { it.id == id }
}

/** What Settings › Voice shows. */
data class VoicesState(
    /** The voice read aloud uses (null: the phone's own voice). */
    val chosen: String? = VoiceCatalog.default.id,
    /** The voice whose sample is playing. */
    val sampling: String? = null,
) {
    /** The voice that reads aloud now, or null for the phone's own. */
    val active: NaturalVoice? get() = VoiceCatalog.byId(chosen)
}

/** The choices on Settings › Voice. */
interface VoiceActions {
    /** Makes [id] the reading voice; null goes to the phone's own voice. */
    fun choose(id: String?)
    /** Plays (or stops) a short sample. */
    fun sample(id: String)
}

/** The files the app carries for the voices (its assets, or a stand-in in checks). */
interface Carried {
    fun open(path: String): InputStream
    /** The names inside a folder; empty for a file. */
    fun list(path: String): List<String>
}

/**
 * The voice choice and the few files the voice engine reads from a folder (espeak's data),
 * copied from the app into [dir] once per app version that changes them. Everything else in
 * [dir] goes when the app starts: voices downloaded by older versions (Scottish Daniel, a
 * downloaded Alba) and half-done downloads, so nothing sits unused.
 */
class VoiceStore(
    private val dir: File,
    private val carried: Carried,
    private val catalog: List<NaturalVoice> = VoiceCatalog.all,
    /** Called before a voice's folder is replaced (the reader lets go of it). */
    private val release: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(VoicesState())
    val state: StateFlow<VoicesState> = _state.asStateFlow()
    private val saved = File(dir, "voices.txt")

    init {
        dir.mkdirs()
        val keep = setOf(saved.name) + catalog.map { folder(it).name }
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        // Older versions kept "chosen" (often empty: the phone's voice until a download); the
        // built-in Ingrid is the voice now, unless someone chose the phone's own since.
        val m = read()
        val chosen = when (val v = m["voice"]) {
            null -> VoiceCatalog.default.id
            PHONE -> null
            else -> v.takeIf { id -> catalog.any { it.id == id } } ?: VoiceCatalog.default.id
        }
        // What older versions kept about downloads goes too.
        val wanted = setOf("voice") + catalog.map { "carried.${it.id}" }
        if (m.keys.any { it !in wanted }) edit { old -> old.filterKeys { it in wanted } }
        _state.value = VoicesState(chosen = chosen)
    }

    fun byId(id: String?): NaturalVoice? = catalog.firstOrNull { it.id == id }

    /** The folder with the files [v] reads from a folder. */
    fun folder(v: NaturalVoice): File = File(dir, "${v.id}-builtin")

    /** Bytes the voice's files take on the phone (Settings › Storage). */
    fun bytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun read(): Map<String, String> = runCatching {
        saved.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrDefault(emptyMap())

    private fun edit(change: (Map<String, String>) -> Map<String, String>) = synchronized(saved) {
        val m = change(read())
        val tmp = File(dir, "voices.txt.tmp")
        tmp.writeText(m.entries.joinToString("") { "${it.key}=${it.value}\n" })
        tmp.renameTo(saved)
    }

    /** Makes [id] the reading voice; null is the phone's own voice. */
    fun choose(id: String?) {
        if (id != null && byId(id) == null) return
        _state.update { it.copy(chosen = id) }
        edit { it + ("voice" to (id ?: PHONE)) }
    }

    fun setSampling(id: String?) = _state.update { it.copy(sampling = id) }

    /** Copies what the app carries for [v] into [into] (a folder is copied whole). */
    private fun copyCarried(v: NaturalVoice, into: File) {
        for ((asset, name) in v.carried) copyAsset("voices/$asset", File(into, name))
    }

    private fun copyAsset(path: String, to: File) {
        val inside = carried.list(path)
        if (inside.isNotEmpty()) {
            to.deleteRecursively()
            to.mkdirs()
            inside.forEach { copyAsset("$path/$it", File(to, it)) }
            return
        }
        val tmp = File(to.path + ".tmp")
        carried.open(path).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!tmp.renameTo(to)) throw IOException("couldn't keep $path")
    }

    /**
     * Makes sure [v]'s folder has the files this app version carries (the first time, and after
     * an update that changes them); false if they couldn't be put there.
     */
    fun ready(v: NaturalVoice): Boolean {
        val key = "carried.${v.id}"
        val home = folder(v)
        if (read()[key] != CARRIED_VERSION.toString() || !home.isDirectory) {
            release(v.id)
            val stage = File(dir, "${home.name}.new")
            val ok = runCatching {
                stage.deleteRecursively(); stage.mkdirs()
                copyCarried(v, stage)
                home.deleteRecursively()
                if (!stage.renameTo(home)) throw IOException("couldn't keep the voice's files")
            }.onFailure { Log.w(TAG, "couldn't put ${v.id}'s files in place", it); stage.deleteRecursively() }.isSuccess
            if (!ok) return false
            edit { it + (key to CARRIED_VERSION.toString()) }
        }
        return true
    }

    companion object {
        private const val TAG = "Voices"
        private const val PHONE = "phone"
        /** Raise when the files in assets/voices that are copied out change, so phones copy them again. */
        const val CARRIED_VERSION = 2
    }
}
