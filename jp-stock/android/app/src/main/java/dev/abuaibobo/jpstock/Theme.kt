package dev.abuaibobo.jpstock

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Monochrome base (near-black / gray scale). Colour is reserved for numbers
// and emphasis only, so the UI reads clean and the figures pop.

// Market up/down colours (numbers & candles)
val UpRed = Color(0xFFFF5A5F)     // up
val DownBlue = Color(0xFF41A0FF)  // down

// Grayscale surfaces / text
val BgDark = Color(0xFF0A0B0D)
val CardDark = Color(0xFF141619)
val BorderDark = Color(0xFF23262B)
val TextPrimary = Color(0xFFF0F2F4)
val TextSecondary = Color(0xFF9AA1A8)

// Single accent for interactive / anchor text and highlights
val AccentBlue = Color(0xFF63A8FF)
val AccentGold = Color(0xFFD9B24A)   // kept small (e.g. tags) — minimal use
val AccentPurple = Color(0xFFA78BFA)

// Rounded corners for cards, dialogs, sheets, text fields
val JPShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Material3 scheme for the whole app
val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = AccentBlue,
    secondary = TextSecondary,
    tertiary = AccentGold,
    background = BgDark,
    surface = CardDark,
    surfaceVariant = BorderDark,
    surfaceContainerHighest = Color(0xFF1B1E23),
    onPrimary = Color(0xFF06121F),
    onSecondary = Color(0xFF0B0D10),
    onTertiary = Color(0xFF0B0D10),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = BorderDark,
    error = UpRed,
)
