package io.github.odnwks.mimir.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The boards' own line icons (24×24, round caps), drawn from their SVG paths so they match exactly.
 * Drawn in black and tinted by Icon(tint = ...).
 */
object Glyphs {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private fun line(name: String, width: Float, vararg paths: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    private fun solid(name: String, vararg paths: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply { paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) } }.build()

    val Chat = line("chat", 2f, "M4 5h16v11H9l-5 4z")
    val Gauge = line("gauge", 2f, "M4 16a8 8 0 0 1 16 0", "M12 16l4-5")
    val Speaker = line("speaker", 2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 9a4 4 0 0 1 0 6", "M18.5 6a8 8 0 0 1 0 12")
    val SpeakerLow = line("speaker-low", 2f, "M11 5L6 9H3v6h3l5 4z", "M15.5 9a4 4 0 0 1 0 6")
    /** Settings: a cog. */
    val Settings = line(
        "settings", 2f,
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
    )
    val Back = line("back", 2f, "M15 18l-6-6 6-6")
    /** Search: a magnifier. */
    val Search = line("search", 2f, "M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0z", "M15.5 15.5L20 20")
    /** Storage: two stacked drives. */
    val Storage = line("storage", 2f, "M4 5h16v6H4z", "M4 13h16v6H4z", "M8 8h.01", "M8 16h.01")
    val Next = line("chevron", 2f, "M9 6l6 6-6 6")
    val Down = line("down", 2f, "M6 9l6 6 6-6")
    val Up = line("up", 2f, "M6 15l6-6 6 6")
    val NextBold = line("chevron-bold", 2.5f, "M9 6l6 6-6 6")
    val Alert = line("alert", 2.2f, circle(12f, 12f, 9f), "M12 7v6M12 16.5v.5")
    val Play = solid("play", "M7 4l13 8-13 8z")
    /** Three dots: a menu for the thing beside it. */
    val More = line("more", 3.5f, "M5 12h.01M12 12h.01M19 12h.01")
    val Pause = solid("pause", rect(6f, 5f, 4f, 14f, 1f), rect(14f, 5f, 4f, 14f, 1f))
    val Check = line("check", 2.5f, "M5 12l5 5 9-10")
    val CheckBold = line("check-bold", 2.8f, "M5 12l5 5 9-10")
    val Minus = line("minus", 2.8f, "M7 12h10")
    val Mic = line("mic", 2f, rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3")
    /** The menu button: two lines, the second shorter. */
    val Menu = line("menu", 2f, "M4 8h16M4 16h11")
    /** New chat: a pencil. */
    val NewChat = line("new-chat", 2f, "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Plus = line("plus", 2f, "M12 5v14M5 12h14")
    val Send = line("send", 2f, "M5 12h13M13 6l6 6-6 6")
    val SkipNext = line("skip-next", 2f, "M5 5l10 7-10 7z", "M19 5v14")
    val Back10 = line("back-10", 2f, "M4 12a8 8 0 1 0 3-6.2", "M4 4v4h4")
    val Options = solid("options", circle(12f, 5f, 2f), circle(12f, 12f, 2f), circle(12f, 19f, 2f))
    val Warning = line("warning", 2.2f, "M12 3l10 18H2z", "M12 10v4M12 17.5v.5")
    val Shield = line("shield", 2f, "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z")
    val Stop = line("stop", 2f, rect(6f, 6f, 12f, 12f, 2f))
    val Steps = line("steps", 2f, "M8 6h12M8 12h12M8 18h12M4 6h.5M4 12h.5M4 18h.5")
    val Close = line("close", 2f, "M6 6l12 12M18 6L6 18")
    val Document = line("document", 2f, "M6 3h9l3 3v15H6z", "M9 12h6M9 16h6")
    val NewDocument = line("new-document", 2f, "M6 3h9l3 3v15H6z", "M9 12h6M12 9v6")
    val Move = line("move", 2f, "M4 7h11l-3-3M20 17H9l3 3")
    val Question = line("question", 2f, circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6M12 17v.5")
    val Clock = line("clock", 2f, circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Info = line("info", 2f, circle(12f, 12f, 9f), "M12 11v6M12 7.5v.5")
    val Moon = line("moon", 2f, "M20 14A8 8 0 1 1 10 4a7 7 0 0 0 10 10z")
    /** The closed eye of a Whisper: a downward arc with three lashes. */
    val EyeClosed = line("eye-closed", 2f, "M3.5 9.5C6 14 9 15.5 12 15.5s6-1.5 8.5-6", "M7 14l-1.8 2.6M12 15.5v3.3M17 14l1.8 2.6")
    val Bars = solid("bars", rect(4f, 9f, 3f, 10f, 1f), rect(10.5f, 4f, 3f, 15f, 1f), rect(17f, 12f, 3f, 7f, 1f))

    // More's rows (paths from the More board).
    val Models = line("models", 2f, "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5")
    val PhonePc = line("phone-pc", 2f, "M3 5h12v9H3zM7 18h4M9 14v4M17 8h4v12h-4z")
    val Lock = line("lock", 2f, "M5 11h14v10H5zM8 11V8a4 4 0 0 1 8 0v3")
    /** Where an answer ran: the PC (a monitor) or this phone. */
    val Monitor = line("monitor", 2f, "M3 4h18v12H3zM8 20h8M12 16v4")
    val Phone = line("phone", 2f, "M7 2h10v20H7zM11 18h2")
    val Bell = line("bell", 2f, "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4")
    val Voice = line("voice", 2f, "M9 3h6v11H9zM5 11a7 7 0 0 0 14 0M12 18v3")
    val Folder = line("folder", 2f, "M3 7h7l2 2h9v10H3z")
    val Look = line("look", 2f, "M12 3a9 9 0 1 0 0 18zM12 3a9 9 0 0 1 0 18")
    val About = line("about", 2f, "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18M12 11v6M12 7.5v.5")
}
