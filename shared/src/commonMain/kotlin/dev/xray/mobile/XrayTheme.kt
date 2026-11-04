package dev.xray.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dev.xray.mobile.resources.Res
import dev.xray.mobile.resources.inter
import org.jetbrains.compose.resources.Font

internal val Background = Color(0xFF0D1118)
internal val Surface = Color(0xFF141C25)
internal val Border = Color(0xFF25303B)
internal val Accent = Color(0xFF81EBCD)
internal val Text = Color(0xFFE9EEF2)
internal val Muted = Color(0xFF97A3B1)
internal val Danger = Color(0xFFC89494)

@Composable
internal fun XrayTheme(content: @Composable () -> Unit) {
    val font = FontFamily(Font(Res.font.inter))
    val typography = Typography().let {
        it.copy(bodyLarge = it.bodyLarge.copy(fontFamily = font), bodyMedium = it.bodyMedium.copy(fontFamily = font),
            bodySmall = it.bodySmall.copy(fontFamily = font), labelLarge = it.labelLarge.copy(fontFamily = font),
            labelMedium = it.labelMedium.copy(fontFamily = font), labelSmall = it.labelSmall.copy(fontFamily = font))
    }
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Surface,
            onSurface = Text, onBackground = Text, error = Danger),
        typography = typography,
        content = content,
    )
}
