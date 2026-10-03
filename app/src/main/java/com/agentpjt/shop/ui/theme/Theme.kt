package com.agentpjt.shop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 색은 웹 시안(:root 토큰)과 같은 값. 이름도 시안을 따른다.
@Immutable
data class ShopColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val voice: Color,
    val voiceInk: Color,
    val voiceTint: Color,
    val speak: Color,
    val speakInk: Color,
    val ok: Color,
)

private val Light = ShopColors(
    paper = Color(0xFFEEF1F6), surface = Color(0xFFFFFFFF), ink = Color(0xFF0F1A2E), inkSoft = Color(0xFF4A5568),
    line = Color(0xFFCDD5E1), voice = Color(0xFF1C46C9), voiceInk = Color(0xFFFFFFFF), voiceTint = Color(0xFFE3EAFC),
    speak = Color(0xFFFFD43B), speakInk = Color(0xFF2E2300), ok = Color(0xFF0E7A4B),
)

private val Dark = ShopColors(
    paper = Color(0xFF0C121D), surface = Color(0xFF151D2B), ink = Color(0xFFECF0F7), inkSoft = Color(0xFFA3AFC2),
    line = Color(0xFF2B374B), voice = Color(0xFF7398FF), voiceInk = Color(0xFF0A1430), voiceTint = Color(0xFF1D2A47),
    speak = Color(0xFFE9C02E), speakInk = Color(0xFF221900), ok = Color(0xFF4CC38A),
)

val LocalShopColors = staticCompositionLocalOf { Light }

// 글자 크기는 시안과 같다. 글꼴은 기기 설정을 따르도록 지정하지 않는다.
private val ShopTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
)

object ShopTheme {
    val colors: ShopColors @Composable get() = LocalShopColors.current
}

@Composable
fun ShopTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.voice, onPrimary = c.voiceInk, background = c.surface, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    } else {
        lightColorScheme(primary = c.voice, onPrimary = c.voiceInk, background = c.surface, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalShopColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = ShopTypography, content = content)
    }
}
