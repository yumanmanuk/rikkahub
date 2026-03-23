package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

// [FORK] 使用 Google Fonts 可下载字体，接近 Google AI Studio 的阅读体验
// 英文/数字/符号：DM Sans（接近 Google Sans，字形纤细现代）
// 中文：Noto Sans SC Light（字形细腻，与 AI Studio 中文渲染最接近）
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val NotoSansSC = GoogleFont("Noto Sans SC")
private val DMSans = GoogleFont("DM Sans")

// 中文正文字体：Noto Sans SC Light，字重 W300
val AppFontFamily = FontFamily(
    Font(googleFont = NotoSansSC, fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = NotoSansSC, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = NotoSansSC, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = NotoSansSC, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    // DM Sans 覆盖 Latin 字符（英文/数字中 DM Sans 优先）
    Font(googleFont = DMSans, fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = DMSans, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = DMSans, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = DMSans, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
)

// [FORK] 全局 Typography 使用 AppFontFamily，正文字重 Light，行高 1.75em
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    ),
)

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)
