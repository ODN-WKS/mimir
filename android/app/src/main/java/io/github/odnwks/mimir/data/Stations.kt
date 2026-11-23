package io.github.odnwks.mimir.data

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.odnwks.mimir.BuildConfig
import io.github.odnwks.mimir.alerts.Notifier
import io.github.odnwks.mimir.alerts.PrefsStayStore
import io.github.odnwks.mimir.link.Vault
import java.io.File

/**
 * The one link to the PC, for as long as the app's process lives: the screens, the check job
 * and the link-keeping service all use the same one.
 */
object Stations {
    @Volatile private var station: LinkStation? = null

    /** The link if it's made already (nothing is started). */
    fun current(): LinkStation? = station

    /**
     * The link, made the first time. The check build's adb arguments (`hints`: connect directly,
     * relays off; `check`: no screen lock needed) only count the first time.
     */
    fun get(context: Context, relays: Boolean = true, hints: List<String> = emptyList(), check: Boolean = false, deviceName: String? = null): LinkStation =
        station ?: synchronized(this) {
            station ?: LinkStation(
                Vault(File(context.filesDir, "link.vault")),
                BuildConfig.VERSION_NAME,
                relays,
                hints,
                secure = { check || secure(context) },
                stayStore = PrefsStayStore(context.applicationContext),
                audioDir = File(context.filesDir, "updates"),
                playback = io.github.odnwks.mimir.audio.Speaker.get(context),
                heard = PrefsHeardStore(context.applicationContext),
                modelStore = PrefsModelStore(context.applicationContext),
                network = { online(context.applicationContext) },
                projectStore = PrefsProjectStore(context.applicationContext),
                deviceName = deviceName ?: io.github.odnwks.mimir.data.DeviceName.of(context.applicationContext),
                recordDir = File(context.filesDir, "recordings"),
                // Pictures and files waiting to go with a reply or a new task (0.08: without it nothing could be attached).
                attachDir = File(context.filesDir, "attachments"),
                lockStore = PrefsLockStore(context.applicationContext),
                onScreen = { io.github.odnwks.mimir.alerts.Notifier.foreground },
            ).also { s ->
                val app = context.applicationContext
                s.onItems = { items -> Notifier.show(app, items); io.github.odnwks.mimir.alerts.LinkKeepService.maybeStart(app) }
                s.onFinished = { items -> Notifier.showFinished(app, items) }
                s.onHeader = { task, status, busy -> Notifier.headerSeen(app, task, status, busy) }
                io.github.odnwks.mimir.alerts.OutsideTasks.init(app)
                s.onPairingChanged = { Notifier.forgetFinished(app); io.github.odnwks.mimir.alerts.OutsideTasks.forgetAll(); io.github.odnwks.mimir.alerts.FinishedGate.clear() }
                s.onWorking = { titles -> Notifier.working(app, titles); io.github.odnwks.mimir.alerts.LinkKeepService.maybeStart(app) }
                s.onOnlineWithdrawn = { words -> io.github.odnwks.mimir.standalone.Engine.get(app).say(words) }
                s.onStandaloneModels = { list -> io.github.odnwks.mimir.standalone.Engine.get(app).useCatalog(list) }
                watchNetwork(app) { why ->
                    s.networkChanged(why)
                    if (why == "network available") io.github.odnwks.mimir.standalone.Engine.made()?.networkBack()
                }
                // Mímir's searches go through the owner's own SearXNG on the PC while it's connected.
                io.github.odnwks.mimir.online.Search.viaPc = { q -> s.webSearch(q)?.let(io.github.odnwks.mimir.online.Search::fromPc) }
                // The weather and the encyclopedias are asked both ways at once: from this phone and through the PC.
                io.github.odnwks.mimir.online.Net.viaPc = { r -> s.lookup(r.url.toString(), r.timeoutMs.toLong()) }
                io.github.odnwks.mimir.online.Net.phoneOnline = { online(app) }
                GroundRules.onChange = { s.sendGroundRules() }
                // The one chat sends its messages to the PC once paired.
                io.github.odnwks.mimir.standalone.Engine.made()?.pc = s
                station = s
            }
        }

    /**
     * Calls [onChange] whenever the phone's network comes, goes or changes (Wi-Fi to mobile
     * data and back). iroh can't see these on Android by itself; without them the link went
     * quiet on the S25 and didn't come back.
     */
    fun watchNetwork(context: Context, onChange: (String) -> Unit): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        var current: Network? = null
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != current) { current = network; onChange("network available") }
            }

            override fun onLost(network: Network) {
                if (network == current) current = null
                onChange("network lost") // lint:log-only
            }
        })
        true
    }.getOrDefault(false)

    /** This phone has a network that reaches the internet. */
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun secure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
}

/** Heard marks, kept on this phone. */
class PrefsHeardStore(private val context: Context) : HeardStore {
    private val prefs get() = context.getSharedPreferences("heard", Context.MODE_PRIVATE)
    override fun heard(): Set<String> = prefs.getStringSet("ids", emptySet()).orEmpty()
    override fun mark(id: String) {
        // Only the newest 500 are kept.
        val now = (heard() + id).toList().takeLast(500).toSet()
        prefs.edit().putStringSet("ids", now).apply()
    }
}

/** The model new tasks from this phone start on. */
class PrefsLockStore(private val context: Context) : LockStore {
    private val prefs get() = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    // Off by default once it's offered again (Stage 1, 4.3): a choice saved before the lock was
    // fixed ("on", which used to be the default) isn't carried over.
    override fun on(): Boolean = prefs.getBoolean("offered_on", false)
    override fun save(on: Boolean) { prefs.edit().remove("on").putBoolean("offered_on", on).apply() }
}

class PrefsModelStore(private val context: Context) : ModelStore {
    private val prefs get() = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    override fun model(): String = prefs.getString("model", "auto") ?: "auto"
    override fun save(model: String) { prefs.edit().putString("model", model).apply() }
}

/** The PC's project list in the app's private preferences. */
class PrefsProjectStore(private val context: Context) : ProjectStore {
    private val prefs get() = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
    override fun load(): List<Pair<String, String>> = runCatching {
        val a = org.json.JSONArray(prefs.getString("list", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("path") to it.getString("name") } }
    }.getOrDefault(emptyList())
    override fun save(list: List<Pair<String, String>>) {
        val a = org.json.JSONArray(list.map { (p, n) -> org.json.JSONObject().put("path", p).put("name", n) })
        prefs.edit().putString("list", a.toString()).apply()
    }
}
