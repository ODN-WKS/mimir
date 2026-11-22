package io.github.odnwks.mimir.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.odnwks.mimir.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Settings › Help and about › App updates shows. */
sealed interface UpdateStatus {
    data object None : UpdateStatus
    data object Checking : UpdateStatus
    data class Newest(val version: String) : UpdateStatus
    data class Ready(val version: String, val notes: String) : UpdateStatus
    data object Downloading : UpdateStatus
    /** The system's own install screen is up (older Android always asks). */
    data class Waiting(val version: String) : UpdateStatus
    data class Installed(val version: String) : UpdateStatus
    /** [install] is true when a saved release failed to install (so Install/Try again can run); false for a failed check. */
    data class Failed(val words: String, val install: Boolean = false) : UpdateStatus
}

/**
 * The phone app's own updates: a daily check of the signed list on GitHub (a plain GET with nothing
 * about the phone), then the new app file, checked three ways before Android installs it.
 */
object AppUpdater {
    private const val PREFS = "app-updates"
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.None)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** The public releases sign with a key the check builds don't have; they only show what's out. */
    val isDevBuild: Boolean get() = BuildConfig.APPLICATION_ID.endsWith(".dev")

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun downloadDir(c: Context): File = File(c.applicationContext.cacheDir, "new-version")

    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); schedule(c) }
    fun mobileData(c: Context) = prefs(c).getBoolean("mobile", false)
    fun setMobileData(c: Context, on: Boolean) { prefs(c).edit().putBoolean("mobile", on).apply(); schedule(c) }
    fun lastCheck(c: Context) = prefs(c).getLong("last", 0L)
    fun installAllowed(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()
    /** Android 12 and newer install later updates without asking again; older Android always shows its screen. */
    val silentLater: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** Test builds only: another list address and key (so a check can run against a list made for the test). */
    private fun testUpdater(c: Context): Updater {
        val p = prefs(c)
        val url = if (isDevBuild) p.getString("test-url", null) else null
        val key = if (isDevBuild) p.getString("test-key", null) else null
        return Updater(testNet ?: httpNet, key?.let { UpdateKey.bytes(it) } ?: UpdateKey.bytes(), url ?: UpdateKey.MANIFEST_URL)
    }

    /** Tests only: stands in for the network. */
    @Volatile internal var testNet: Net? = null

    private val httpNet = Net { url ->
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
        conn.inputStream
    }

    private fun isMetered(c: Context): Boolean =
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    /** Builds the status at start: an install that just finished, or a found update waiting. */
    fun restore(c: Context) {
        val p = prefs(c)
        val pending = p.getString("installing", null)
        if (pending != null && !Versions.newer(pending, BuildConfig.VERSION_NAME)) {
            p.edit().remove("installing").remove("found").apply()
            downloadDir(c).deleteRecursively()
            _status.value = UpdateStatus.Installed(BuildConfig.VERSION_NAME)
            return
        }
        // Old downloads that are no longer newer go.
        val v = p.getString("found", null)
        if (v != null && !Versions.newer(v, BuildConfig.VERSION_NAME)) { p.edit().remove("found").apply(); downloadDir(c).deleteRecursively() }
        if (pending == null) downloadDir(c).deleteRecursively()
        val f = prefs(c).getString("found", null)
        if (f != null && _status.value == UpdateStatus.None) _status.value = UpdateStatus.Ready(f, p.getString("notes", "") ?: "")
    }

    /** One check. [force] is the "Check now" button (no day rule, no data rule). Returns the found release, if newer. */
    fun check(c: Context, force: Boolean): Release? {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(now, lastCheck(c), enabled(c), isMetered(c), mobileData(c), force)) return null
        if (force) _status.value = UpdateStatus.Checking
        return when (val r = testUpdater(c).check(BuildConfig.VERSION_NAME)) {
            is Found.Newer -> {
                p.edit().putLong("last", now).putString("found", r.release.version).putString("notes", r.release.notes)
                    .putString("apk-url", r.release.apkUrl).putString("apk-sha", r.release.apkSha256).apply()
                _status.value = UpdateStatus.Ready(r.release.version, r.release.notes)
                r.release
            }
            Found.Newest -> {
                p.edit().putLong("last", now).remove("found").apply()
                downloadDir(c).deleteRecursively()
                _status.value = UpdateStatus.Newest(BuildConfig.VERSION_NAME)
                null
            }
            is Found.Failed -> { _status.value = UpdateStatus.Failed(r.words); null }
        }
    }

    /** True when the saved release can be fetched and installed. */
    fun foundRelease(c: Context): Release? {
        val p = prefs(c)
        val v = p.getString("found", null) ?: return null
        return Release(v, p.getString("apk-url", "") ?: "", p.getString("apk-sha", "") ?: "", p.getString("notes", "") ?: "")
    }

    /** Opens Android's "Install unknown apps" page for this app. */
    fun openAllowPage(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads, checks and hands the file to Android's installer. Call off the main thread. */
    fun downloadAndInstall(c: Context) {
        val rel = foundRelease(c) ?: return
        if (!installAllowed(c)) { openAllowPage(c); _status.value = UpdateStatus.Failed(NEEDS_ALLOW, install = true); return }
        _status.value = UpdateStatus.Downloading
        val file = testUpdater(c).download(rel, downloadDir(c)) { sameSigner(c, it) }
        if (file == null) { _status.value = UpdateStatus.Failed(UpdateWords.DIDNT_CHECK_OUT, install = true); return }
        try {
            install(c, file, rel.version)
            _status.value = UpdateStatus.Waiting(rel.version)
        } catch (e: Exception) {
            file.delete()
            _status.value = UpdateStatus.Failed("The update couldn't be installed. Try again later.", install = true)
        }
    }

    const val NEEDS_ALLOW = "To update itself, Mímir needs your OK once: allow installs from Mímir, then tap Install again."

    private fun install(c: Context, file: File, version: String) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { i -> s.openWrite("mimir.apk", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            prefs(c).edit().putString("installing", version).apply()
            val intent = Intent(c, InstallResultReceiver::class.java).setAction("io.github.odnwks.mimir.INSTALL_RESULT").setPackage(c.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            s.commit(PendingIntent.getBroadcast(c, id, intent, flags).intentSender)
        }
    }

    internal fun installResult(c: Context, status: Int, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            PackageInstaller.STATUS_SUCCESS -> {
                downloadDir(c).deleteRecursively()
                val v = prefs(c).getString("installing", "") ?: ""
                _status.value = UpdateStatus.Installed(v)
            }
            else -> {
                downloadDir(c).deleteRecursively()
                prefs(c).edit().remove("installing").apply()
                _status.value = UpdateStatus.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update wasn't installed." else "The update couldn't be installed. Try again later.",
                    install = true,
                )
            }
        }
    }

    /** The new file's signer must be the installed app's signer (Android enforces it too). */
    fun sameSigner(c: Context, apk: File): Boolean {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val flag = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
        if (theirs.packageName != c.packageName) return false
        val mine = pm.getPackageInfo(c.packageName, flag)
        @Suppress("DEPRECATION")
        fun of(i: android.content.pm.PackageInfo) = certs(i.signingInfo).ifEmpty { i.signatures?.map { it.toByteArray() } ?: emptyList() }
        return sameSigners(of(mine), of(theirs))
    }

    private fun certs(i: android.content.pm.SigningInfo?): List<ByteArray> =
        i?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()

    fun sameSigners(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.isEmpty() || a.size != b.size) return false
        fun h(l: List<ByteArray>) = l.map { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { x -> "%02x".format(x) } }.toSet()
        return h(a) == h(b)
    }

    /** The daily check, as a background job (only on Wi-Fi unless "Also on mobile data" is on). */
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c.applicationContext)
        if (!enabled(c)) { wm.cancelUniqueWork("app-update"); return }
        val net = if (mobileData(c)) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build()
        wm.enqueueUniquePeriodicWork("app-update", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = applicationContext
        val found = AppUpdater.check(c, force = false) ?: return Result.success()
        // Check builds only show what's out; the public app goes on to fetch it when Android allows installs.
        if (!AppUpdater.isDevBuild && AppUpdater.installAllowed(c) && found.version.isNotEmpty()) AppUpdater.downloadAndInstall(c)
        return Result.success()
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        AppUpdater.installResult(context, status, confirm)
    }
}
