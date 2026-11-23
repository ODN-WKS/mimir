package io.github.odnwks.mimir

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.odnwks.mimir.alerts.Notifier
import io.github.odnwks.mimir.alerts.Stay
import io.github.odnwks.mimir.data.Stations
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Llm
import io.github.odnwks.mimir.ui.AppRoot
import io.github.odnwks.mimir.ui.adaptive.Device
import io.github.odnwks.mimir.ui.adaptive.LocalDevice
import io.github.odnwks.mimir.ui.theme.MimirTheme
import io.github.odnwks.mimir.ui.theme.Motion

/**
 * The one activity: the real Mímir (Mímir) over the link. With "Lock Mímir" on (Settings › Privacy,
 * off unless chosen) the app opens with the fingerprint, face or the phone's own PIN.
 *
 * The check build (`dev` flavor) also takes, for checks run over adb: `--es pair_code <code>`,
 * `--es hint <host:port,...>` (connect directly, relays off), `--ez check true` (no lock) and
 * `--es device_name <name>` (the name this device shows on the PC, so testers can be told apart).
 */
class MainActivity : FragmentActivity() {
    private var check = false
    /** The unlock that confirms one action (YOLO), and the action waiting for it. */
    private lateinit var confirmPrompt: BiometricPrompt
    private var confirmed: (() -> Unit)? = null
    private var auth: io.github.odnwks.mimir.ui.AppLockAuth? = null
    /**
     * A plain dark view over everything. It goes up the moment the app starts to leave (and while locked,
     * until the lock screen is drawn), so a half-drawn or old frame of a chat is never what shows.
     */
    private var shield: android.view.View? = null
    private fun showShield() {
        if (!check && !isChangingConfigurations && io.github.odnwks.mimir.ui.AppLock.on(this)) {
            shield?.visibility = android.view.View.VISIBLE
            // While it leaves, the phone may not keep a picture of the app (Recents, and the picture shown when it comes back).
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    private fun hideShield() {
        shield?.visibility = android.view.View.GONE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mímir is always dark: white status and navigation icons, whatever the phone's light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        io.github.odnwks.mimir.data.GroundRules.load(this)
        io.github.odnwks.mimir.devlog.DevLogRunner.start(this)
        io.github.odnwks.mimir.data.NetUse.load(this)
        io.github.odnwks.mimir.update.AppUpdater.restore(this)
        io.github.odnwks.mimir.update.AppUpdater.schedule(this)
        io.github.odnwks.mimir.online.Online.load(this)
        io.github.odnwks.mimir.online.TorLink.setUp(filesDir, noBackupFilesDir)
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        io.github.odnwks.mimir.online.Online.dataSaver = {
            cm?.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
        io.github.odnwks.mimir.online.LookupLog.load(this)
        // Phones stay upright (the screens are drawn for it); tablets may turn.
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        check = BuildConfig.FLAVOR == "dev" && intent.getBooleanExtra("check", false)
        // The frame-timing check opens the check build with its example data: no PC, no link.
        if (check && intent.getBooleanExtra("example", false)) {
            val reduce = Motion.systemRemovesAnimations(this)
            // Kept across turning the tablet, like the real app's, so what was open stays open.
            val example = Shared.example ?: io.github.odnwks.mimir.data.FakeStation().also { Shared.example = it }
            val exampleNav = Shared.exampleNav ?: Navigator(Screen.Chat).also { Shared.exampleNav = it }
            setContent { MimirTheme(reduceMotion = reduce) { AppRoot(example, exampleNav) } }
            return
        }
        val hints = if (check) intent.getStringExtra("hint")?.split(',')?.filter { it.isNotBlank() }.orEmpty() else emptyList()
        val deviceName = if (BuildConfig.FLAVOR == "dev") intent.getStringExtra("device_name") else null
        val station = Stations.get(applicationContext, relays = hints.isEmpty(), hints = hints, check = check, deviceName = deviceName)
        // Unpaired, Chat opens with the welcome: pair with the PC, or use a model on this phone.
        val nav = Shared.nav ?: Navigator(Screen.Chat).also { Shared.nav = it }
        // Notifications: their channels, the permission (Android 13 and later), the 5-minute
        // check, and holding the link while a task works.
        Notifier.channels(this)
        // The permission is asked once, with a plain line why, right after pairing (PairDoneScreen), never mid-session.
        Stay.arm(this)
        if (intent.getStringExtra("open") == "needs_you" && station.paired) nav.open(Screen.NeedsYou)
        if (intent.getStringExtra("open") == "task" && station.paired) {
            val id = intent.getStringExtra("task")
            nav.open(if (id.isNullOrEmpty()) Screen.NeedsYou else Screen.Task(id))
        }
        if (intent.getStringExtra("open") == "standalone" && nav.current != Screen.Standalone) nav.open(Screen.Standalone)
        if (check && !station.paired) {
            intent.getStringExtra("pair_code")?.let { code ->
                station.pair(code)
                nav.open(Screen.PairDone)
            }
        }
        // Made here every time, so an answer still on its way after a rotation finds the new screen.
        confirmPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                resetInsets()
                confirmed?.invoke(); confirmed = null
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                resetInsets()
                confirmed = null
                // Backing out is the owner's choice; anything else is said, never silent.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED)
                    android.widget.Toast.makeText(this@MainActivity, "Couldn't confirm it's you. Try again.", android.widget.Toast.LENGTH_LONG).show()
            }
        })
        // Lock Mímir: decided before the first frame, so no chat flashes before the lock appears.
        val lockAuth = io.github.odnwks.mimir.ui.BiometricAuth(this)
        auth = lockAuth
        if (!check) io.github.odnwks.mimir.ui.AppLock.coldStart(this, android.os.SystemClock.elapsedRealtime())
        val reduceMotion = Motion.systemRemovesAnimations(this)
        val standaloneOk = Llm.usable()
        // One device word for every screen, chosen once from the display's shortest side.
        val device = Device.of(this)
        setContent {
            MimirTheme(reduceMotion = reduceMotion) {
                CompositionLocalProvider(LocalDevice provides device, io.github.odnwks.mimir.ui.LocalUnlockConfirm provides ::confirmByUnlock, io.github.odnwks.mimir.ui.LocalAppLockAuth provides lockAuth) {
                    val locked by io.github.odnwks.mimir.ui.AppLock.locked.collectAsState()
                    val covered by io.github.odnwks.mimir.ui.AppLock.covered.collectAsState()
                    val note by io.github.odnwks.mimir.ui.AppLock.note.collectAsState()
                    // Locked: no chat is drawn at all. Just left the screen: the lock screen sits over it for Recents.
                    if (!locked) AppRoot(station, nav, standaloneOk = standaloneOk)
                    if (locked || covered) {
                        val noLock by io.github.odnwks.mimir.ui.AppLock.noScreenLock.collectAsState()
                        io.github.odnwks.mimir.ui.LockScreen(note, onUnlock = { io.github.odnwks.mimir.ui.unlockNow(this@MainActivity, lockAuth) },
                            noScreenLock = noLock, onTurnOff = { io.github.odnwks.mimir.ui.AppLock.setOn(this@MainActivity, false); io.github.odnwks.mimir.ui.AppLock.unlocked() })
                    }
                }
            }
        }
        shield = android.view.View(this).apply {
            setBackgroundColor(0xFF0F1114.toInt())
            isClickable = true
            visibility = if (io.github.odnwks.mimir.ui.AppLock.locked.value) android.view.View.VISIBLE else android.view.View.GONE
        }
        addContentView(shield, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        io.github.odnwks.mimir.ui.AppLock.onLockDrawn = { hideShield() }
    }

    /** The phone's unlock confirms [ok] (YOLO); a phone with no screen lock can't confirm, so nothing runs. */
    /**
     * After the system PIN screen the window can keep the keyboard's height as if it were still up, and the app
     * then draws in only the top part of the screen: put the keyboard away and lay the window out again.
     */
    private fun resetInsets() {
        val v = window.decorView
        fun again() {
            androidx.core.view.WindowCompat.getInsetsController(window, v).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            androidx.core.view.ViewCompat.requestApplyInsets(v)
            v.requestLayout()
        }
        again()
        v.postDelayed({ again() }, 250)
    }

    private fun confirmByUnlock(title: String, ok: () -> Unit) {
        if (!secure(this)) {
            android.widget.Toast.makeText(this, "Set a screen lock on this phone first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val how = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        confirmed = ok
        runCatching {
            confirmPrompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(how).build())
        }.onFailure {
            confirmed = null
            android.widget.Toast.makeText(this, "Couldn't ask for your fingerprint or PIN. Try again.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!check) io.github.odnwks.mimir.ui.AppLock.back(this, android.os.SystemClock.elapsedRealtime())
        if (io.github.odnwks.mimir.ui.AppLock.locked.value) shield?.visibility = android.view.View.VISIBLE
        Notifier.foreground = true
        Notifier.clearFinished(this)
        Notifier.refreshWorking(this)
        // Android cuts an app's network soon after it leaves the screen; back in front, the link
        // is told and tries again at once.
        Stations.current()?.networkChanged("app in front")
        // Read aloud's voice loads in the background once the screen has settled, so the first
        // tap is heard at once (it's let go a while after the app leaves the screen).
        val app = applicationContext
        window.decorView.postDelayed({ if (Notifier.foreground) Thread({ io.github.odnwks.mimir.audio.Voices.get(app).onScreen() }, "voice-ahead").start() }, 1500)
    }

    override fun onResume() {
        super.onResume()
        // The prompt opens by itself; the cover (from onPause) comes off.
        io.github.odnwks.mimir.ui.AppLock.cover(false)
        // Back from the system PIN screen: no stale keyboard height.
        resetInsets()
        // Open: the shield comes off. Locked: it stays until the lock screen is drawn (or already is).
        if (!io.github.odnwks.mimir.ui.AppLock.locked.value || io.github.odnwks.mimir.ui.AppLock.lockInTree) hideShield()
        if (!check && io.github.odnwks.mimir.ui.AppLock.locked.value) auth?.let { io.github.odnwks.mimir.ui.unlockNow(this, it) }
    }

    override fun onUserLeaveHint() {
        showShield()
        super.onUserLeaveHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Anything that takes the screen's focus (the home key, the recents view, a dialog) puts the shield up first.
        if (!hasFocus) showShield()
        else if (!io.github.odnwks.mimir.ui.AppLock.locked.value || io.github.odnwks.mimir.ui.AppLock.lockInTree) hideShield()
    }

    override fun onPause() {
        showShield()
        // Leaving (not turning): the lock screen is laid over the app so Recents never shows a chat.
        val on = !check && io.github.odnwks.mimir.ui.AppLock.on(this)
        if (on && !isChangingConfigurations) io.github.odnwks.mimir.ui.AppLock.cover(true)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!check && !isChangingConfigurations) io.github.odnwks.mimir.ui.AppLock.left(this, android.os.SystemClock.elapsedRealtime())
        // Counted as the app leaves the screen, so a restart of the phone loses little.
        io.github.odnwks.mimir.data.NetUse.sample()
        Notifier.foreground = false
        Notifier.refreshWorking(this)
        // Work still going or waiting: keep the link as the app leaves.
        io.github.odnwks.mimir.alerts.LinkKeepService.maybeStart(this)
        io.github.odnwks.mimir.audio.Voices.made()?.offScreen()
        // "End Whispers when I leave the app" (Settings › Privacy check): an open Whisper is forgotten.
        io.github.odnwks.mimir.standalone.Engine.made()?.appLeft(
            getSharedPreferences(io.github.odnwks.mimir.ui.PHONE_CHAT_PREFS, 0).getBoolean(io.github.odnwks.mimir.ui.END_ON_LEAVE, false),
            isChangingConfigurations,
        )
    }

    /** The back stack lives as long as the app's process (the link: [Stations]). */
    private object Shared {
        var nav: Navigator? = null
        var example: io.github.odnwks.mimir.data.FakeStation? = null
        var exampleNav: Navigator? = null
    }
}

private fun secure(context: Context): Boolean = Stations.secure(context)
