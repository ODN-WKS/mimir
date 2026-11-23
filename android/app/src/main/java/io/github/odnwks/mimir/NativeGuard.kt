package io.github.odnwks.mimir

/**
 * Loads a native library and remembers whether it was there. A phone whose processor has no
 * build of a library (or an app copy missing it) gets `false`, so the feature turns off with one
 * plain line instead of the app crashing. A failure is remembered only for [RETRY_MS], so a
 * passing hiccup (low memory) doesn't switch a feature off for good.
 */
object NativeGuard {
    /** What loads a library (swapped in checks to simulate a missing one). */
    @Volatile var loader: (String) -> Unit = { System.loadLibrary(it) }
    /** The clock for the back-off (swapped in checks). */
    @Volatile var now: () -> Long = { System.nanoTime() / 1_000_000 }
    private const val RETRY_MS = 30_000L
    private val ok = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun available(lib: String): Boolean {
        if (ok.containsKey(lib)) return true
        val failed = failedAt[lib]
        if (failed != null && now() - failed < RETRY_MS) return false
        return try { loader(lib); ok[lib] = true; failedAt.remove(lib); true }
        catch (_: LinkageError) { failedAt[lib] = now(); false }
        catch (_: SecurityException) { failedAt[lib] = now(); false }
    }

    /** What is known without loading anything: true or false, or null if not tried yet (safe on the main thread). */
    fun known(lib: String): Boolean? = if (ok.containsKey(lib)) true else if (failedAt.containsKey(lib)) false else null

    /** Forgets what was learnt (checks only). */
    fun reset() { ok.clear(); failedAt.clear() }

    const val PHONE_CORE = "mimir_phone_core"
    const val STANDALONE = "standalone"
    const val VOICE = "sherpa-onnx-jni"
    const val VOICE_OFF = "Voice isn't available on this device. The phone's own voice reads instead."
    const val LINK_OFF = "Connecting to a PC isn't available on this device. Use this phone on its own."
}
