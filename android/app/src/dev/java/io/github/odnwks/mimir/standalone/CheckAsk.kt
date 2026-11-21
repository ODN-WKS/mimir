package io.github.odnwks.mimir.standalone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import io.github.odnwks.mimir.audio.PhoneVoice

/**
 * Check build only: `am broadcast -a io.github.odnwks.mimir.STANDALONE_ASK
 * --es text "..." [--ez new true]` sends a message to standalone mode in the background, so a
 * check can measure it without taking the phone's screen. The reply, its speed and the bytes this
 * app sent or received meanwhile (which should be none) go to logcat (MimirStandalone).
 * `--es read long [--ei words 700]` (or `--es read "some text"`) reads that text aloud as the read
 * aloud button does, and logs when it started and when it ended, so a check can time it with the
 * screen off; `--ez stop_read true` stops it.
 * `--es download default` starts the default model's download instead, and `--es test default`
 * runs Test on this device.
 */
class CheckAsk : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The gate's kill-mid-save check works on files of its own, so it starts before the engine is made.
        if (intent.hasExtra("kill_test")) return CheckKill.handle(context, intent)
        val engine = Engine.get(context)
        // `--es download default` (or a model's id) starts its download, as the Download button
        // does: for checking what happens with no internet, on any phone.
        // `--es import default`: the model file pushed from the PC into this app's external files folder
        // (`adb push model.gguf /sdcard/Android/data/<pkg>/files/model.gguf`) is copied in as the download's
        // part-file and then checked and finished as a download is, so a 2.7 GB model needn't be fetched on the phone.
        intent.getStringExtra("import")?.let { id ->
            val m = Catalog.byId(if (id == "default") Catalog.default.id else id)
            val src = java.io.File(context.getExternalFilesDir(null), "model.gguf")
            Thread {
                val dst = java.io.File(context.filesDir, "models/${m.file}.part").also { it.parentFile?.mkdirs() }
                Log.i(Engine.TAG, "check import: copying ${src.length()} bytes")
                src.copyTo(dst, overwrite = true)
                src.delete()
                engine.download(m.id)
                while (engine.state.value.status[m.id].let { it is ModelStatus.Downloading || it is ModelStatus.Checking }) Thread.sleep(500)
                Log.i(Engine.TAG, "check import: ${engine.state.value.status[m.id]}")
            }.start()
            return
        }
        // `--ez stop_reply true`: Stop, as the button does, mid-answer.
        if (intent.getBooleanExtra("stop_reply", false)) { engine.stop(); Log.i(Engine.TAG, "check: stop pressed"); return }
        if (intent.getBooleanExtra("stop_read", false)) { PhoneVoice.get(context).stop(); return }
        intent.getStringExtra("read")?.let { what ->
            val text = if (what == "long") LongText.of(intent.getIntExtra("words", 700)) else what
            val voice = PhoneVoice.get(context)
            val started = System.nanoTime()
            voice.toggle("check-read", text)
            Log.i(Engine.TAG, "check read: started, ${text.split(' ').size} words, voice ${voice.voiceName}")
            Thread {
                Thread.sleep(500)
                while (voice.reading.value == "check-read") Thread.sleep(200)
                Log.i(Engine.TAG, "check read: ended after ${(System.nanoTime() - started) / 1_000_000} ms")
            }.start()
            return
        }
        intent.getStringExtra("download")?.let { id ->
            engine.download(if (id == "default") Catalog.default.id else id)
            return
        }
        // `--es test default` (or a model's id) runs Test on this device, as its button does, and
        // logs the verdict and the numbers; `--ez cancel_test true` cancels it.
        if (intent.getBooleanExtra("cancel_test", false)) return engine.cancelTest()
        intent.getStringExtra("test")?.let { id ->
            val model = if (id == "default") Catalog.default.id else id
            engine.test(model)
            Thread {
                while (engine.state.value.testing != null) {
                    Log.i(Engine.TAG, "check test: ${engine.state.value.testing}")
                    Thread.sleep(2_000)
                }
                val t = engine.state.value.tests[model]
                Log.i(Engine.TAG, "check test result: ${t?.verdict} | ${t?.numbers?.joinToString(" | ")} | problem=${engine.state.value.problem}")
            }.start()
            return
        }
        // `--es lookup "Portland"`: times the weather from this phone, through the PC, both at once,
        // and a search from this phone (DuckDuckGo) and through the PC (SearXNG), into logcat.
        intent.getStringExtra("lookup")?.let { place -> Thread { LookupTimings.run(place) }.start(); return }
        // `--es series weather|search [--ei rounds 10]`: that many lookups in a row as a stranger
        // asks them (nothing kept between them), each within the app's budget, timed into logcat.
        intent.getStringExtra("series")?.let { kind -> Thread { LookupTimings.series(kind, intent.getIntExtra("rounds", 10)) }.start(); return }
        val text = intent.getStringExtra("text") ?: return
        if (intent.getBooleanExtra("new", false)) engine.newChat()
        // `--es effort quick|balanced|thorough`: the effort chip's choice for this message (R4 asks with Thorough).
        intent.getStringExtra("effort")?.let { w -> Effort.entries.firstOrNull { it.name.equals(w, true) }?.let(engine::setEffort) }
        val uid = Process.myUid()
        val tx = TrafficStats.getUidTxBytes(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        val started = System.nanoTime()
        engine.send(text)
        Thread {
            while (true) {
                Thread.sleep(200)
                if (!engine.state.value.writing) break
            }
            val s = engine.state.value
            Log.i(
                Engine.TAG,
                "check: ${(System.nanoTime() - started) / 1_000_000} ms, ${s.speed}, network bytes sent " +
                    "${TrafficStats.getUidTxBytes(uid) - tx} received ${TrafficStats.getUidRxBytes(uid) - rx}, problem=${s.problem}",
            )
            Log.i(Engine.TAG, "check reply: ${s.chat.turns.lastOrNull()?.text?.replace('\n', ' ')?.take(400)}")
        }.start()
    }
}

/** Check build only: real lookup timings on this phone (`--es lookup "Portland"`). */
object LookupTimings {
    private fun ms(f: () -> Any?): String {
        val t0 = System.nanoTime()
        val r = runCatching(f)
        val took = (System.nanoTime() - t0) / 1_000_000
        return "$took ms " + r.fold({ "ok" }, { "FAILED (${it.message})" })
    }

    fun run(place: String) {
        val net = io.github.odnwks.mimir.online.Net
        val mode = io.github.odnwks.mimir.online.ChatMode.Normal
        val pc = net.viaPc
        val direct = net.transport
        fun weather() = io.github.odnwks.mimir.ui.weatherWords(place, null)
        for (round in 1..3) {
            net.forget()
            net.viaPc = null
            val phoneOnly = ms(::weather)
            net.forget()
            net.viaPc = pc
            net.transport = { throw java.io.IOException("phone's way off for this check") }
            val pcOnly = if (pc == null) "no PC paired" else ms(::weather)
            net.transport = direct
            net.forget()
            val both = ms(::weather)
            val kept = ms(::weather)
            io.github.odnwks.mimir.online.Search.forget()
            val ddg = ms { io.github.odnwks.mimir.online.Search.search("weather $place", mode) }
            io.github.odnwks.mimir.online.Search.forget()
            val searx = ms { io.github.odnwks.mimir.online.Search.searchViaPc("weather $place", mode) ?: error("PC away") }
            Log.i(Engine.TAG, "check lookup $round: weather from this phone $phoneOnly | through the PC $pcOnly | both at once $both | kept $kept | DuckDuckGo $ddg | SearXNG via PC $searx")
        }
        net.viaPc = pc
        net.transport = direct
        Log.i(Engine.TAG, "check lookup done")
    }

    private val PLACES = listOf("Portland", "Leeds", "Oslo", "Denver", "Osaka", "Lyon", "Austin", "Boston", "Seattle", "Miami", "Perth", "Quito")
    private val WORDS = listOf("artemis program", "electric cars", "world cup", "stock market", "mars rover", "solar eclipse",
        "james webb telescope", "olympics", "bitcoin price", "hurricane season", "nobel prize", "chess championship")

    /** [rounds] weather questions (a new place each, nothing kept) or searches, each as the app asks it. */
    fun series(kind: String, rounds: Int) {
        val net = io.github.odnwks.mimir.online.Net
        val mode = io.github.odnwks.mimir.online.ChatMode.Normal
        val times = ArrayList<Long>()
        for (i in 0 until rounds) {
            net.forget()
            io.github.odnwks.mimir.online.Search.forget()
            val t0 = System.nanoTime()
            val r = runCatching {
                net.within(net.BUDGET_MS) {
                    if (kind == "weather") {
                        val place = io.github.odnwks.mimir.online.Weather.find(PLACES[i % PLACES.size], mode) ?: error("no such place")
                        io.github.odnwks.mimir.online.Weather.forecast(place, mode).let { "${it.block.length} chars from ${it.sources.firstOrNull()?.site}" }
                    } else {
                        val w = "latest news about " + WORDS[i % WORDS.size]
                        (io.github.odnwks.mimir.online.Search.searchViaPc(w, mode) ?: io.github.odnwks.mimir.online.Search.search(w, mode))
                            .let { res -> "${res.size} results, ${io.github.odnwks.mimir.online.Search.describe(w, res).length} chars" }
                    }
                }
            }
            val took = (System.nanoTime() - t0) / 1_000_000
            times += took
            Log.i(Engine.TAG, "check series $kind ${i + 1}: $took ms " + r.fold({ "ok, $it" }, { "FAILED (${it.javaClass.simpleName}: ${it.message})" }))
        }
        Log.i(Engine.TAG, "check series $kind done: ${times.joinToString(" ")} ms; median ${times.sorted()[times.size / 2]}, worst ${times.max()}")
    }
}

/** Check build only: a long, plain text for reading aloud (about [words] words, a few minutes spoken). */
object LongText {
    private val SENTENCES = listOf(
        "The morning was quiet, and the harbour lay flat under a pale sky.",
        "A small boat moved slowly out past the lighthouse, leaving a thin line on the water.",
        "Nobody on the shore was in a hurry, so the day began without any noise at all.",
        "In the market the first stalls were opening, and the smell of bread drifted along the street.",
        "An old man arranged his oranges in careful rows and nodded to everyone who passed.",
        "By noon the wind had turned, and the flags on the long pier began to snap and lift.",
        "Children ran between the tables, and a dog followed them for a while before it lost interest.",
        "The afternoon brought clouds from the west, and the light turned soft and silver.",
        "Somewhere a bell rang four times, and the sound travelled out over the roofs and the water.",
        "When evening came the lamps were lit one by one, and the town settled into a warm and easy calm.",
    )

    fun of(words: Int): String {
        val sb = StringBuilder()
        var n = 0
        var i = 0
        while (n < words) {
            val s = SENTENCES[i++ % SENTENCES.size]
            sb.append(s).append(' ')
            n += s.split(' ').size
        }
        return sb.toString().trim()
    }
}
