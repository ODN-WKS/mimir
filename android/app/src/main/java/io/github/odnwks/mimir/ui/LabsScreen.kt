package io.github.odnwks.mimir.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odnwks.mimir.devlog.DevLogRunner
import io.github.odnwks.mimir.labs.CheckLine
import io.github.odnwks.mimir.labs.Checks
import io.github.odnwks.mimir.labs.GithubLink
import io.github.odnwks.mimir.labs.LabsProbes
import io.github.odnwks.mimir.labs.LabsReport
import io.github.odnwks.mimir.labs.Report
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.Type

const val LABS_TITLE = "Labs"
const val LABS_LINE = "Check things and report a problem"
const val LABS_WORDS = "labs bug report problem troubleshoot log checks something wrong broken crash github"
const val LABS_CHECKS_HEAD = "RUN CHECKS"
const val LABS_REPORT_HEAD = "REPORT A PROBLEM"
const val LABS_REPORT_HINT = "What happened? What did you expect?"
const val LABS_GITHUB_NOTE = "Mímir never sends this itself. You send it on GitHub, which needs a free account."

/** The checks last run, kept while the app is open, so a report can include them. */
object LastChecks {
    @Volatile var lines: List<CheckLine>? = null
}

/** Settings › Help and about › Labs: the notes switch, Run checks, and Report a problem. */
@Composable
fun LabsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    var checks by remember { mutableStateOf(LastChecks.lines) }
    var running by remember { mutableStateOf(false) }
    // Each line shows as soon as its check is done.
    val partial = remember { androidx.compose.runtime.mutableStateListOf<CheckLine?>() }
    val thisDevice = io.github.odnwks.mimir.ui.adaptive.LocalDevice.current.thisDevice
    var what by rememberSaveable { mutableStateOf("") }
    var withChecks by rememberSaveable { mutableStateOf(true) }
    var withErrors by rememberSaveable { mutableStateOf(true) }
    val logOn = DevLogRunner.enabled(ctx)
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(LABS_TITLE, nav::back, backLabel = "Back")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CapsLabel("TROUBLESHOOT")
            DeveloperLogRows()
            CapsLabel(LABS_CHECKS_HEAD)
            Panel(spacing = 10.dp) {
                val list = checks
                if (list == null && !running) Text("Looks at your PC, its model, Ingrid's voice, lookups and space.", style = Type.Small, color = Palette.TextDim)
                (if (running) partial.filterNotNull() else list.orEmpty()).forEach { CheckRow(it) }
                if (running) Text("Checking… a few seconds.", style = Type.Small, color = Palette.TextDim)
                BigButton(
                    if (list == null) "Run checks" else "Run checks again", Look.Outline,
                    {
                        if (!running) {
                            running = true
                            partial.clear(); repeat(6) { partial.add(null) }
                            Thread({
                                val got = runCatching { Checks.run(LabsProbes(ctx), thisDevice) { i, line -> if (i < partial.size) partial[i] = line } }.getOrDefault(emptyList())
                                LastChecks.lines = got
                                checks = got
                                running = false
                            }, "labs-checks").start()
                        }
                    },
                    height = 48.dp, enabled = !running,
                )
            }
            CapsLabel(LABS_REPORT_HEAD)
            Panel(spacing = 10.dp) {
                ReportBox(what) { what = it }
                SwitchRow(withChecks, { withChecks = it }, Modifier.heightIn(min = 48.dp)) {
                    Text("Include the last checks", style = Type.BodyTight, color = Palette.Text)
                }
                SwitchRow(withErrors && logOn, { if (logOn) withErrors = it }, Modifier.heightIn(min = 48.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Include recent errors from the notes", style = Type.BodyTight, color = if (logOn) Palette.Text else Palette.TextDim)
                        if (!logOn) Text("Turn the notes on above first.", style = Type.Small, color = Palette.TextDim)
                    }
                }
                val text = remember(what, withChecks, withErrors && logOn, checks, running) {
                    Report.build(
                        LabsReport.facts(ctx), if (withChecks) (checks ?: emptyList()) else null,
                        if (withErrors && logOn) LabsReport.errors(ctx) else null, what,
                    )
                }
                Text("This is everything that will be in the report:", style = Type.Small, color = Palette.TextDim)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Bar)
                        .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp)
                        .semantics { contentDescription = "The whole report, scrollable" },
                ) { Text(text, style = Type.Small, color = Palette.TextSoft) }
                BigButton("Copy report", Look.Primary, { copy(ctx, text); toast(ctx, GithubLink.COPIED) }, height = 48.dp)
                BigButton(
                    "Report on GitHub", Look.Outline,
                    {
                        // The whole report is always copied first: the form's address can only carry a short part of it.
                        copy(ctx, text)
                        val facts = LabsReport.facts(ctx)
                        val url = GithubLink.build(GithubLink.titleFrom(what), what, Report.summary(facts))
                        val opened = runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        toast(ctx, if (opened) GithubLink.COPIED_PASTE else "No browser found. The report is copied.")
                    },
                    height = 48.dp,
                )
                Text(LABS_GITHUB_NOTE, style = Type.Small, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun CheckRow(c: CheckLine) {
    val color = when (c.result) { CheckLine.Result.Good -> Palette.GreenText; CheckLine.Result.Problem -> Palette.Red; CheckLine.Result.Skipped -> Palette.TextDim }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(c.mark, style = Type.BodyTight.copy(fontWeight = FontWeight.Bold), color = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name, style = Type.BodyTight.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
            Text(c.line, style = Type.Small, color = Palette.TextSoft)
            c.next?.let { Text(it, style = Type.Small, color = Palette.Amber) }
        }
    }
}

@Composable
private fun ReportBox(value: String, onValue: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape).background(Palette.Bar).border(1.dp, Palette.Outline, shape).padding(14.dp)) {
        if (value.isEmpty()) Text(LABS_REPORT_HINT, style = Type.Body, color = Palette.TextDim)
        BasicTextField(
            value, onValue, textStyle = Type.Body.copy(color = Palette.Text), cursorBrush = SolidColor(Palette.BlueLight),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = LABS_REPORT_HINT },
        )
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Mímir report", text))
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
