package dev.abuaibobo.jpstock

import androidx.compose.ui.graphics.Color

// JP market convention: red = up, blue = down. Deep "trading terminal" theme.
val UpRed = Color(0xFFE5484D)
val DownBlue = Color(0xFF3B9EFF)
val FlatGray = Color(0xFF8B949E)

val BgDark = Color(0xFF0D1117)
val CardDark = Color(0xFF161B22)
val BorderDark = Color(0xFF21262D)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)
val AccentBlue = Color(0xFF58A6FF)
val AccentGold = Color(0xFFD29922)
val AccentPurple = Color(0xFFBC8CFF)

// Material3 dark scheme for the whole app
val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = AccentBlue,
    secondary = AccentPurple,
    tertiary = AccentGold,
    background = BgDark,
    surface = CardDark,
    surfaceVariant = BorderDark,
    onPrimary = Color(0xFF0D1117),
    onSecondary = Color(0xFF0D1117),
    onTertiary = Color(0xFF0D1117),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = UpRed,
)
