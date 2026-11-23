package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.adaptive.forDevice
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.online.KeyTest
import io.github.odnwks.mimir.online.KeyVault
import io.github.odnwks.mimir.online.OnlineModels
import io.github.odnwks.mimir.online.Service
import io.github.odnwks.mimir.online.Setup
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The words of the free-online-models part of the store and its setup (the redesign, screens 20 and 21). */
const val ONLINE_HEADING = "FREE ONLINE MODELS"
const val ONLINE_STILL_CHAT = "You can still chat."
const val ONLINE_STILL_CHAT_MORE = " Connect your PC, or use a free online model."
const val ONLINE_OPENROUTER_ABOUT = "Many free models in one place. No card needed."
const val ONLINE_GROQ_ABOUT = "Very fast answers. Free with a daily limit."
const val ONLINE_OWN_TITLE = "Another service"
const val ONLINE_OWN_ABOUT = "Have a key from somewhere else? Paste it and its address."
const val ONLINE_KNOW = "Know before you choose:"
const val ONLINE_KNOW_MORE = " with an online model, your messages go to that company. With a PC or a phone model, they never do."
const val ONLINE_CONNECT_PC = "Connect your PC with Mímir"
const val ONLINE_STEP1 = "Get a free key."
const val ONLINE_STEP1_MORE = " Their website opens, you sign up, and copy the key it gives you."
const val ONLINE_STEP2 = "Paste it here."
const val ONLINE_WHICH = "WHICH ONE SHOULD ANSWER?"
const val ONLINE_BEST = "Best free one right now"
const val ONLINE_BEST_ABOUT = "Mímir picks the best free model and switches if it stops being free."
const val ONLINE_CHOOSE = "Let me choose"

fun onlineSetupTitle(service: String) = "Set up $service"
fun tooLittleMemory(ramBytes: Long) =
    if (ramBytes > 0) "This phone: %.0f GB memory. That's too little to run a model by itself.".format(ramBytes / 1e9)
    else "This phone can't run a model by itself."
const val CLIPBOARD_CLEARED = "Copied key removed from the clipboard."
const val KEY_SETUP_AGAIN = "Your key needs to be set up again."
fun keySentTo(host: String) = "Your key will be sent to $host"
fun keyWorks(n: Int, free: Boolean) = "✓ The key works. $n ${if (free) "free " else ""}model${if (n == 1) "" else "s"} found."

/** A pasted key as it is kept: no spaces or line breaks, and no "Bearer " in front. */
fun cleanKey(raw: String): String = raw.trim().removePrefix("Bearer ").removePrefix("bearer ").filter { !it.isWhitespace() }

/** The address as typed for "Another service": spaces and a closing slash are dropped. */
fun cleanAddress(raw: String): String = raw.trim().trimEnd('/')

/** Shows a long key as its first 5 and last 4 characters, so it is never on screen in full. */
private object KeyMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = KeyVault.mask(text.text)
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset.coerceAtMost(shown.length)
            override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(text.length)
        })
    }
}

/** What the part of the store for online models is, from the phone's side. */
@Composable
internal fun OnlineStore(canRun: Boolean, ramBytes: Long, nav: Navigator, paired: Boolean = false) {
    val ctx = LocalContext.current
    remember { OnlineModels.ensure(ctx.applicationContext.filesDir) }
    val st = OnlineModels.state.collectAsStateWithLifecycle().value
    var setting by rememberSaveable { mutableStateOf<String?>(null) }
    if (!canRun) {
        Panel(background = Palette.Bar, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 6.dp) {
            Text(tooLittleMemory(ramBytes).forDevice(), style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Palette.Text)); append(ONLINE_STILL_CHAT); pop()
                append(ONLINE_STILL_CHAT_MORE.forDevice())
            },
            style = Type.Body, color = Palette.TextSoft, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (!canRun && !paired) Text(
        ONLINE_CONNECT_PC, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.BlueText,
        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { nav.open(Screen.Pair) }.padding(horizontal = 4.dp, vertical = 12.dp),
    )
    Text(ONLINE_HEADING, style = Type.Small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp), color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
    ServiceCard(OnlineModels.OPENROUTER, ONLINE_OPENROUTER_ABOUT, listOf("Free"), st.setups.firstOrNull { it.id == "openrouter" }, "openrouter" in st.broken, "Set up") { setting = "openrouter" }
    ServiceCard(OnlineModels.GROQ, ONLINE_GROQ_ABOUT, listOf("Free"), st.setups.firstOrNull { it.id == "groq" }, "groq" in st.broken, "Set up") { setting = "groq" }
    ServiceCard(null, ONLINE_OWN_ABOUT, emptyList(), st.setups.firstOrNull { it.id == "own" }, "own" in st.broken, "Add") { setting = "own" }
    Panel(background = Palette.AmberCard, border = Palette.AmberBorder, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(ONLINE_KNOW); pop()
                append(ONLINE_KNOW_MORE.forDevice())
            },
            style = Type.Label, color = Palette.AmberText,
        )
    }
    setting?.let { id ->
        OnlineSetupDialog(
            service = when (id) { "openrouter" -> OnlineModels.OPENROUTER; "groq" -> OnlineModels.GROQ; else -> null },
            canRun = canRun, onClose = { setting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceCard(service: Service?, about: String, tags: List<String>, done: Setup?, needsAgain: Boolean, action: String, onSet: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val name = service?.name ?: ONLINE_OWN_TITLE
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (done != null) Palette.BlueCard else Palette.Card).border(1.dp, if (done != null) Palette.BlueBorder else Palette.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (done != null && service == null) "${done.name} (your own)" else name, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
        Text(about, style = Type.Small, color = Palette.TextSoft)
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { Tag(it, if (it == "Free") Palette.GreenText else Palette.BlueText, if (it == "Free") Palette.GreenCard else Palette.BlueIcon) }
        }
        if (needsAgain) Text(KEY_SETUP_AGAIN, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.AmberText)
        if (done == null) BigButton(action, Look.Primary, onSet, height = 48.dp, description = if (service != null) "Set up $name" else "Add another service")
        else {
            Text("✓ Set up · ${KeyVault.mask(done.key)}", style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.GreenText)
            Text(done.model?.let { "Answers with ${shortModel(it)}" } ?: "Picks the best free model", style = Type.Small, color = Palette.TextSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Change", Look.Outline, onSet, Modifier.weight(1f), height = 48.dp, description = "Change $name")
                BigButton("Remove key", Look.Danger, { OnlineModels.remove(done.id) }, Modifier.weight(1f), height = 48.dp, description = "Remove the key for $name")
            }
        }
    }
}

/** What the key test says right now. */
private sealed interface Shown {
    data object Nothing : Shown
    data object Testing : Shown
    data class Works(val models: List<String>) : Shown
    data class Bad(val words: String) : Shown
}

/** Screen 21: get a free key, paste it, see it tested, pick who answers, Done. [service] null: the owner's own service. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnlineSetupDialog(service: Service?, canRun: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    // The key is never kept in saved state (it would sit in the system's saved-state Bundle): it lives only here, and a turn of the phone asks for it again.
    var key by remember { mutableStateOf("") }
    var cleared by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Shown>(Shown.Nothing) }
    var auto by rememberSaveable { mutableStateOf(true) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val own = service == null
    val target = service ?: OnlineModels.own(cleanAddress(address))
    val title = if (own) "Add another service" else onlineSetupTitle(service!!.name)
    val scope = rememberCoroutineScope()
    suspend fun check() {
        if (own && !cleanAddress(address).startsWith("https://")) { shown = Shown.Bad("The address must start with https://"); return }
        shown = Shown.Testing
        // The owner's own address may be used only from here on, for this one check; it is forgotten again if the key fails.
        if (own) io.github.odnwks.mimir.online.Net.allowAnswerHost(target.name)
        shown = when (val r = withContext(Dispatchers.IO) { OnlineModels.test(target, key) }) {
            is KeyTest.Works -> Shown.Works(OnlineModels.ranked(r.models))
            KeyTest.BadKey -> Shown.Bad(OnlineModels.BAD_KEY)
            KeyTest.Away -> Shown.Bad(OnlineModels.away(target.name))
            is KeyTest.Other -> Shown.Bad(r.words)
        }
        if (own && shown is Shown.Bad && OnlineModels.setup("own")?.base?.let { b -> runCatching { java.net.URL(b).host }.getOrNull() } != target.name) io.github.odnwks.mimir.online.Net.forgetAnswerHost(target.name)
    }
    // OpenRouter and Groq: the key is tested as soon as it's pasted (or typed far enough). The owner's own address: only when "Check key" is tapped, after the address was shown.
    LaunchedEffect(key, address) {
        shown = Shown.Nothing
        if (own || key.length < 12) return@LaunchedEffect
        delay(500)
        check()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().background(Palette.Ground).statusBarsPadding().windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            BackHeader(title, onClose, backLabel = "Back")
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!own) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Palette.Text)); append("1  $ONLINE_STEP1"); pop()
                            append(ONLINE_STEP1_MORE)
                        },
                        style = Type.Body, color = Palette.TextSoft,
                    )
                    BigButton("Open their website", Look.Outline, { openWebsite(ctx, service!!.keysUrl!!) }, height = 48.dp)
                } else {
                    Text("The address", style = Type.Body.copy(fontWeight = FontWeight.Bold), color = Palette.Text)
                    Text("The web address of the service, such as https://example.com/v1", style = Type.Small, color = Palette.TextSoft)
                    MessageBox(address, { address = cleanAddress(it) }, "Service address", "https://", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 2)
                }
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Palette.Text)); append("${if (own) "" else "2  "}$ONLINE_STEP2"); pop()
                    },
                    style = Type.Body, color = Palette.TextSoft,
                )
                KeyField(key, tested = shown is Shown.Works || shown is Shown.Bad, onKey = { key = cleanKey(it); cleared = false }, onPaste = {
                    val got = cleanKey(pasted(ctx))
                    key = got
                    // A key that was copied is taken off the clipboard at once, so no other app can read it from there.
                    if (got.isNotEmpty()) { cleared = clearClipboard(ctx) }
                })
                if (cleared) Text(CLIPBOARD_CLEARED, style = Type.Small, color = Palette.TextSoft)
                if (own && key.length >= 12 && shown !is Shown.Testing && shown !is Shown.Works) {
                    val host = runCatching { java.net.URL(cleanAddress(address)).host }.getOrDefault("").ifEmpty { null }
                    if (host != null && cleanAddress(address).startsWith("https://")) {
                        Text(keySentTo(host), style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.AmberText)
                        BigButton("Check key", Look.Outline, { scope.launch { check() } }, height = 48.dp, description = "Check the key with $host")
                    } else Text("Type the address first. It must start with https://", style = Type.Small, color = Palette.TextDim)
                }
                when (val r = shown) {
                    Shown.Nothing -> {}
                    Shown.Testing -> Text("Checking the key…", style = Type.Label, color = Palette.TextSoft)
                    is Shown.Works -> Text(keyWorks(r.models.size, !own), style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.GreenText, modifier = Modifier.semantics { contentDescription = keyWorks(r.models.size, !own) })
                    is Shown.Bad -> Text(r.words, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Red)
                }
                val works = shown as? Shown.Works
                if (works != null) {
                    Text(ONLINE_WHICH, style = Type.Small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp), color = Palette.TextDim, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                    Pick(ONLINE_BEST, ONLINE_BEST_ABOUT, auto) { auto = true }
                    Pick(ONLINE_CHOOSE, null, !auto) { auto = false; if (chosen == null) chosen = works.models.firstOrNull() }
                    if (!auto) works.models.take(40).forEach { m -> Pick(shortModel(m), null, chosen == m, small = true) { chosen = m } }
                }
                Text(OnlineModels.KEPT, style = Type.Small, color = Palette.TextDim)
                BigButton(
                    "Done", Look.Primary,
                    {
                        val w = works ?: return@BigButton
                        val model = if (auto) OnlineModels.best(w.models) else chosen
                        val s = Setup(target.id, target.name, target.base, key, model, auto)
                        OnlineModels.save(s)
                        // A phone that can't run a model answers with it at once; one that can keeps its own way until the owner picks.
                        if (!canRun) OnlineModels.answerWith(s.id)
                        onClose()
                    },
                    enabled = works != null, height = 52.dp,
                )
            }
        }
    }
}

@Composable
private fun KeyField(key: String, tested: Boolean, onKey: (String) -> Unit, onPaste: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(Palette.Bar).border(1.dp, Palette.Outline, shape).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (key.isEmpty()) Text("Paste your key here", style = Type.Body, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Once tested the key is only ever shown hidden (a text field would give the whole key to a screen reader); a tap clears it to type again.
            if (tested && key.isNotEmpty()) Text(
                KeyVault.mask(key), style = Type.Body, color = Palette.Text, maxLines = 1,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Clear the key to type another") { onKey("") }.padding(vertical = 12.dp)
                    .semantics { contentDescription = "Your key, hidden: ${KeyVault.mask(key)}" },
            ) else BasicTextField(
                key, onKey, singleLine = true, textStyle = Type.Body.copy(color = Palette.Text), cursorBrush = SolidColor(Palette.BlueLight),
                visualTransformation = KeyMask, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your key" },
            )
        }
        Box(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Blue).clickable(role = Role.Button, onClick = onPaste).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Paste", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.White) }
    }
}

@Composable
private fun Pick(title: String, about: String?, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (on) Palette.BlueCard else Palette.Card)
            .border(1.dp, if (on) Palette.BlueBorder else Palette.Line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = (if (small) Type.Small else Type.BodyTight).copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            if (about != null) Text(about, style = Type.Small, color = Palette.TextSoft)
        }
        if (on) androidx.compose.material3.Icon(Glyphs.Check, "Chosen", tint = Palette.BlueText)
    }
}

/** The text on the clipboard ("" when there is none, or it can't be read). */
/** Takes the copied text off the clipboard; true when it is done. */
internal fun clearClipboard(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    true
}.getOrDefault(false)

private fun pasted(ctx: Context): String = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}.getOrDefault("")

private fun openWebsite(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
