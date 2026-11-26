package io.github.odnwks.mimir.online

import org.json.JSONObject
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What a lookup found: the words for the model, and where they came from. */
data class Found(val block: String, val sources: List<Source>)

/**
 * One source under an answer: the site's name, the page, and the words that were sent. [results]:
 * a search that was blocked, so this is the results page for the owner to open themselves.
 */
data class Source(
    val site: String, val url: String, val words: String, val results: Boolean = false,
    /** How it was found, when not from this phone ("your PC: Brave, Google CSE"). */
    val via: String = "",
)

/**
 * The weather, from several free sources asked at the same time (owner, 2026-11-19: fast, 6 s at
 * most): Open-Meteo, MET Norway, and for the United States the National Weather Service. The first
 * good answer is used; each source's answer is turned into the same small block of words. Places
 * by Open-Meteo's own place search, raced with OpenStreetMap's: only the place's name is sent to
 * find it; then only its rounded position, to get the forecast.
 */
object Weather {
    const val SITE_OPEN_METEO = "open-meteo.com"

    /**
     * The site of the source that answered last on this thread (what the app tells the model and
     * the owner it fetched from): "open-meteo.com" until something else has answered.
     */
    val SITE: String get() = lastSite.get() ?: SITE_OPEN_METEO
    private val lastSite = ThreadLocal<String?>()

    /** Time zones the place search told us, by rounded position (the forecast from MET Norway needs the local day). */
    private val zones = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun cell(lat: Double, lon: Double) = "%.2f,%.2f".format(Locale.ROOT, lat, lon)

    /**
     * Finds [name] ("Leeds", "Oslo, Norway", "Springfield IL", "Fresno California", "tomorrow in
     * Paris", a postcode), or null when there's no such place.
     */
    fun find(name: String, mode: ChatMode): Place? {
        Net.requireAllowed(mode)
        justFound.remove()
        val clean = placeName(name)
        // Postcodes: only OpenStreetMap's place search knows them (Open-Meteo's doesn't), so only it is asked,
        // and only from a normal chat (a Whisper asks Open-Meteo alone, as before).
        if (isPostcode(clean) && mode == ChatMode.Normal) {
            val code = clean.uppercase()
            val key = "place|postcode|" + code.lowercase()
            if (mode == ChatMode.Normal) Net.recall(key)?.let { return placeOf(JSONObject(it.text)).also { p -> justFound.set(p) } }
            val place = nominatimPlace(code, mode)
            if (place != null && mode == ChatMode.Normal) remember(key, place)
            return place?.also { justFound.set(it) }
        }
        val (q, region) = split(clean)
        val key = "place|" + q.lowercase() + (region?.let { "|" + it.lowercase() } ?: "")
        if (mode == ChatMode.Normal) Net.recall(key)?.let { return placeOf(JSONObject(it.text)) }
        // OpenStreetMap's place search is asked beside Open-Meteo's, from this phone only (a normal chat).
        val ways = listOf<() -> Place?>({ openMeteoPlace(q, region, mode) }) +
            if (mode == ChatMode.Normal) listOf<() -> Place?>({ nominatimPlace(listOfNotNull(q, region?.let { r -> US_STATES[r.uppercase()]?.let { "$it, United States" } ?: r }).joinToString(", "), mode) }) else emptyList()
        val place = if (ways.size == 1) ways[0]() else Net.firstGood(ways)
        if (place != null && mode == ChatMode.Normal) remember(key, place)
        return place?.also { justFound.set(it) }
    }

    private fun remember(key: String, place: Place) =
        Net.remember(key, Net.Got(JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon).toString(), 0, "application/json"), 24 * 3_600_000L)

    /** The place [find] found last on this thread: a place the owner named, not the saved one. */
    private val justFound = ThreadLocal<Place?>()

    /** A day or time beside the town ("tomorrow in Paris", "in Paris tomorrow"): never part of its name. */
    private val WHEN_WORDS = Regex(
        "^(?:(?:the\\s+)?(?:weather|forecast)|in|for|at|near|around|on|today|tonight|tomorrow|now|right now|later|" +
            "(?:this|next|the|on the|for the|over the)\\s+(?:week-?end|week|morning|afternoon|evening|night)|" +
            "(?:on\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b[\\s,]*" +
            "|[\\s,]+(?:today|tonight|tomorrow|now|right now|later|please|" +
            "(?:this|next|the|on the|for the|over the)\\s+(?:week-?end|week|morning|afternoon|evening|night)|" +
            "(?:on\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The place alone, without the day or the asking words around it: "tomorrow in Paris",
     * "weather tomorrow in Paris" and "in Paris tomorrow" are all "Paris". A name that would be
     * left empty is kept as it was.
     */
    fun placeName(name: String): String {
        var n = name.trim().trimEnd('?', '.', '!').trim()
        while (true) {
            val next = WHEN_WORDS.replace(n, "").trim()
            if (next == n || next.isEmpty()) return n
            n = next
        }
    }

    /** A postcode: "90210", "90210-1234", "SW1A 1AA", "M1 1AE", "K1A 0B1". */
    private val POSTCODE = Regex(
        "\\d{5}(?:-\\d{4})?|[A-Z]{1,2}\\d[A-Z\\d]?\\s*\\d[A-Z]{2}|[A-Z]\\d[A-Z]\\s?\\d[A-Z]\\d",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [name] is a postcode and nothing else. */
    fun isPostcode(name: String): Boolean = POSTCODE.matches(name.trim())

    /** A postcode somewhere in [text] ("weather in sw1a 1aa tomorrow" gives "SW1A 1AA"), or null. */
    fun postcodeIn(text: String): String? =
        Regex("(?<![\\p{L}\\d])(?:${POSTCODE.pattern})(?![\\p{L}\\d])", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase()

    private fun placeOf(o: JSONObject) = Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))

    /** US states by their two-letter code: "Springfield IL" is Springfield, Illinois (owner-found 2026-11-25: a town and "CA" gave a same-named town in Canada). */
    val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
        "CT" to "Connecticut", "DE" to "Delaware", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
        "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington",
        "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia",
    )

    /**
     * The town and the part that says where it is: "Paris, Texas" and "Springfield IL" (a state code
     * typed in capitals) and "Fresno California" (a state's full name at the end). Null region: the name alone.
     */
    fun split(name: String): Pair<String, String?> {
        val n = name.trim().trimEnd('?', '.', '!').trim()
        if (',' in n) return n.substringBefore(',').trim() to n.substringAfter(',').trim().ifEmpty { null }
        val words = n.split(Regex("\\s+"))
        if (words.size >= 2) {
            val last = words.last()
            if (last.length == 2 && last == last.uppercase() && last.uppercase() in US_STATES) return words.dropLast(1).joinToString(" ") to last
            for (state in US_STATES.values) {
                val sw = state.split(' ')
                if (words.size > sw.size && words.takeLast(sw.size).joinToString(" ").equals(state, ignoreCase = true)) {
                    return words.dropLast(sw.size).joinToString(" ") to state
                }
            }
        }
        return n to null
    }

    /** Whether a place-search result is in [region] (a state's code or name, or a country's name or code). */
    private fun inRegion(r: JSONObject, region: String): Boolean {
        val want = (US_STATES[region.uppercase()] ?: region).lowercase()
        return listOf(r.optString("admin1"), r.optString("country"), r.optString("country_code"), r.optString("admin2"))
            .any { it.isNotEmpty() && it.lowercase() == want }
    }

    private fun openMeteoPlace(q: String, region: String?, mode: ChatMode): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=${if (region != null) 10 else 1}&language=en&format=json&name=" + URLEncoder.encode(q, "UTF-8")
        val o = JSONObject(Net.get(url, mode, words = q, site = SITE_OPEN_METEO).text)
        val all = o.optJSONArray("results") ?: return null
        val r = (0 until all.length()).map { all.getJSONObject(it) }.let { list -> if (region == null) list.firstOrNull() else list.firstOrNull { inRegion(it, region) } } ?: return null
        val label = listOfNotNull(r.optString("name").ifEmpty { null }, r.optString("admin1").ifEmpty { null }, r.optString("country").ifEmpty { null })
            .distinct().joinToString(", ")
        val lat = r.getDouble("latitude")
        val lon = r.getDouble("longitude")
        r.optString("timezone").takeIf { it.isNotEmpty() }?.let { zones[cell(lat, lon)] = it }
        return Place(label.ifEmpty { q }, lat, lon)
    }

    private fun nominatimPlace(q: String, mode: ChatMode): Place? {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, "UTF-8")
        val a = org.json.JSONArray(Net.get(url, mode, words = q, site = "openstreetmap.org").text)
        val r = a.optJSONObject(0) ?: return null
        // "Portland, Multnomah County, Oregon, United States": the town, the region before the country, the country.
        val parts = r.optString("display_name").split(", ").map { it.trim() }.filter { it.isNotEmpty() && !isPostcode(it) }
        val label = when {
            parts.size >= 3 -> listOf(parts.first(), parts[parts.size - 2], parts.last())
            else -> parts
        }.distinct().joinToString(", ")
        return Place(label.ifEmpty { q }, r.getString("lat").toDouble(), r.getString("lon").toDouble())
    }

    /** Whether this phone's country uses °F, mph and inches. */
    fun imperial(locale: Locale = Locale.getDefault()): Boolean = locale.country in setOf("US", "LR", "MM")

    /** How long a forecast is kept, whichever source it came from. */
    const val KEEP_MS = 15 * 60_000L

    /**
     * The forecast for [place]: now, today and tomorrow (or the weekend), as a few plain lines for
     * the model. All sources are asked at once and the first good answer wins; a normal chat keeps
     * it 15 minutes under the place.
     */
    fun forecast(
        place: Place, mode: ChatMode, imperial: Boolean = imperial(), at: Long = System.currentTimeMillis(), ask: String = "",
        /** The saved place was used because the message named none: the block says so. */
        savedPlace: Boolean = usedSaved(place),
    ): Found {
        justFound.remove()
        Net.requireAllowed(mode)
        // About 1 km is plenty for a forecast, and says less about where the owner is.
        val lat = "%.2f".format(Locale.ROOT, place.lat)
        val lon = "%.2f".format(Locale.ROOT, place.lon)
        val key = "wx|$lat|$lon|${if (imperial) "imperial" else "metric"}"
        val kept = if (mode == ChatMode.Normal) Net.recall(key) else null
        val o = kept?.let { JSONObject(it.text) } ?: run {
            val tz = zones[cell(place.lat, place.lon)]
            val ways = mutableListOf<() -> JSONObject>(
                { openMeteo(lat, lon, imperial, place.name, mode) },
                { metNo(lat, lon, imperial, place.name, mode, zoneFor(tz, place.lon)) },
            )
            if (mode == ChatMode.Normal && inUnitedStates(place.lat, place.lon)) ways += { weatherGov(lat, lon, imperial, place.name, mode) }
            val got = if (mode == ChatMode.Normal) Net.firstGood(ways)!! else oneAfterAnother(ways)
            if (mode == ChatMode.Normal) Net.remember(key, Net.Got(got.toString(), 0, "application/json"), KEEP_MS)
            got
        }
        val site = o.optString("site").ifEmpty { SITE_OPEN_METEO }
        lastSite.set(site)
        val block = describe(o, place.name, imperial, at, ask).let { b ->
            if (!savedPlace) b else b.substringBefore('\n') + "\n" + savedPlaceSaid(place.name) + b.substringAfter('\n', "").let { if (it.isEmpty()) "" else "\n" + it }
        }
        return Found(block, listOf(Source(site, o.optString("page").ifEmpty { "https://open-meteo.com/" }, place.name)))
    }

    /** The line that tells the owner (and the model) the saved place was used. */
    fun savedPlaceSaid(name: String) = "No place was named, so the saved place $name was used."

    /** Whether [place] is the saved place and not one [find] just found for the message. */
    private fun usedSaved(place: Place): Boolean = place == Online.settings.value.place && justFound.get() != place

    /** Through Tor the sources are asked one after the other (fewer sites hear of it), the next only when one failed. */
    private fun oneAfterAnother(ways: List<() -> JSONObject>): JSONObject {
        val problems = mutableListOf<Throwable>()
        for (w in ways) try { return w() } catch (e: Net.Refused) { throw e } catch (e: Exception) { problems += e }
        throw Net.Failed(Net.plainAll(problems))
    }

    private fun zoneFor(tz: String?, lon: Double): java.time.ZoneId =
        tz?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneOffset.ofHours(Math.round(lon / 15.0).toInt().coerceIn(-12, 14))

    /** The United States (with Alaska, Hawaii and Puerto Rico), roughly: the weather service answers only there. */
    fun inUnitedStates(lat: Double, lon: Double): Boolean =
        (lat in 24.0..49.6 && lon in -125.0..-66.5) || (lat in 51.0..72.0 && lon in -170.0..-129.0) ||
            (lat in 18.5..22.5 && lon in -161.0..-154.5) || (lat in 17.5..18.7 && lon in -68.0..-65.0)

    private val JSON_OR_GEO = setOf("application/json", "application/geo+json")

    private fun openMeteo(lat: String, lon: String, imperial: Boolean, name: String, mode: ChatMode): JSONObject {
        val units = if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=8$units"
        val o = JSONObject(Net.get(url, mode, words = name, site = SITE_OPEN_METEO).text)
        if (o.optJSONObject("daily")?.optJSONArray("time") == null) throw Net.Failed("Open-Meteo gave an odd answer")
        return o.put("from", "Open-Meteo").put("site", SITE_OPEN_METEO).put("page", "https://open-meteo.com/")
    }

    private fun metNo(lat: String, lon: String, imperial: Boolean, name: String, mode: ChatMode, zone: java.time.ZoneId): JSONObject {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon"
        val o = JSONObject(Net.get(url, mode, words = name, site = "met.no", accept = JSON_OR_GEO).text)
        return fromMetNo(o, zone, imperial)
    }

    private fun weatherGov(lat: String, lon: String, imperial: Boolean, name: String, mode: ChatMode): JSONObject {
        val points = JSONObject(Net.get("https://api.weather.gov/points/$lat,$lon", mode, words = name, site = "weather.gov", accept = JSON_OR_GEO).text)
        val next = points.optJSONObject("properties")?.optString("forecast").orEmpty()
        // Only the service's own forecast address is followed.
        if (!next.startsWith("https://api.weather.gov/gridpoints/")) throw Net.Failed("the weather service gave an odd answer")
        val o = JSONObject(Net.get(next, mode, words = name, site = "weather.gov", accept = JSON_OR_GEO).text)
        return fromWeatherGov(o, imperial)
    }

    // ---- Each source's answer as the same shape (Open-Meteo's: "current" and "daily" lists) ----

    private fun symbolCode(symbol: String): Int {
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> 95
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            s == "fog" -> 45
            "sleet" in s -> 67
            s == "lightsnow" -> 71
            s == "snow" -> 73
            s == "heavysnow" -> 75
            "snow" in s -> 85
            s == "lightrain" -> 61
            s == "rain" -> 63
            s == "heavyrain" -> 65
            s == "lightrainshowers" -> 80
            s == "rainshowers" -> 81
            s == "heavyrainshowers" -> 82
            "rain" in s -> 63
            else -> 3
        }
    }

    /** How much a weather code matters in a day's summary: storms over snow over rain over cloud. */
    private fun rank(c: Int) = when (c) {
        95, 96, 99 -> 9
        71, 73, 75, 77, 85, 86 -> 8
        66, 67 -> 8
        61, 63, 65, 80, 81, 82 -> 7
        51, 53, 55, 56, 57 -> 6
        45, 48 -> 5
        3 -> 4
        2 -> 3
        1 -> 2
        else -> 1
    }

    /** A rough chance of rain from how much is forecast (MET Norway's compact answer has no chance). */
    private fun chanceFromAmount(mm: Double) = when {
        mm >= 2.0 -> 80
        mm >= 0.5 -> 55
        mm >= 0.2 -> 35
        mm > 0 -> 15
        else -> 5
    }

    /** MET Norway's compact forecast as Open-Meteo-shaped data, in the days of [zone]. Public for checks. */
    fun fromMetNo(o: JSONObject, zone: java.time.ZoneId, imperial: Boolean): JSONObject {
        val ts = o.optJSONObject("properties")?.optJSONArray("timeseries") ?: throw Net.Failed("MET Norway gave an odd answer")
        if (ts.length() == 0) throw Net.Failed("MET Norway gave an odd answer")
        fun temp(c: Double) = if (imperial) c * 9 / 5 + 32 else c
        fun speed(ms: Double) = if (imperial) ms * 2.23694 else ms * 3.6
        fun amount(mm: Double) = if (imperial) mm / 25.4 else mm
        class Day(val date: java.time.LocalDate) {
            var lo = Double.NaN; var hi = Double.NaN; var rain = 0.0; var wind = 0.0; var code = -1
        }
        val days = LinkedHashMap<java.time.LocalDate, Day>()
        var current: JSONObject? = null
        var first: java.time.ZonedDateTime? = null
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val at = java.time.Instant.parse(e.getString("time")).atZone(zone)
            val data = e.optJSONObject("data") ?: continue
            val d = data.optJSONObject("instant")?.optJSONObject("details") ?: continue
            val t = d.optDouble("air_temperature", Double.NaN)
            val w = d.optDouble("wind_speed", Double.NaN)
            val hour = data.optJSONObject("next_1_hours")
            val six = data.optJSONObject("next_6_hours")
            val symbol = (hour ?: six)?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
            if (current == null) {
                first = at
                current = JSONObject().put("time", at.toLocalDateTime().withSecond(0).withNano(0).toString())
                    .put("temperature_2m", temp(t)).put("wind_speed_10m", speed(if (w.isNaN()) 0.0 else w))
                    .put("weather_code", symbolCode(symbol)).put("precipitation", amount(hour?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0))
            }
            val day = days.getOrPut(at.toLocalDate()) { Day(at.toLocalDate()) }
            if (!t.isNaN()) { val v = temp(t); day.lo = if (day.lo.isNaN()) v else minOf(day.lo, v); day.hi = if (day.hi.isNaN()) v else maxOf(day.hi, v) }
            if (!w.isNaN()) day.wind = maxOf(day.wind, speed(w))
            // Hourly entries carry the next hour; the later six-hourly ones the next six: never both.
            val mm = (if (hour != null) hour else six)?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
            day.rain += amount(mm)
            if (at.hour in 6..21 && symbol.isNotEmpty()) {
                val c = symbolCode(symbol)
                if (day.code < 0 || rank(c) > rank(day.code)) day.code = c
            }
        }
        val list = days.values.filter { !it.lo.isNaN() }.take(8)
        val dry = if (imperial) 0.008 else 0.2
        val daily = JSONObject()
        daily.put("time", org.json.JSONArray(list.map { it.date.toString() }))
        // A "rain" symbol over a dry day is only a passing hour: no more than overcast.
        daily.put("weather_code", org.json.JSONArray(list.map { if (it.code < 0 || (it.rain < dry && rank(it.code) >= 6)) 3 else it.code }))
        daily.put("temperature_2m_max", org.json.JSONArray(list.map { it.hi }))
        daily.put("temperature_2m_min", org.json.JSONArray(list.map { it.lo }))
        daily.put("precipitation_probability_max", org.json.JSONArray(list.map { chanceFromAmount(if (imperial) it.rain * 25.4 else it.rain) }))
        daily.put("precipitation_sum", org.json.JSONArray(list.map { it.rain }))
        daily.put("wind_speed_10m_max", org.json.JSONArray(list.map { it.wind }))
        daily.put("chance_estimated", true)
        return JSONObject().put("current", current).put("daily", daily)
            .put("from", "MET Norway").put("site", "met.no").put("page", "https://www.met.no/")
    }

    /** The National Weather Service's twelve-hour periods as Open-Meteo-shaped data. Public for checks. */
    fun fromWeatherGov(o: JSONObject, imperial: Boolean): JSONObject {
        val periods = o.optJSONObject("properties")?.optJSONArray("periods") ?: throw Net.Failed("the weather service gave an odd answer")
        if (periods.length() == 0) throw Net.Failed("the weather service gave an odd answer")
        fun textCode(s: String): Int {
            val t = s.lowercase()
            return when {
                "thunder" in t -> 95
                "snow" in t && ("shower" in t || "flurr" in t) -> 85
                "snow" in t || "blizzard" in t -> 73
                "sleet" in t || "freezing" in t || "ice" in t -> 67
                "shower" in t -> 81
                "drizzle" in t -> 51
                "rain" in t -> 63
                "fog" in t || "haze" in t || "smoke" in t -> 45
                "partly" in t -> 2
                "mostly cloudy" in t || "cloudy" in t || "overcast" in t -> 3
                "mostly sunny" in t || "mostly clear" in t -> 1
                "sunny" in t || "clear" in t -> 0
                else -> 3
            }
        }
        fun temp(p: JSONObject): Double {
            val v = p.optDouble("temperature", Double.NaN)
            val f = p.optString("temperatureUnit", "F") == "F"
            return if (imperial == f) v else if (f) (v - 32) * 5 / 9 else v * 9 / 5 + 32
        }
        fun wind(p: JSONObject): Double {
            val n = Regex("\\d+").findAll(p.optString("windSpeed")).map { it.value.toDouble() }.maxOrNull() ?: 0.0
            return if (imperial) n else n * 1.609344
        }
        fun chance(p: JSONObject): Double = p.optJSONObject("probabilityOfPrecipitation")?.takeIf { !it.isNull("value") }?.optDouble("value", 0.0) ?: 0.0
        class Day(val date: String) { val temps = mutableListOf<Double>(); var pop = 0.0; var wind = 0.0; var code = -1; var dayCode = -1 }
        val days = LinkedHashMap<String, Day>()
        for (i in 0 until periods.length()) {
            val p = periods.getJSONObject(i)
            val d = days.getOrPut(p.getString("startTime").take(10)) { Day(p.getString("startTime").take(10)) }
            temp(p).takeIf { !it.isNaN() }?.let { d.temps += it }
            d.pop = maxOf(d.pop, chance(p))
            d.wind = maxOf(d.wind, wind(p))
            val c = textCode(p.optString("shortForecast"))
            if (d.code < 0) d.code = c
            if (p.optBoolean("isDaytime") && d.dayCode < 0) d.dayCode = c
        }
        val list = days.values.filter { it.temps.isNotEmpty() }.take(8)
        val now = periods.getJSONObject(0)
        val current = JSONObject().put("temperature_2m", temp(now)).put("wind_speed_10m", wind(now)).put("weather_code", textCode(now.optString("shortForecast")))
        val daily = JSONObject()
        daily.put("time", org.json.JSONArray(list.map { it.date }))
        daily.put("weather_code", org.json.JSONArray(list.map { if (it.dayCode >= 0) it.dayCode else it.code }))
        daily.put("temperature_2m_max", org.json.JSONArray(list.map { it.temps.max() }))
        daily.put("temperature_2m_min", org.json.JSONArray(list.map { it.temps.min() }))
        daily.put("precipitation_probability_max", org.json.JSONArray(list.map { it.pop }))
        daily.put("wind_speed_10m_max", org.json.JSONArray(list.map { it.wind }))
        return JSONObject().put("current", current).put("daily", daily)
            .put("from", "the US National Weather Service").put("site", "weather.gov").put("page", "https://www.weather.gov/")
    }

    /**
     * The forecast's numbers in a few plain lines (the phone reads its prompt slowly, so every
     * word counts): one for now, one for today, one for tomorrow, or Saturday and Sunday when the
     * question was about the weekend.
     */
    fun describe(o: JSONObject, name: String, imperial: Boolean, at: Long, ask: String = ""): String = buildString {
        val deg = if (imperial) "°F" else "°C"
        val wind = if (imperial) "mph" else "km/h"
        val rain = if (imperial) "in" else "mm"
        fun r(v: Double) = Math.round(v).toString()
        fun n(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ROOT, v)
        append("Weather for ").append(name).append(" (").append(o.optString("from").ifEmpty { "Open-Meteo" }).append(')')
        val c = o.optJSONObject("current")
        c?.optString("time")?.takeIf { it.isNotEmpty() }?.let { append(", ").append(it.substringAfter('T')).append(" local") }
        append('.')
        if (c != null && c.has("temperature_2m")) {
            append("\nNow: ").append(r(c.optDouble("temperature_2m"))).append(' ').append(deg)
            if (c.has("apparent_temperature")) append(" (feels like ").append(r(c.optDouble("apparent_temperature"))).append(')')
            if (c.has("weather_code")) append(", ").append(code(c.optInt("weather_code")))
            if (c.has("wind_speed_10m")) append(", wind ").append(r(c.optDouble("wind_speed_10m"))).append(' ').append(wind)
            if (c.optDouble("precipitation", 0.0) > 0) append(", ").append(n(c.optDouble("precipitation"))).append(' ').append(rain).append(" falling")
            append('.')
        }
        val d = o.optJSONObject("daily") ?: return@buildString
        val days = d.optJSONArray("time") ?: return@buildString
        val weekend = isWeekend(ask)
        val shown = if (weekend) weekendDays(days) else (0 until minOf(2, days.length())).toList()
        yesNo(ask, d, days, shown, weekend)?.let { append("\n").append(it) }
        val estimated = d.optBoolean("chance_estimated")
        for (i in shown) {
            // The day's name, and for the weekend its date once.
            val label = when {
                i == 0 && !weekend -> "Today"
                i == 1 && !weekend -> "Tomorrow"
                else -> runCatching { LocalDate.parse(days.getString(i)).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }.getOrDefault("Day ${i + 1}") + " (" + days.getString(i) + ")"
            }
            append('\n').append(label).append(": ")
            append(code(d.getJSONArray("weather_code").optInt(i)))
            val lo = d.getJSONArray("temperature_2m_min").optDouble(i)
            val hi = d.getJSONArray("temperature_2m_max").optDouble(i)
            if (r(lo) == r(hi)) append(", around ").append(r(hi)).append(' ').append(deg)
            else append(", ").append(r(lo)).append(" to ").append(r(hi)).append(' ').append(deg)
            if (!estimated) d.optJSONArray("precipitation_probability_max")?.optDouble(i)?.takeIf { !it.isNaN() }?.let { append(", ").append(r(it)).append("% rain") }
            d.optJSONArray("precipitation_sum")?.optDouble(i)?.takeIf { !it.isNaN() && it > 0 }?.let { append(", ").append(n(it)).append(' ').append(rain) }
            append('.')
        }
    }

    private val WEEKEND = Regex("\\bweek-?end\\b", RegexOption.IGNORE_CASE)
    private val RAIN_ASK = Regex("\\b(will it|is it going to|is it gonna|going to|gonna|is it) (rain|snow|be rainy)\\b|\\bis it (raining|snowing)\\b|\\bumbrella\\b", RegexOption.IGNORE_CASE)

    /** Whether [ask] is about the coming weekend. */
    fun isWeekend(ask: String) = WEEKEND.containsMatchIn(ask)

    /** The coming Saturday and the Sunday after it (today counts), as places in [days]. */
    private fun weekendDays(days: org.json.JSONArray): List<Int> {
        val dates = (0 until days.length()).map { runCatching { LocalDate.parse(days.getString(it)) }.getOrNull() }
        val sat = dates.indexOfFirst { it?.dayOfWeek == DayOfWeek.SATURDAY }
        return if (sat < 0) dates.indices.filter { dates[it]?.dayOfWeek == DayOfWeek.SUNDAY }
        else listOf(sat) + (sat + 1).takeIf { it < dates.size }.let { listOfNotNull(it) }
    }

    /** At or above this chance of rain the answer to "will it rain" is yes; from [MAYBE] it's "maybe". */
    const val YES_AT = 50
    const val MAYBE_AT = 30

    /**
     * For "will it rain tomorrow": the plain answer to start with, from the chance of rain
     * (50% or more: yes; 30 to 49: maybe; below: no). Null when it isn't that kind of question.
     */
    private fun yesNo(ask: String, d: JSONObject, days: org.json.JSONArray, shown: List<Int>, weekend: Boolean): String? {
        if (!RAIN_ASK.containsMatchIn(ask)) return null
        val chance = d.optJSONArray("precipitation_probability_max") ?: return null
        val pick = when {
            weekend -> shown
            Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE).containsMatchIn(ask) -> listOf(1)
            else -> listOf(0)
        }.filter { it < days.length() && !chance.optDouble(it).isNaN() }
        if (pick.isEmpty()) return null
        val top = pick.maxOf { chance.optDouble(it) }.toInt()
        val when_ = if (weekend) "this weekend" else if (pick[0] == 1) "tomorrow" else "today"
        val verb = if (Regex("\\bsnow", RegexOption.IGNORE_CASE).containsMatchIn(ask)) "snow" else "rain"
        val word = when {
            top >= YES_AT -> "Yes"
            top >= MAYBE_AT -> "Maybe"
            else -> "No"
        }
        return "Start the answer with \"$word\" (the chance of rain or snow $when_ is $top%), then the short details below. The question was whether it will $verb."
    }

    /** The WMO weather codes Open-Meteo uses, in plain words. */
    fun code(c: Int): String = when (c) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "violent showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "weather code $c"
    }
}
