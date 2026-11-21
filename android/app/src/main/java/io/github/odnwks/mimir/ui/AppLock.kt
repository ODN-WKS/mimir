package io.github.odnwks.mimir.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lock Mímir (Settings › Privacy): off unless chosen. When on, the app opens only after the phone's
 * fingerprint, face or PIN, and again after it has been out of sight for the chosen time.
 *
 * This file holds the rules (pure, so tests can walk them), the state of the one running app, the
 * ask itself ([AppLockAuth]) and the two screens: the lock screen and the Settings switch.
 */
object AppLock {
    const val PREFS = "app_lock"
    private const val KEY_ON = "on"
    private const val KEY_AFTER = "after"

    /** How long the app may be out of sight before it asks again. */
    enum class After(val ms: Long, val label: String) {
        Now(0L, "Right away"), Minute(60_000L, "1 minute"), Five(300_000L, "5 minutes");
    }

    val DEFAULT_AFTER = After.Minute

    fun on(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ON, on).apply()
        // Switching it on or off is done by someone who just proved it's them (or is turning it off).
        leftAt = 0L
        // The first time away after switching it on always asks, whatever "Lock again after" says (QA2 #12).
        firstAway = on
        _locked.value = false
        _covered.value = false
    }

    fun after(ctx: Context): After {
        val name = ctx.getSharedPreferences(PREFS, 0).getString(KEY_AFTER, null)
        return After.entries.firstOrNull { it.name == name } ?: DEFAULT_AFTER
    }

    fun setAfter(ctx: Context, a: After) { ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_AFTER, a.name).apply() }

    /**
     * The rule: asks again when the lock is on and either it is already locked, or the app was out
     * of sight (since [leftAt], 0 if unknown-never-left) for at least [afterMs].
     */
    fun shouldLock(on: Boolean, alreadyLocked: Boolean, leftAt: Long, now: Long, afterMs: Long): Boolean =
        on && (alreadyLocked || (leftAt > 0L && now - leftAt >= afterMs))

    // ---- The one running app's state ----
    private val _locked = MutableStateFlow(false)
    private val _covered = MutableStateFlow(false)
    private val _note = MutableStateFlow<String?>(null)
    private var started = false
    private var leftAt = 0L
    private var firstAway = false
    private val _noScreenLock = MutableStateFlow(false)

    /** Locked, but the phone has no screen lock any more: the lock screen says so instead of opening (QA2 #13). */
    val noScreenLock: StateFlow<Boolean> get() = _noScreenLock

    /** Locked: no chat is drawn at all, only the lock screen. */
    val locked: StateFlow<Boolean> get() = _locked
    /** Just left the screen: the lock screen is laid over the app so Recents never shows chats. */
    val covered: StateFlow<Boolean> get() = _covered
    /** One plain line under the button after a failed try. */
    val note: StateFlow<String?> get() = _note

    /** The first thing on a fresh start (before the first frame): locked at once when the lock is on. */
    fun coldStart(ctx: Context, now: Long) {
        if (started) return
        started = true
        leftAt = 0L
        firstAway = false
        if (on(ctx)) {
            _locked.value = true
            // The startup animation is skipped when the lock was shown: one calm thing at a time.
            StartupGate.claim(ctx)
        }
    }

    /** The app left the screen (not a turn of the phone). */
    fun left(ctx: Context, now: Long) { if (on(ctx)) leftAt = now }

    /** The app is back in front: locks if it was away long enough (before anything is drawn). */
    fun back(ctx: Context, now: Long) {
        val lock = shouldLock(on(ctx), _locked.value, leftAt, now, if (firstAway) 0L else after(ctx).ms)
        if (leftAt > 0L) firstAway = false
        leftAt = 0L
        if (lock) _locked.value = true
    }

    /** True while the lock screen is in the drawn tree (the activity's plain shield can then come off). */
    @Volatile var lockInTree = false
    /** Called (on the main thread) once the lock screen has been drawn. */
    @Volatile var onLockDrawn: (() -> Unit)? = null

    fun cover(on: Boolean) { _covered.value = on }
    fun unlocked() { _locked.value = false; _covered.value = false; _note.value = null; _noScreenLock.value = false }
    fun noScreenLock(on: Boolean) { _noScreenLock.value = on }
    fun note(text: String?) { _note.value = text }

    /** For tests: forget this run, as if the process had just started. */
    internal fun resetForTest() { started = false; leftAt = 0L; firstAway = false; _noScreenLock.value = false; _locked.value = false; _covered.value = false; _note.value = null }

    /** What a notification says while the lock is on: no message words, no names. */
    fun lockedNote(): Pair<String, String> = "$AI_NAME has a reply" to "Open the app to see it"

    const val NO_SCREEN_LOCK = "Set up a screen lock in Android Settings first."
    const val NO_SCREEN_LOCK_NOW = "Lock $AI_NAME is on, but this phone has no screen lock. Set one up in Android settings."
    const val TRY_AGAIN = "Couldn't confirm it's you. Try again."
}

/** The phone's fingerprint, face or PIN, as the lock needs it (a stand-in in tests). */
interface AppLockAuth {
    /** The phone has a screen lock (a PIN, pattern or password). Without one nothing can be asked. */
    fun hasScreenLock(): Boolean
    /** A fingerprint, face or PIN can be asked for now. */
    fun available(): Boolean
    /** Asks. [fail] gets true when the person backed out, false for any other failure. */
    fun ask(title: String, ok: () -> Unit, fail: (backedOut: Boolean) -> Unit)
}

/** What the Settings switch and the lock screen use; the activity provides the real one. */
val LocalAppLockAuth = compositionLocalOf<AppLockAuth> {
    object : AppLockAuth {
        override fun hasScreenLock() = false
        override fun available() = false
        override fun ask(title: String, ok: () -> Unit, fail: (Boolean) -> Unit) = fail(false)
    }
}

/** The real thing, made in the activity's onCreate (a BiometricPrompt must exist before the activity starts). */
class BiometricAuth(private val activity: FragmentActivity) : AppLockAuth {
    private var ok: (() -> Unit)? = null
    private var fail: ((Boolean) -> Unit)? = null
    private var asking = false

    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            asking = false
            val f = ok; ok = null; fail = null
            f?.invoke()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            asking = false
            val f = fail; ok = null; fail = null
            f?.invoke(errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED)
        }
    })

    /** Android 9 and 10 can't mix strong-only with the PIN, so they take the weaker biometric class. */
    private val how = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun hasScreenLock() = io.github.odnwks.mimir.data.Stations.secure(activity)

    override fun available() = hasScreenLock() &&
        BiometricManager.from(activity).canAuthenticate(how) == BiometricManager.BIOMETRIC_SUCCESS

    override fun ask(title: String, ok: () -> Unit, fail: (Boolean) -> Unit) {
        if (asking) return
        asking = true
        this.ok = ok; this.fail = fail
        runCatching {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(how).build())
        }.onFailure {
            asking = false; this.ok = null; this.fail = null
            fail(false)
        }
    }
}

/** What to do when the lock is on and a phone can't be asked (its screen lock was taken away): open, and switch off. */
fun openSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Asks the phone to unlock; on success the app opens. A phone that lost its screen lock opens and the switch goes off. */
fun unlockNow(ctx: Context, auth: AppLockAuth) {
    // No screen lock any more: never open silently; the lock screen says so (QA2 #13).
    if (!auth.hasScreenLock()) { AppLock.noScreenLock(true); AppLock.note(AppLock.NO_SCREEN_LOCK_NOW); return }
    AppLock.noScreenLock(false)
    auth.ask("Unlock $AI_NAME", { AppLock.unlocked() }, { backedOut -> AppLock.note(if (backedOut) null else AppLock.TRY_AGAIN) })
}

/** The calm lock screen: the eye, what it is, one button. It never shows anything from a chat. */
@Composable
fun LockScreen(note: String?, onUnlock: () -> Unit, modifier: Modifier = Modifier, noScreenLock: Boolean = false, onTurnOff: () -> Unit = {}) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Two frames later the lock screen is really on screen: the activity's plain shield can come off.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        AppLock.lockInTree = true
        AppLock.onLockDrawn?.invoke()
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { AppLock.lockInTree = false } }
    Box(
        modifier.fillMaxSize().background(Palette.Ground)
            // Swallows touches, so nothing underneath can be reached while this is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MimirEye(EyeMode.Normal, Modifier.size(96.dp))
            Text("$AI_NAME is locked", style = Type.Section, color = Palette.Text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (note != null) Text(note, style = Type.Small, color = Palette.TextSoft, textAlign = TextAlign.Center)
            if (noScreenLock) {
                BigButton("Open Android settings", Look.Primary, { runCatching { ctx.startActivity(openSettingsIntent()) } }, modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp))
                BigButton("Turn off Lock $AI_NAME", Look.Outline, onTurnOff, modifier = Modifier.widthIn(max = 320.dp))
            } else BigButton("Unlock", Look.Primary, onUnlock, modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp), description = "Unlock $AI_NAME with your fingerprint, face or PIN")
        }
    }
}

/** Settings › Privacy: "Lock Mímir", off unless chosen, and how soon it asks again. */
@Composable
fun AppLockSetting() {
    val ctx = LocalContext.current
    val auth = LocalAppLockAuth.current
    var on by remember { mutableStateOf(AppLock.on(ctx)) }
    var after by remember { mutableStateOf(AppLock.after(ctx)) }
    var needsScreenLock by remember { mutableStateOf(false) }
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), spacing = 0.dp) {
        SwitchRow(on, { want ->
            if (!want) { AppLock.setOn(ctx, false); on = false; needsScreenLock = false }
            else if (!auth.available()) needsScreenLock = true
            else auth.ask("Turn on Lock $AI_NAME", { AppLock.setOn(ctx, true); on = true; needsScreenLock = false }, { })
        }, Modifier.heightIn(min = 56.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Lock $AI_NAME", style = Type.BodyTight, color = Palette.Text)
                Text("Ask for your fingerprint, face or phone PIN to open $AI_NAME.", style = Type.Small, color = Palette.TextDim)
            }
        }
        if (needsScreenLock && !on) {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppLock.NO_SCREEN_LOCK, style = Type.Small, color = Palette.AmberText)
                SmallButton("Open settings", { runCatching { ctx.startActivity(openSettingsIntent()) } }, description = "Open Android's security settings")
            }
        }
        if (on) {
            Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Lock again after", style = Type.BodyTight, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
                AppLock.After.entries.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).choice(after == a) { after = a; AppLock.setAfter(ctx, a) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioMark(after == a)
                        Text(a.label, style = Type.BodyTight, color = Palette.Text)
                    }
                }
            }
        }
    }
}
