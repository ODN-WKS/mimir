package io.github.odnwks.mimir.labs

import io.github.odnwks.mimir.labs.CheckLine.Result

/** What "Run checks" asks of the phone. The real ones are in [LabsProbes]; tests give their own. */
interface Probes {
    fun version(): String
    fun freeBytes(): Long
    fun paired(): Boolean
    /** Whether the PC answers right now. */
    fun pcOnline(): Boolean
    /** Asks the PC's model for one word. Returns the time in ms and whether it answered; null when the PC isn't set to answer chats. */
    fun askPc(): PcAnswer?
    /** Whether Ingrid's voice loaded (quietly: nothing is played). */
    fun voiceLoads(): Boolean
    fun lookupsOn(): Boolean
    /** Whether a plain lookup worked. */
    fun lookup(): Boolean
    fun elapsed(): Long = System.nanoTime() / 1_000_000
}

data class PcAnswer(val reachedMs: Long, val answered: Boolean)

/** Runs the checks one by one and says each in plain words. */
object Checks {
    const val LOW_BYTES = 1_000_000_000L

    /** The whole set is done within this long, however slow one check is (a check that isn't done says so). */
    const val TIMEOUT_MS = 15_000L

    /**
     * Runs the checks side by side, each line handed to [onLine] (its place in the list, the line) as soon as it
     * is done. [thisDevice] is "this phone" or "this tablet". The result is in the same order as always.
     */
    fun run(p: Probes, thisDevice: String = "this phone", timeoutMs: Long = TIMEOUT_MS, onLine: (Int, CheckLine) -> Unit = { _, _ -> }): List<CheckLine> {
        val ask = lazy { p.askPc() }
        val jobs: List<Triple<String, String, () -> CheckLine>> = listOf(
            Triple("Your PC", "Open Mímir on your PC and try again") { pc(p, ask) },
            Triple("Your PC's model", "Check Mímir on your PC has a model ready") { model(p, ask) },
            Triple("Ingrid's voice", "Close other apps and try again") { voice(p) },
            Triple("Looking things up", "Check $thisDevice has internet") { lookup(p, thisDevice) },
            Triple("Space on $thisDevice", "Free some space in Settings › Storage") { storage(p, thisDevice) },
            Triple("App version", "") { version(p) },
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(jobs.size) { r -> Thread(r, "labs-check").apply { isDaemon = true } }
        val done = java.util.concurrent.ExecutorCompletionService<Pair<Int, CheckLine>>(pool)
        val out = arrayOfNulls<CheckLine>(jobs.size)
        try {
            jobs.forEachIndexed { i, j -> done.submit { i to j.third() } }
            val end = System.nanoTime() + timeoutMs * 1_000_000
            var left = jobs.size
            while (left > 0) {
                val wait = (end - System.nanoTime()) / 1_000_000
                val f = (if (wait > 0) done.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS) else done.poll()) ?: break
                val (i, line) = f.get()
                out[i] = line; left--
                onLine(i, line)
            }
        } finally { pool.shutdownNow() }
        return jobs.mapIndexed { i, j ->
            out[i] ?: CheckLine(j.first, Result.Problem, "Took too long", j.second.ifBlank { null }).also { onLine(i, it) }
        }
    }

    private fun safe(name: String, next: String, f: () -> CheckLine): CheckLine =
        try { f() } catch (e: Exception) { CheckLine(name, Result.Problem, "Stopped before it finished", next) }

    private fun pc(p: Probes, ask: Lazy<PcAnswer?>) = safe("Your PC", "Open Mímir on your PC and try again") {
        when {
            !p.paired() -> CheckLine("Your PC", Result.Skipped, "Not connected, so nothing to check")
            !p.pcOnline() -> CheckLine("Your PC", Result.Problem, "Not answering", "Check it is on and Mímir is open there")
            else -> {
                val a = ask.value
                CheckLine("Your PC", Result.Good, "Reached" + (a?.let { " in ${words(it.reachedMs)}" } ?: ""))
            }
        }
    }

    private fun model(p: Probes, ask: Lazy<PcAnswer?>) = safe("Your PC's model", "Check Mímir on your PC has a model ready") {
        when {
            !p.paired() -> CheckLine("Your PC's model", Result.Skipped, "Not connected, so nothing to check")
            !p.pcOnline() -> CheckLine("Your PC's model", Result.Skipped, "Your PC is not answering")
            else -> when (val a = ask.value) {
                null -> CheckLine("Your PC's model", Result.Skipped, "Your PC isn't set to answer chats")
                else -> if (a.answered) CheckLine("Your PC's model", Result.Good, "Answered a test in ${words(a.reachedMs)}")
                else CheckLine("Your PC's model", Result.Problem, "Didn't answer", "Check Settings › Who answers on your PC")
            }
        }
    }

    private fun voice(p: Probes) = safe("Ingrid's voice", "Close other apps and try again") {
        if (p.voiceLoads()) CheckLine("Ingrid's voice", Result.Good, "Loads")
        else CheckLine("Ingrid's voice", Result.Problem, "Didn't load", "Close other apps and try again")
    }

    private fun lookup(p: Probes, d: String) = safe("Looking things up", "Check $d has internet") {
        when {
            !p.lookupsOn() -> CheckLine("Looking things up", Result.Skipped, "Turned off, so not checked")
            p.lookup() -> CheckLine("Looking things up", Result.Good, "Works")
            else -> CheckLine("Looking things up", Result.Problem, "Not working", "Check $d has internet")
        }
    }

    private fun storage(p: Probes, d: String) = safe("Space on $d", "Free some space in Settings › Storage") {
        val free = p.freeBytes()
        if (free >= LOW_BYTES) CheckLine("Space on $d", Result.Good, "${gb(free)} free")
        else CheckLine("Space on $d", Result.Problem, "Only ${gb(free)} free", "Free some space in Settings › Storage")
    }

    private fun version(p: Probes) = CheckLine("App version", Result.Good, p.version())

    fun words(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
    private fun gb(b: Long): String = "%.1f GB".format(java.util.Locale.ROOT, b / 1_000_000_000.0)
}
