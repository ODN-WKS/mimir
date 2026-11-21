package io.github.odnwks.mimir.data

import android.content.Context
import android.net.TrafficStats
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What this app sent and received over the network (Settings › Privacy check). Android counts it
 * per app from the phone's start; the app adds it up across restarts from the first time it
 * counted, and since the owner last reset it. Downloads the owner starts are counted apart.
 */
object NetUse {
    /** Bytes in and out. */
    data class Bytes(val received: Long = 0, val sent: Long = 0)

    data class Use(
        val sinceInstall: Bytes = Bytes(),
        val sinceReset: Bytes = Bytes(),
        /** Received for model and voice downloads, since install and since the reset. */
        val downloads: Long = 0,
        val downloadsSinceReset: Long = 0,
        /** Sent while downloads ran: the phone confirming to the server what arrived. */
        val downloadsSent: Long = 0,
        val downloadsSentSinceReset: Long = 0,
        /** When counting began (ms): the first time this app counted, not the phone's start. */
        val since: Long = 0,
        /** When it was last reset (ms), or 0 if never. */
        val resetAt: Long = 0,
        /** False when this phone doesn't count an app's network use. */
        val counted: Boolean = true,
    )

    /** This app's bytes (received, sent) since the phone started, or null if the phone doesn't count them. */
    var read: () -> Bytes? = {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else Bytes(rx, tx)
    }

    private val _use = MutableStateFlow(Use())
    val use: StateFlow<Use> = _use.asStateFlow()
    private val downloading = AtomicLong(0)
    private val downloadingSent = AtomicLong(0)
    /** Sent by downloads in this run of the app so far (finished ones), for the open chat's line. */
    private val downloadSentThisRun = AtomicLong(0)
    private var prefs: android.content.SharedPreferences? = null
    private var last = Bytes()

    /**
     * Runs a download the owner started (a model or a voice) and counts what went in and out
     * meanwhile as the download's: the bytes it received, and the small replies the phone sends
     * the server to confirm what arrived.
     */
    fun <T> download(f: () -> T): T {
        val before = read()
        try {
            return f()
        } finally {
            val after = read()
            if (before != null && after != null && after.received >= before.received && after.sent >= before.sent) {
                downloading.addAndGet(after.received - before.received)
                downloadingSent.addAndGet(after.sent - before.sent)
                downloadSentThisRun.addAndGet(after.sent - before.sent)
            }
        }
    }

    @Synchronized
    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences("net_use", 0)
        prefs = p
        // The first count: Android's numbers so far (since the phone started, and from earlier
        // copies of the app) aren't this app's to explain, so counting starts from now.
        // (Counts kept by 0.19, which started from the phone's start, are begun again too.)
        if (!p.contains("since_at")) {
            last = read() ?: Bytes()
            val fresh = Use(since = System.currentTimeMillis())
            _use.value = fresh
            save(fresh)
            return
        }
        last = Bytes(p.getLong("last_rx", 0), p.getLong("last_tx", 0))
        _use.value = Use(
            Bytes(p.getLong("rx", 0), p.getLong("tx", 0)),
            Bytes(p.getLong("rx", 0) - p.getLong("reset_rx", 0), p.getLong("tx", 0) - p.getLong("reset_tx", 0)),
            p.getLong("dl", 0), p.getLong("dl", 0) - p.getLong("reset_dl", 0),
            p.getLong("dl_tx", 0), p.getLong("dl_tx", 0) - p.getLong("reset_dl_tx", 0),
            p.getLong("since_at", 0), p.getLong("reset_at", 0),
        )
        sample()
    }

    /** Adds what's new since the last look (after a restart Android starts again from 0). */
    @Synchronized
    fun sample(): Use {
        val now = read()
        val u = _use.value
        val dl = downloading.getAndSet(0)
        val dlTx = downloadingSent.getAndSet(0)
        if (now == null) return u.copy(counted = false, downloads = u.downloads + dl, downloadsSinceReset = u.downloadsSinceReset + dl).also { _use.value = it }
        val add = Bytes(
            if (now.received >= last.received) now.received - last.received else now.received,
            if (now.sent >= last.sent) now.sent - last.sent else now.sent,
        )
        last = now
        val next = u.copy(
            sinceInstall = Bytes(u.sinceInstall.received + add.received, u.sinceInstall.sent + add.sent),
            sinceReset = Bytes(u.sinceReset.received + add.received, u.sinceReset.sent + add.sent),
            downloads = u.downloads + dl, downloadsSinceReset = u.downloadsSinceReset + dl,
            downloadsSent = u.downloadsSent + dlTx, downloadsSentSinceReset = u.downloadsSentSinceReset + dlTx, counted = true,
        )
        _use.value = next
        save(next)
        return next
    }

    /** Starts the "since reset" count again from now. */
    @Synchronized
    fun reset(at: Long = System.currentTimeMillis()) {
        val u = sample()
        val next = u.copy(sinceReset = Bytes(), downloadsSinceReset = 0, downloadsSentSinceReset = 0, resetAt = at)
        _use.value = next
        save(next)
    }

    /**
     * Bytes this app has sent since the phone started, leaving out what downloads the owner
     * started sent (for "Nothing sent during this chat"), or null.
     */
    fun sentNow(): Long? = read()?.sent?.let { it - downloadSentThisRun.get() }

    private fun save(u: Use) {
        prefs?.edit()?.apply {
            putLong("last_rx", last.received); putLong("last_tx", last.sent)
            putLong("rx", u.sinceInstall.received); putLong("tx", u.sinceInstall.sent)
            putLong("reset_rx", u.sinceInstall.received - u.sinceReset.received)
            putLong("reset_tx", u.sinceInstall.sent - u.sinceReset.sent)
            putLong("dl", u.downloads); putLong("reset_dl", u.downloads - u.downloadsSinceReset)
            putLong("dl_tx", u.downloadsSent); putLong("reset_dl_tx", u.downloadsSent - u.downloadsSentSinceReset)
            putLong("since_at", u.since)
            putLong("reset_at", u.resetAt)
        }?.apply()
    }

    /** Back to nothing counted (checks). */
    @Synchronized
    fun clear() { prefs = null; last = Bytes(); downloading.set(0); downloadingSent.set(0); downloadSentThisRun.set(0); _use.value = Use() }
}
