package io.github.odnwks.mimir.standalone

import java.io.File

/** Receives a reply's words as they are written (whole UTF-8 characters); false stops it. */
fun interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/**
 * The model runner (llama.cpp, android/standalone). Loaded only when standalone mode first runs a
 * model, and only on phones whose CPU has the instructions it was built for.
 */
object Llm {
    private val loaded by lazy { io.github.odnwks.mimir.NativeGuard.available(io.github.odnwks.mimir.NativeGuard.STANDALONE) }

    /** Whether this phone's CPU can run it (dot product and int8 matrix instructions). */
    fun supported(cpuinfo: File = File("/proc/cpuinfo")): Boolean {
        val text = runCatching { cpuinfo.readText() }.getOrDefault("")
        return text.contains("asimddp") && text.contains("i8mm")
    }

    /** [supported], and the runner's library is on this phone (an x86_64 build has none). */
    fun usable(cpuinfo: File = File("/proc/cpuinfo")): Boolean = supported(cpuinfo) && loaded

    fun open(path: String, context: Int, threads: Int, temperature: Float): Long {
        if (!loaded) return 0L
        return load(path.toByteArray(), context, threads, temperature)
    }

    /** A picture for the model: RGB bytes (3 a pixel, rows from the top). */
    class Rgb(val width: Int, val height: Int, val bytes: ByteArray)

    fun reply(handle: Long, prompt: String, maxTokens: Int, sink: PieceSink, pictures: List<Rgb> = emptyList()): String =
        generate(
            handle, prompt.toByteArray(), pictures.map { it.bytes }.toTypedArray(),
            pictures.flatMap { listOf(it.width, it.height) }.toIntArray(), maxTokens, sink,
        )

    /** Loads the model's vision part once (false: it couldn't). */
    fun see(handle: Long, mmproj: String, threads: Int): Boolean = vision(handle, mmproj.toByteArray(), threads)

    @JvmStatic private external fun load(path: ByteArray, context: Int, threads: Int, temperature: Float): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic private external fun vision(handle: Long, mmproj: ByteArray, threads: Int): Boolean
    @JvmStatic private external fun generate(handle: Long, prompt: ByteArray, pictures: Array<ByteArray>, sizes: IntArray, maxTokens: Int, sink: PieceSink): String
}
