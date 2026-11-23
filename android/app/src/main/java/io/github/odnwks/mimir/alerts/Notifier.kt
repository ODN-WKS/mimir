package io.github.odnwks.mimir.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.odnwks.mimir.MainActivity
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.link.LItem

/**
 * The phone's notifications. Things that need you (a permission, a question, a release, a check
 * that started failing) make a sound; a quiet one says a task finished (generic words only); the quiet "checking with your PC" one shows only while the
 * check job runs on Android 11 and older.
 *
 * A notification never shows what an item is about on the lock screen: only that something waits.
 */
object Notifier {
    const val NEEDS = "needs_you"
    const val QUIET = "quiet"
    const val CONNECTION = "connection"
    const val WORKING = "working"
    const val FINISHED = "finished"
    const val KEEP = "keep"
    const val CHECK_ID = 1
    const val KEEP_ID = 2

    /** How many tasks Claude is working on, as last heard from the PC. */
    @Volatile private var workingOn = 0

    /** True while the app is on screen (then the app itself shows what's new). */
    @Volatile var foreground = false

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel(NEEDS, "Needs you", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Permissions, questions, releases and failing checks: Mímir waits for you." },
        )
        m.createNotificationChannel(
            NotificationChannel(QUIET, "Other news", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Fixes to check and things to approve when you have a moment." },
        )
        m.createNotificationChannel(
            NotificationChannel(CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown briefly while the phone checks with your PC." },
        )
        m.createNotificationChannel(
            NotificationChannel(FINISHED, "Finished tasks", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A quiet note when a task has finished." },
        )
        m.createNotificationChannel(
            NotificationChannel(KEEP, "Following your tasks", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown while Mímir keeps in touch with your PC for a task that is working or waiting for you." },
        )
        m.createNotificationChannel(
            NotificationChannel(WORKING, "Working on it", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A silent note while Mímir works on your tasks; it goes when nothing is working." },
        )
    }

    /** The words of the "working" note: "Claude is working on 2 tasks". */
    fun workingWords(n: Int) = if (n == 1) "Working on 1 task" else "Working on $n tasks"

    /** Claude now works on [n] tasks: the silent ongoing note shows, changes or goes. */
    fun working(context: Context, n: Int) {
        if (n == workingOn) return
        workingOn = n
        refreshWorking(context)
    }

    /**
     * Shows the "working" note while the app is off screen and any task works, and takes it away
     * otherwise (the app itself shows what's working while it's open).
     */
    fun refreshWorking(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        val n = workingOn
        if (n == 0 || foreground || !allowed(context)) return nm.cancel(WORKING, 0)
        val open = PendingIntent.getActivity(
            context, WORKING.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(context, WORKING)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle(workingWords(n))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        try { nm.notify(WORKING, 0, note) } catch (_: SecurityException) {}
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The ids of items already shown, so each makes a sound once. */
    private fun shown(context: Context) = context.getSharedPreferences("notified", Context.MODE_PRIVATE)

    /**
     * Shows a notification for each item not shown before, and takes away the ones answered
     * elsewhere. While the app is on screen it only takes them away.
     */
    fun show(context: Context, items: List<LItem>) {
        val prefs = shown(context)
        val before = prefs.getStringSet("ids", emptySet()).orEmpty()
        val now = items.map { it.askId }.toSet()
        val nm = NotificationManagerCompat.from(context)
        for (gone in before - now) nm.cancel(gone, 0)
        val fresh = items.filter { it.askId !in before }
        if (!foreground && allowed(context)) {
            for (i in fresh) post(context, i)
        }
        prefs.edit().putStringSet("ids", now).apply()
    }

    private fun finishedPrefs(context: Context) = context.getSharedPreferences("finished_notified", Context.MODE_PRIVATE)

    /**
     * A quiet, generic note for each finished item not handled before (by id). The very first
     * list a phone ever hears only seeds the ids. While the app is on screen nothing is posted.
     */
    @Synchronized
    fun showFinished(context: Context, items: List<io.github.odnwks.mimir.link.LFinished>) {
        val prefs = finishedPrefs(context)
        val seen = prefs.getStringSet("ids", emptySet()).orEmpty()
        val fresh = FinishedNotes.fresh(items, seen, prefs.getBoolean("seeded", false), System.currentTimeMillis() / 1000)
        prefs.edit().putStringSet("ids", FinishedNotes.remember(seen, items)).putBoolean("seeded", true).apply()
        if (foreground || !allowed(context)) return
        for (f in fresh) postFinished(context, f)
    }

    private fun postFinished(context: Context, f: io.github.odnwks.mimir.link.LFinished) {
        val (title, body) = if (io.github.odnwks.mimir.ui.AppLock.on(context)) io.github.odnwks.mimir.ui.AppLock.lockedNote() else FinishedNotes.words(f.how)
        val open = PendingIntent.getActivity(
            context, f.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open", "task").putExtra("task", f.task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, FINISHED)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(context).notify(f.id, 0, n) } catch (_: SecurityException) {}
    }

    /** What one notification says. */
    fun words(i: LItem, locked: Boolean = false): Pair<String, String> {
        // Lock Mímir on: nothing about the task on the lock screen.
        if (locked) return io.github.odnwks.mimir.ui.AppLock.lockedNote()
        val kind = Mapping.kind(i.kind)
        // PHD-26: lock-screen format "Mímir: [friendly action]", never raw project/kind data.
        val action = when (kind) {
            NeedsKind.Permission -> "Waiting for you"
            NeedsKind.Question, NeedsKind.Choose -> "A question for you"
            NeedsKind.Release -> "Ready to release"
            NeedsKind.CheckFailing -> "A check started failing"
            NeedsKind.CheckFix -> "A fix to check"
            NeedsKind.Approve -> "Waiting for your OK"
            null -> "Waiting for you"
        }
        return "Mímir: $action" to "Open the app to answer"
    }

    private fun post(context: Context, i: LItem) {
        val (title, body) = words(i, io.github.odnwks.mimir.ui.AppLock.on(context))
        val open = PendingIntent.getActivity(
            context, i.askId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open", if (i.task != null) "task" else "needs_you").putExtra("task", i.task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (i.serious) NEEDS else QUIET)
            .setSmallIcon(io.github.odnwks.mimir.R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (i.serious) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(i.askId, 0, n)
        } catch (_: SecurityException) {
            // The notification permission was taken back.
        }
    }
}
