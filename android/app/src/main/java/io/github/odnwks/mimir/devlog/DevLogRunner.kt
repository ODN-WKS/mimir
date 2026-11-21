package io.github.odnwks.mimir.devlog

import android.content.Context
import io.github.odnwks.mimir.BuildConfig
import io.github.odnwks.mimir.audio.Voices
import io.github.odnwks.mimir.data.Stations
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/**
 * Runs the developer log on the phone: follows this app's own logcat into [DevLog], writes the
 * header at each start and any crash, and honours the switch in Settings › Privacy check.
 * Whispers are never logged (see [DevLog]).
 */
object DevLogRunner {
    private const val PREFS = "devlog"
    private const val KEY = "on"
    private const val KEY_CHATS = "chats"

    @Volatile private var log: DevLog? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var process: Process? = null
    private var handlerSet = false

    // PHD-07, PHW-13: developer log and "Include my normal chats" are OFF by default.
    const val DEV_LOG_DEFAULT = false
    const val CHATS_DEFAULT = false

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, 0).getBoolean(KEY, DEV_LOG_DEFAULT)

    /** The chats switch as chosen (it only counts while the log is on). */
    fun chatsPref(context: Context): Boolean = context.applicationContext.getSharedPreferences(PREFS, 0).getBoolean(KEY_CHATS, CHATS_DEFAULT)

    /** "Include my normal chats": off by default, and only counts while the log itself is on. */
    fun chatsEnabled(context: Context): Boolean =
        enabled(context) && context.applicationContext.getSharedPreferences(PREFS, 0).getBoolean(KEY_CHATS, CHATS_DEFAULT)

    fun setChats(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_CHATS, on).apply()
        if (!on) (log ?: folder(app)?.let { DevLog(it) })?.removeChatLines()
    }

    // PHD-07: log stored in the app's private folder (not on external storage accessible by file managers).
    private fun folder(context: Context): File = File(context.applicationContext.filesDir, "devlog")

    private fun whisper(): Boolean = Engine.made()?.state?.value?.whisper == true

    /** Starts it (once per app run) when the switch is on. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!enabled(app) || thread != null) return
        val dir = folder(app)
        val l = DevLog(dir, whisperOpen = ::whisper, chatsOn = { chatsEnabled(app) })
        log = l
        l.tidy()
        installCrashHandler()
        Thread({ watchChats(l) }, "devlog-chats").also { it.isDaemon = true }.start()
        // What has gathered is written every few seconds, so a quiet log still shows its last lines.
        Thread({
            while (log === l) { Thread.sleep(DevLog.FLUSH_MS); l.flush() }
        }, "devlog-flush").also { it.isDaemon = true }.start()
        val t = Thread({ follow(app, l) }, "devlog")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** The Settings switch: off deletes the files at once, on starts it again. */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, on).apply()
        if (on) start(app)
        else {
            stop()
            folder(app)?.let { DevLog(it).clear() }
        }
    }

    private fun stop() {
        thread = null
        runCatching { process?.destroy() }
        process = null
        log?.clear()
        log = null
    }

    private fun follow(app: Context, l: DevLog) {
        // The header waits briefly for the engine, so it can say which model is chosen.
        var waited = 0
        while (Engine.made() == null && waited < 5000) { Thread.sleep(250); waited += 250 }
        l.writeNow(header(app))
        val mine = thread
        runCatching {
            val p = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            process = p
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (thread !== mine) break
                    l.write(line)
                }
            }
        }
        runCatching { process?.destroy() }
    }

    /** Follows the engine's chat while the log runs, writing normal chats' messages (see [ChatWatch]). */
    private fun watchChats(l: DevLog) {
        val mine = thread
        val watch = ChatWatch(l)
        while (Engine.made() == null) { if (thread !== mine) return; Thread.sleep(500) }
        val engine = Engine.made() ?: return
        runCatching {
            kotlinx.coroutines.runBlocking {
                engine.state.takeWhile { thread === mine }.collect { s ->
                    watch.see(s.chat.id, s.whisper, s.writing, s.chat.turns.map { SeenTurn(it.user, it.text) })
                }
            }
        }
    }

    private fun header(app: Context): String {
        val s = Engine.made()?.state?.value
        val station = runCatching { Stations.current() }.getOrNull()
        val voice = runCatching { Voices.made()?.store?.state?.value?.let { it.active?.name ?: "the phone's own" } }.getOrNull() ?: "not loaded yet"
        return DevLogHeader.text(
            version = BuildConfig.VERSION_NAME + " (" + BuildConfig.FLAVOR + ")",
            built = BuildConfig.BUILD_TIME,
            model = DevLogHeader.device(),
            android = DevLogHeader.androidName(),
            memoryMb = DevLogHeader.memoryMb(app),
            chosen = s?.model ?: "unknown yet",
            think = if (s == null) "unknown yet" else "think ${if (s.think) "on" else "off"}, effort ${s.effort.name}",
            paired = station?.paired == true,
            lookups = Online.settings.value.lookups,
            voice = voice,
        )
    }

    private fun installCrashHandler() {
        if (handlerSet) return
        handlerSet = true
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log?.writeNow("CRASH in thread ${t.name}:\n" + android.util.Log.getStackTraceString(e)) }
            runCatching { log?.flush() }
            before?.uncaughtException(t, e)
        }
    }
}
