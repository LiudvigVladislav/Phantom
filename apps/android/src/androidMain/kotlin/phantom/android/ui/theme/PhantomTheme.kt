package phantom.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyanAccent   = Color(0xFF00D4FF)
val BgDeep       = Color(0xFF0B0D12)
val Surface      = Color(0xFF0F1318)
val Surface2     = Color(0xFF141820)
val TextPrimary  = Color(0xFFE8F4F8)
val TextDim      = Color(0xFF6B8A9A)
val Success      = Color(0xFF2FBF71)
val Danger       = Color(0xFFE85D75)

private val PhantomColorScheme = darkColorScheme(
    primary          = CyanAccent,
    onPrimary        = BgDeep,
    background       = BgDeep,
    onBackground     = TextPrimary,
    surface          = Surface,
    onSurface        = TextPrimary,
    surfaceVariant   = Surface2,
    onSurfaceVariant = TextDim,
    outline          = Color(0xFF1E2530),
    error            = Danger,
)

@Composable
fun PhantomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhantomColorScheme,
        content = content,
    )
}
