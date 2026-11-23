package io.github.odnwks.mimir

/**
 * Loads a native library once and remembers whether it was there. A phone whose processor has no
 * build of a library (or an app copy missing it) gets `false`, so the feature turns off with one
 * plain line instead of the app crashing.
 */
object NativeGuard {
    /** What loads a library (swapped in checks to simulate a missing one). */
    @Volatile var loader: (String) -> Unit = { System.loadLibrary(it) }
    private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun available(lib: String): Boolean = known.getOrPut(lib) {
        try { loader(lib); true } catch (_: LinkageError) { false } catch (_: SecurityException) { false }
    }

    /** Forgets what was learnt (checks only). */
    fun reset() = known.clear()

    const val PHONE_CORE = "mimir_phone_core"
    const val STANDALONE = "standalone"
    const val VOICE = "sherpa-onnx-jni"
    const val VOICE_OFF = "Voice isn't available on this device. The phone's own voice reads instead."
    const val LINK_OFF = "Connecting to a PC isn't available on this device. Use this phone on its own."
}
