package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.standalone.Catalog
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.Fit
import io.github.odnwks.mimir.standalone.LocalModel
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.ModelSearch
import io.github.odnwks.mimir.standalone.ModelStatus
import io.github.odnwks.mimir.standalone.S25Results
import io.github.odnwks.mimir.standalone.TestResult
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the Model Store's buttons do to the models on this phone. */
interface PhoneModelActions {
    fun download(id: String)
    fun cancelDownload()
    fun removePartial(id: String)
    fun delete(id: String)
    fun test(id: String)
    fun cancelTest()
    /** Your own models: the folder the owner chose (its address), looking in it again, and using one of its files. */
    fun setOwnFolder(uri: String) {}
    fun lookAgain() {}
    fun useOwn(docId: String) {}
}

/** This phone's memory in bytes (0: unknown). */
internal fun phoneMemory(context: Context): Long = runCatching {
    ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem
}.getOrDefault(0L)

/** The engine as the Model Store sees it, with this phone's memory. */
fun Engine.storeModels(context: Context): PhoneModels {
    val e = this
    val mem = phoneMemory(context)
    return PhoneModels(e.state, e::choose, object : PhoneModelActions {
        override fun download(id: String) = e.download(id)
        override fun cancelDownload() = e.cancelDownload()
        override fun removePartial(id: String) = e.removePartial(id)
        override fun delete(id: String) = e.delete(id)
        override fun test(id: String) = e.test(id)
        override fun cancelTest() = e.cancelTest()
        override fun setOwnFolder(uri: String) = e.setOwnFolder(uri)
        override fun lookAgain() = e.lookAgain()
        override fun useOwn(docId: String) = e.useOwn(docId)
    }, mem)
}

/**
 * The Model Store's first page for this phone: what this phone can run, "Found for this phone" with
 * a Refresh button (a ready-made search, only when tapped), then a card per model, best pick first,
 * with its plain name, what it's good at, tags, and one button: Get, On this phone, or Too big.
 * A tap opens its details (full name, source, tests, Use, Delete).
 */
@Composable
internal fun PhoneStore(phone: PhoneModels?, nav: Navigator, paired: Boolean = false) {
    val s = phone?.state?.collectAsStateWithLifecycle()?.value
    val search = ModelSearch.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val tap = LocalTapTracker.current
    val ram = remember { phone?.phoneBytes?.takeIf { it > 0 } ?: phoneMemory(context) }
    // A phone that can't run a model doesn't see models it can't run: it sees free online ones instead.
    if (s?.supported != true) { OnlineStore(canRun = false, ramBytes = ram, nav = nav, paired = paired); return }
    val free = remember(search.lastSearched, s?.have) { runCatching { context.filesDir.usableSpace }.getOrDefault(0L) }
    if (ram > 0) PhoneCard(ram, free)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("FOUND FOR THIS PHONE", style = Type.Small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp), color = Palette.TextDim, modifier = Modifier.weight(1f).semantics { heading() })
        if (ram > 0) RefreshPill(search.searching) { ModelSearch.refresh(ModelSearch.Phone(ram, free)) }
    }
    if (search.searching) {
        Text("Looking for models…", style = Type.Small, color = Palette.TextSoft)
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Palette.BlueLight, trackColor = Palette.Track)
    }
    search.message?.let { Text(it, style = Type.Small, color = if (search.error) Palette.AmberText else Palette.TextSoft) }
    val installed = Catalog.models.filter { s != null && it.id in s.have }
    val all = Catalog.models
    val fresh = all.filter { it.id in search.newIds }
    val rest = all - fresh.toSet()
    val ordered = fresh + (rest.filter { it in installed } + rest.filter { it !in installed }).sortedBy { fitOf(it, phone).let { f -> f == Fit.TooBig || f == Fit.NeedsMemory } }
    val recommended = ordered.firstOrNull { fitOf(it, phone).let { f -> f != Fit.TooBig && f != Fit.NeedsMemory } && it.id !in search.newIds }?.id
    ordered.forEach { lm ->
        key(lm.id) {
            StoreCard(lm, s, phone, isNew = lm.id in search.newIds, recommended = lm.id == recommended) { nav.open(Screen.ModelDetails(lm.id), tap.last) }
        }
    }
    if (Catalog.models.any { it.refusalsRemoved }) Text(
        Catalog.REFUSALS_NOTE, style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
    )
    Text(
        "Last searched ${ModelSearch.lastSearchedWords(search.lastSearched)}. Tap Refresh to look again.",
        style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp),
    )
    Text("Tap a model for its full name and where it comes from.", style = Type.Small, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
    if (phone != null && s != null) OwnModelsSection(s, phone)
    OnlineStore(canRun = true, ramBytes = ram, nav = nav, paired = paired)
}

/** "This phone: 12 GB memory, 80 GB free. It can run models up to about 5.5 GB." */
@Composable
private fun PhoneCard(ramBytes: Long, freeBytes: Long) {
    Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp) {
        Text(
            "This phone: %.0f GB memory, %.0f GB free.".format(ramBytes / 1e9, freeBytes / 1e9),
            style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text,
        )
        Text("It can run models up to about ${ModelSearch.limitWords(ramBytes)} GB.", style = Type.Small, color = Palette.TextSoft)
    }
}

/** The outlined "Refresh" pill; while a search runs it says so and does nothing. */
@Composable
private fun RefreshPill(searching: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).border16(Palette.Outline, 24)
            .clickable(enabled = !searching, role = Role.Button, onClickLabel = "Look for new models", onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (searching) "Looking…" else "\u21BB Refresh", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = if (searching) Palette.TextDim else Palette.Text) }
}

/** The memory a model needs, in whole GB (the rule of [Fit.of] turned round). */
private fun needGb(lm: LocalModel): Int =
    if (lm.minRamGb > 0) lm.minRamGb else Math.ceil((lm.totalBytes * 1.05 + 300_000_000) / 0.55 / 1e9).toInt()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreCard(lm: LocalModel, s: LocalState?, phone: PhoneModels?, isNew: Boolean, recommended: Boolean, open: () -> Unit) {
    val inUse = s != null && lm.id == s.model && lm.id in s.have
    val shape = RoundedCornerShape(16.dp)
    val reduce = LocalReduceMotion.current
    val fit = fitOf(lm, phone)
    val tooBig = fit == Fit.TooBig || fit == Fit.NeedsMemory
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (inUse) Palette.BlueCard else Palette.Card)
            .border16(if (inUse) Palette.BlueBorder else Palette.Line)
            .then(if (reduce) Modifier else Modifier.animateContentSize(tween(220)))
            .alpha(if (tooBig && s?.status?.get(lm.id) == null) 0.6f else 1f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Details of ${lm.plainName}", onClick = open),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(lm.plainName, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                Text(lm.note, style = Type.Small, color = Palette.TextSoft)
                StoreTags(lm, s, inUse, isNew = isNew, recommended = recommended, fits = !tooBig && fit == Fit.Easily)
                if (tooBig) Text("Needs ${needGb(lm)} GB of memory. Your PC can run this one instead.", style = Type.Small, color = Palette.AmberText)
            }
            Icon(Glyphs.Next, null, tint = Palette.TextDim, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        }
        s?.tests?.get(lm.id)?.let { Verdict(it) }
        if (s != null && phone?.actions != null) CardButton(lm, s, phone, fit)
    }
}

/**
 * The one button on a card: Get, "On this phone", or "Too big". A model with something going on
 * (downloading, stopped, failing, out of date, being tested) shows [StoreButtons] with its progress.
 */
@Composable
private fun ColumnScope.CardButton(lm: LocalModel, s: LocalState, phone: PhoneModels, fit: Fit) {
    val act = phone.actions ?: return
    val st = s.status[lm.id]
    val busy = st is ModelStatus.Downloading || st is ModelStatus.Checking || st is ModelStatus.Paused || st is ModelStatus.Failed ||
        st == ModelStatus.Outdated || s.testing?.id == lm.id
    val have = lm.id in s.have
    when {
        busy -> StoreButtons(lm, s, act, fit, phone.choose)
        have -> Text("\u2713 On this phone", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.GreenText, modifier = Modifier.heightIn(min = 32.dp))
        fit == Fit.TooBig || fit == Fit.NeedsMemory -> BigButton("Too big", Look.Outline, {}, enabled = false, height = 48.dp)
        !s.supported -> {}
        s.status.any { (id, v) -> id != lm.id && (v is ModelStatus.Downloading || v is ModelStatus.Checking) } ->
            Text("Another model is downloading; one at a time.", style = Type.Small, color = Palette.TextDim)
        else -> BigButton("Get", Look.Primary, { act.download(lm.id) }, height = 48.dp, description = "Get ${lm.plainName}, ${lm.sizeText}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreTags(lm: LocalModel, s: LocalState?, inUse: Boolean, isNew: Boolean = false, recommended: Boolean = false, fits: Boolean = false) {
    val have = s != null && lm.id in s.have
    FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isNew) Tag("New", Palette.White, Palette.Blue)
        if (recommended) Tag("Best fit", Palette.BlueText, Palette.BlueIcon)
        if (fits) Tag("Fits well", Palette.GreenText, Palette.GreenCard)
        Tag(lm.sizeText, Palette.TextSoft, Palette.Track)
        if (have) Tag("Installed", Palette.GreenText, Palette.GreenCard)
        if (inUse) Tag("In use", Palette.BlueText, Palette.BlueIcon)
        lm.chip?.let { Tag(it, Palette.AmberText, Palette.Track) }
    }
}

private fun fitOf(lm: LocalModel, phone: PhoneModels?) = Fit.of(lm.totalBytes, phone?.phoneBytes ?: 0, lm.minRamGb)

/** "Tested 18 Nov: Good for chat on this phone · 11 words a second · loads in 5.7 s". */
@Composable
private fun Verdict(t: TestResult) {
    Text(
        "Tested ${dayWords(t.at)}: ${t.verdict}", style = Type.Small.copy(fontWeight = FontWeight.SemiBold),
        color = if (t.good) Palette.GreenText else Palette.AmberText,
    )
}

private fun dayWords(ms: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))

/**
 * A card's buttons, from where the model stands: Download (with progress and Cancel), Resume a
 * stopped one, Update when a newer file is out (it replaces the old one), Test on this device
 * (with progress and Cancel), and Delete with one confirmation saying how much space it frees.
 * Only what works right now is shown.
 */
@Composable
private fun ColumnScope.StoreButtons(lm: LocalModel, s: LocalState, act: PhoneModelActions, fit: Fit, choose: (String) -> Unit) {
    if (!s.supported) return
    val st = s.status[lm.id]
    val otherDownload = s.status.any { (id, v) -> id != lm.id && (v is ModelStatus.Downloading || v is ModelStatus.Checking) }
    var sure by rememberSaveable(lm.id) { mutableStateOf(false) }
    val testing = s.testing
    when {
        st is ModelStatus.Downloading || st is ModelStatus.Checking -> {
            val (done, total, words) = when (st) {
                is ModelStatus.Downloading -> Triple(st.done, st.total, "Downloading · %.1f of %.1f GB".format(st.done / 1e9, st.total / 1e9))
                is ModelStatus.Checking -> Triple(st.done, st.total, "Checking the file · %.0f%%".format(st.done * 100.0 / st.total))
                else -> Triple(0L, 1L, "")
            }
            Progress(words, done.toFloat() / total.coerceAtLeast(1))
            BigButton("Cancel download", Look.Outline, act::cancelDownload, height = 48.dp)
            return
        }
        testing?.id == lm.id -> {
            Progress("Testing · ${testing.words}", (testing.step + 0.5f) / testing.steps)
            BigButton("Stop the test", Look.Outline, act::cancelTest, height = 48.dp)
            return
        }
        st is ModelStatus.Paused -> {
            Text("Stopped at %.1f of %.1f GB".format(st.done / 1e9, st.total / 1e9), style = Type.Small, color = Palette.TextSoft)
            if (!otherDownload) BigButton("Resume download", Look.Primary, { act.download(lm.id) }, height = 48.dp)
            BigButton("Remove the part download (%.1f GB)".format(st.done / 1e9), Look.Outline, { act.removePartial(lm.id) }, height = 48.dp)
            return
        }
    }
    val have = lm.id in s.have
    if (!have) {
        if (st is ModelStatus.Failed) Text("The download stopped. ${st.why}", style = Type.Small, color = Palette.AmberText)
        when {
            fit == Fit.TooBig || fit == Fit.NeedsMemory -> {}
            otherDownload -> Text("Another model is downloading; one at a time.", style = Type.Small, color = Palette.TextDim)
            else -> BigButton(
                if (st is ModelStatus.Failed) "Try again (${lm.sizeText})" else "Download (${lm.sizeText})", Look.Primary,
                { act.download(lm.id) }, height = 48.dp, description = "Download ${lm.plainName}, ${lm.sizeText}",
            )
        }
        return
    }
    if (st == ModelStatus.Outdated && !otherDownload) BigButton(
        "Update (${lm.sizeText}, replaces the old file)", Look.Primary, { act.download(lm.id) }, height = 48.dp,
        description = "Update ${lm.plainName}",
    )
    if (sure) {
        Text("Delete ${lm.plainName}? This frees ${lm.sizeText}. You can download it again later.", style = Type.Small, color = Palette.TextSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Delete", Look.Danger, { act.delete(lm.id); sure = false }, Modifier.weight(1f), height = 48.dp, description = "Yes, delete ${lm.plainName}")
            BigButton("Keep", Look.Outline, { sure = false }, Modifier.weight(1f), height = 48.dp)
        }
        return
    }
    val busy = s.writing || testing != null
    // One model is kept: the one in use. Another one downloaded here waits to be picked.
    if (lm.id != s.model && !busy) {
        BigButton("Use this one", Look.Primary, { choose(lm.id) }, height = 48.dp, description = "Use ${lm.plainName}")
        if (s.model in s.have) Text(
            "Only one model is kept: once this one is used, ${Catalog.byId(s.model).plainName} is removed to free its space.",
            style = Type.Small, color = Palette.TextDim,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!busy) BigButton("Test on this phone", Look.Quiet, { act.test(lm.id) }, Modifier.weight(1f), height = 48.dp, description = "Test ${lm.plainName} on this phone")
        if (!(s.writing && s.model == lm.id)) BigButton("Delete", Look.Outline, { sure = true }, Modifier.weight(1f), height = 48.dp, description = "Delete ${lm.plainName}")
    }
}

/** A line of words over a bar that moves smoothly to [fraction]. */
@Composable
internal fun Progress(words: String, fraction: Float) {
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduce) snap() else tween(400), label = "progress")
    Text(words, style = Type.Small, color = Palette.TextSoft)
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = Palette.BlueLight, trackColor = Palette.Track, drawStopIndicator = {},
    )
}

/**
 * A model's details page: what it is and what it's good at, its size and licence, whether it fits
 * this phone, what we measured on the S25, your own test's numbers, and the same buttons as its
 * card, plus Chat with it once it's here.
 */
@Composable
fun ModelDetailsScreen(id: String, nav: Navigator, phone: PhoneModels?) {
    val lm = Catalog.models.firstOrNull { it.id == id }
    val s = phone?.state?.collectAsStateWithLifecycle()?.value
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(lm?.plainName ?: "Model", nav::back, backLabel = "Back", subtitle = lm?.details)
        if (lm == null) {
            EmptyState("This model is no longer offered.")
            return@Column
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(spacing = 6.dp) {
                Text(lm.note, style = Type.Label, color = Palette.TextSoft)
                StoreTags(lm, s, s != null && lm.id == s.model && lm.id in s.have)
                Fact("Model", lm.name.substringBefore(','))
                Fact("Size", lm.sizeText)
                Fact("Fits", fitOf(lm, phone).words)
                Fact("Licence", "Apache 2.0")
                if (lm.refusalsRemoved) Text(Catalog.REFUSALS_NOTE, style = Type.Small, color = Palette.TextDim)
                if (lm.thinking) Text(Catalog.THINKING_NOTE, style = Type.Small, color = Palette.TextDim)
            }
            Panel(spacing = 6.dp) {
                Text("Our tests", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
                Text(S25Results.of(lm.id) ?: "Not tested by us yet.", style = Type.Small, color = Palette.TextSoft)
            }
            Panel(spacing = 6.dp) {
                Text("On this phone", style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, modifier = Modifier.semantics { heading() })
                val t = s?.tests?.get(lm.id)
                if (t == null) Text(
                    "Not tested here yet. Testing asks it a few everyday questions" +
                        (if (lm.refusalsRemoved) " and one that filtered models refuse" else "") +
                        ", and measures its speed and how warm the phone gets. It takes about a minute and keeps only the numbers.",
                    style = Type.Small, color = Palette.TextSoft,
                ) else {
                    Verdict(t)
                    t.numbers.forEach { Text(it, style = Type.Small, color = Palette.TextSoft) }
                }
                if (s != null && phone.actions != null) StoreButtons(lm, s, phone.actions, fitOf(lm, phone), phone.choose)
                if (s != null && lm.id in s.have && s.testing == null) BigButton(
                    "Chat with it", Look.Primary, { phone.choose(lm.id); nav.open(Screen.Standalone) }, height = 48.dp,
                )
            }
        }
    }
}

@Composable
private fun Fact(name: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = Type.Small, color = Palette.TextDim, modifier = Modifier.weight(0.35f))
        Text(value, style = Type.Small, color = Palette.Text, modifier = Modifier.weight(0.65f))
    }
}
