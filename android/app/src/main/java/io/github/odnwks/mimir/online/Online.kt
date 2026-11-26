package io.github.odnwks.mimir.online

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which kind of chat a lookup is for: it decides whether the internet may be used at all. */
enum class ChatMode {
    /** The open eye: lookups allowed while "Mímir may look things up online" is on. */
    Normal,
    /** The closed eye: no network, ever. */
    Whisper,
    /** The closed eye with the globe: lookups only, and only through Tor; nothing kept. */
    WhisperWeb,
}

/** A place for the weather: its name as shown, and where it is. */
data class Place(val name: String, val lat: Double, val lon: Double)

/**
 * Mímir online (Settings › Who answers › Looking things up): whether Mímir may look things up, and the
 * saved weather place. Read once at start; checks set [settings] directly.
 */
object Online {
    data class Settings(
        /** "Mímir may look things up online": on by default. */
        val lookups: Boolean = true,
        /** The weather place typed in by the owner; never a location from the phone. */
        val place: Place? = null,
        /** Web search (DuckDuckGo) as one of the lookups: on unless turned off in Settings. */
        val search: Boolean = true,
        /** The owner accepted the one-time warning about searching DuckDuckGo automatically. */
        val searchAccepted: Boolean = false,
        /** Which encyclopedias "what is / who was" questions read: both, compared, unless changed. */
        val encyclopedias: Encyclopedias = Encyclopedias.Both,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences("mimir_online", 0)
        prefs = p
        val place = p.getString("place_name", null)?.let { Place(it, p.getFloat("place_lat", 0f).toDouble(), p.getFloat("place_lon", 0f).toDouble()) }
        _settings.value = Settings(p.getBoolean("lookups", true), place, p.getBoolean("search", true), p.getBoolean("search_accepted", false),
            runCatching { Encyclopedias.valueOf(p.getString("encyclopedias", null) ?: "") }.getOrDefault(Encyclopedias.Both))
    }

    fun set(s: Settings) {
        _settings.value = s
        prefs?.edit()?.apply {
            putBoolean("lookups", s.lookups)
            putBoolean("search", s.search)
            putBoolean("search_accepted", s.searchAccepted)
            putString("encyclopedias", s.encyclopedias.name)
            if (s.place == null) { remove("place_name"); remove("place_lat"); remove("place_lon") }
            else { putString("place_name", s.place.name); putFloat("place_lat", s.place.lat.toFloat()); putFloat("place_lon", s.place.lon.toFloat()) }
        }?.apply()
    }

    /** Whether Android's Data Saver is on (pages aren't read then; set by the app, checks leave it off). */
    @Volatile var dataSaver: () -> Boolean = { false }

    /** Whether a chat in [mode] may use the internet right now. */
    fun allowed(mode: ChatMode): Boolean = when (mode) {
        ChatMode.Whisper -> false
        ChatMode.Normal, ChatMode.WhisperWeb -> _settings.value.lookups
    }
}
