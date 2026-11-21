package io.github.odnwks.mimir.online

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every lookup Mímir made (Settings › Privacy check › Looked up): when, which site, which way it
 * went and how much came back. The words looked up are shown while the app runs; only time, site,
 * route and size are kept after, until the owner resets the Privacy check. A Whisper's lookups are
 * never kept.
 */
object LookupLog {
    enum class Route(val words: String) { Phone("from this phone"), Pc("via your PC"), Tor("by a hidden route") }

    data class Entry(val at: Long, val site: String, val words: String, val route: Route, val bytes: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private const val MAX = 200

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences("lookup_log", 0)
        prefs = p
        _entries.value = runCatching {
            val a = JSONArray(p.getString("entries", "[]"))
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { Entry(it.getLong("at"), it.getString("site"), "", Route.valueOf(it.getString("route")), it.getLong("bytes")) }
            }
        }.getOrDefault(emptyList())
    }

    /** The entries a Whisper added (by identity), shown while it lasts and gone when it ends. */
    private val whisperEntries = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Entry, Boolean>())

    @Synchronized
    fun add(e: Entry, mode: ChatMode) {
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        // A Whisper's lookups (with internet, through Tor) are shown while it lasts, never kept.
        if (mode == ChatMode.Normal) save() else whisperEntries += e
    }

    /** A Whisper ended: the words it looked up leave the list and the memory (PHE-03). */
    @Synchronized
    fun forgetWhisper() {
        if (whisperEntries.isEmpty()) return
        _entries.value = _entries.value.filterNot { it in whisperEntries }
        whisperEntries.clear()
    }

    /** With the Privacy check's Reset. */
    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save()
    }

    private fun save() {
        // Kept: everything but the words looked up.
        val a = JSONArray(_entries.value.map { JSONObject().put("at", it.at).put("site", it.site).put("route", it.route.name).put("bytes", it.bytes) })
        prefs?.edit()?.putString("entries", a.toString())?.apply()
    }
}
