package io.github.odnwks.mimir.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Colours from the approved boards (docs/designs/phone-app/mockups). */
object Palette {
    val Ground = Color(0xFF0F1114)
    val GroundIncognito = Color(0xFF100E14)
    /** Whisper mode's ground (deep sea-teal, nearly black). */
    val GroundWhisper = Color(0xFF0A1214)
    val GroundSheet = Color(0xFF0B0C0E)
    val Bar = Color(0xFF16191D)
    val Card = Color(0xFF1B1E23)
    val Line = Color(0xFF2E3238)
    val LineStrong = Color(0xFF4A505A)
    val Outline = Color(0xFF7D8590)
    val Track = Color(0xFF3A3F47)
    val TrackDim = Color(0xFF5A616B)

    val Text = Color(0xFFE8EAED)
    val TextSoft = Color(0xFFC9CED4)
    val TextDim = Color(0xFFB8BEC6)
    val White = Color(0xFFFFFFFF)

    val Blue = Color(0xFF1F5FD1)
    val BlueBubble = Color(0xFF24467F)
    val BlueLight = Color(0xFF8AB4FF)
    val BlueText = Color(0xFFB3CEFF)
    val BlueCard = Color(0xFF142238)
    val BlueBorder = Color(0xFF4F7FD9)
    val BlueIcon = Color(0xFF1E3354)

    val Amber = Color(0xFFE3A13B)
    val AmberText = Color(0xFFF2B55A)
    val AmberInk = Color(0xFF14100A)
    val AmberCard = Color(0xFF2A2113)
    val AmberBorder = Color(0xFF8A6A35)
    val AmberButtonBorder = Color(0xFFA08350)
    val AmberSoft = Color(0xFFF3E3CC)
    val AmberNote = Color(0xFFF2C98A)

    val Green = Color(0xFF3FB950)
    val GreenText = Color(0xFF7EE0A8)
    val GreenCard = Color(0xFF13261C)
    val GreenBorder = Color(0xFF3F7A57)
    val GreenDone = Color(0xFF13301F)

    val Red = Color(0xFFFF7B72)
    val RedSoft = Color(0xFFFF9C94)
    val RedCard = Color(0xFF2A1A1A)
    val RedBorder = Color(0xFFA04C46)
    val RedTitle = Color(0xFFFFB4AE)
    val RedBody = Color(0xFFF2D9D6)

    val Purple = Color(0xFFD2B8FF)
    val PurpleCard = Color(0xFF211A2E)
    val PurpleBorder = Color(0xFF6E5A8F)
    val PurpleIcon = Color(0xFF2E2442)
    val PurpleChip = Color(0xFF3A2C55)
    val PurpleTitle = Color(0xFFE9DDFF)
    val PurpleBody = Color(0xFFEDE6FA)
    val PurpleSoft = Color(0xFFCFC4E3)
    val PurpleSwitch = Color(0xFFA98BEA)
    val PurpleButton = Color(0xFF6A4BC4)
    val PurpleLine = Color(0xFF4B3D63)
    val PurpleReplyCard = Color(0xFF1C1925)
    val PurpleReplyLine = Color(0xFF3A3150)
    val PurplePanel = Color(0xFF17151C)
    val PurpleBar = Color(0xFF2A2433)

    // Whisper mode: deep sea-teal. White and the pale teals on the card are well over 4.5:1,
    // as is white on the button.
    val Whisper = Color(0xFF3BB7B0)
    val WhisperCard = Color(0xFF0F2A2E)
    val WhisperBorder = Color(0xFF3BB7B0)
    val WhisperIcon = Color(0xFF123A3F)
    val WhisperChip = Color(0xFF17474C)
    val WhisperTitle = Color(0xFFFFFFFF)
    val WhisperBody = Color(0xFFEAF7F6)
    val WhisperSoft = Color(0xFFB9D9D6)
    val WhisperSwitch = Color(0xFF3BB7B0)
    val WhisperButton = Color(0xFF0F766E)
    val WhisperLine = Color(0xFF1F5257)
    val WhisperReplyCard = Color(0xFF0D1F22)
    val WhisperReplyLine = Color(0xFF1B4347)
    val WhisperPanel = Color(0xFF0C1719)
    val WhisperBar = Color(0xFF12292C)

    val GreyIcon = Color(0xFF2A2E35)
    val Notification = Color(0xFF1F2328)
    val NotificationPlayer = Color(0xFF1A2740)
    val NotificationTrack = Color(0xFF3A4A66)
    val NotificationGround = Color(0xFF08090B)

    val DiffContext = Color(0xFF16191D)
    val DiffRemoved = Color(0xFF3A1D1D)
    val DiffRemovedText = Color(0xFFFFC9C4)
    val DiffAdded = Color(0xFF12301F)
    val DiffAddedText = Color(0xFFB4F0C8)

    val Asleep = Color(0xFF1E2430)
    val QueuedBubble = Color(0xFF1E2A3F)

    // Project tags on update cards.
    val TagStation = Color(0xFFFFB86B)
    val TagRandom = Color(0xFFD2B8FF)
    val TagSky = Color(0xFF8FD3FF)
    val TagRecipeBox = Color(0xFF7EE0A8)
}

/** Cinzel (SIL Open Font License 1.1): titles only. Body, buttons, chips, menus and messages keep the default font. */
val TitleFont = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(io.github.odnwks.mimir.R.font.cinzel))

/** Sizes of real text: never below 13 sp, line heights from the boards. */
object Type {
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val TitleSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    val TaskTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 1.3.em)
    val Big = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 1.45.em)
    val BodyTight = TextStyle(fontSize = 15.5.sp, lineHeight = 1.4.em)
    val Label = TextStyle(fontSize = 14.sp, lineHeight = 1.4.em)
    val Small = TextStyle(fontSize = 13.sp, lineHeight = 1.35.em)
    val Section = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Bold, lineHeight = 1.3.em)
    /** Cinzel is wide: sizes are about 8% under the old serif ones. Titles stay well above body text. */
    val PageTitle = TextStyle(fontFamily = TitleFont, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
    val Hello = TextStyle(fontFamily = TitleFont, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal)
    val SheetTitle = TextStyle(fontFamily = TitleFont, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val MenuHeading = TextStyle(fontFamily = TitleFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    val Mono = TextStyle(fontSize = 14.sp, lineHeight = 1.5.em, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

private val colors = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Palette.White,
    background = Palette.Ground,
    onBackground = Palette.Text,
    surface = Palette.Ground,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Outline,
    error = Palette.Red,
)

@Composable
fun MimirTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(bodyLarge = Type.Body, bodyMedium = Type.Label, bodySmall = Type.Small),
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val size = androidx.compose.runtime.remember { AppSize(ctx.getSharedPreferences(AppSize.PREFS, 0)) }
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { ScaledApp(size, content) }
    }
}
