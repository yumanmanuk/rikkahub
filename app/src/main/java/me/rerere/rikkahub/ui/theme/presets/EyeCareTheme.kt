package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Eye Care (Sepia) Theme ────────────────────────────────────────────────
// 护眼模式：Kindle Sepia 纸张质感，明显区别于普通白底主题
// 背景 #E8D9B5（深暖黄），正文色 #2C1F0E（深棕）
// 对比度 > 9:1，满足 WCAG AAA（7:1）
// ──────────────────────────────────────────────────────────────────────────

// Primary — 琥珀棕，饱和度更高，按钮/开关/强调更明显
private val primaryEyeCare = Color(0xFF8B5E34)
private val onPrimaryEyeCare = Color(0xFFFFFFFF)
private val primaryContainerEyeCare = Color(0xFFFFD9AD)
private val onPrimaryContainerEyeCare = Color(0xFF2D1200)

// Secondary — 暖橄榄棕，次要操作
private val secondaryEyeCare = Color(0xFF7A6245)
private val onSecondaryEyeCare = Color(0xFFFFFFFF)
private val secondaryContainerEyeCare = Color(0xFFEFCFA0)
private val onSecondaryContainerEyeCare = Color(0xFF2A1700)

// Tertiary — 深橄榄绿，第三强调色
private val tertiaryEyeCare = Color(0xFF4A6040)
private val onTertiaryEyeCare = Color(0xFFFFFFFF)
private val tertiaryContainerEyeCare = Color(0xFFCAE6B0)
private val onTertiaryContainerEyeCare = Color(0xFF102408)

// Error
private val errorEyeCare = Color(0xFFBA1A1A)
private val onErrorEyeCare = Color(0xFFFFFFFF)
private val errorContainerEyeCare = Color(0xFFFFDAD6)
private val onErrorContainerEyeCare = Color(0xFF93000A)

// Background / Surface — 深暖黄，与普通白底形成明显视觉差距
private val backgroundEyeCare = Color(0xFFECDFBE)       // 主背景，深暖黄（仿羊皮纸/老书页）
private val onBackgroundEyeCare = Color(0xFF2C1F0E)      // 主文字，近墨棕，对比更强

private val surfaceEyeCare = Color(0xFFECDFBE)
private val onSurfaceEyeCare = Color(0xFF2C1F0E)

private val surfaceVariantEyeCare = Color(0xFFDFCEAD)
private val onSurfaceVariantEyeCare = Color(0xFF4A3A28)

private val outlineEyeCare = Color(0xFF8C7356)
private val outlineVariantEyeCare = Color(0xFFC4AD8A)

private val scrimEyeCare = Color(0xFF000000)

private val inverseSurfaceEyeCare = Color(0xFF2C1F0E)
private val inverseOnSurfaceEyeCare = Color(0xFFECDFBE)
private val inversePrimaryEyeCare = Color(0xFFFFB870)

// Surface 层级（从暗到亮，整体在暖黄色调内变化）
private val surfaceDimEyeCare = Color(0xFFCFC0A0)             // 最暗层，分割线/阴影区
private val surfaceBrightEyeCare = Color(0xFFF5EBCF)          // 最亮层，输入框/高亮卡片
private val surfaceContainerLowestEyeCare = Color(0xFFFAF3DA) // 最浅容器
private val surfaceContainerLowEyeCare = Color(0xFFEFE3C4)    // 较浅容器
private val surfaceContainerEyeCare = Color(0xFFE5D7B6)       // 标准容器（CardGroup 等）
private val surfaceContainerHighEyeCare = Color(0xFFDAC9A8)   // 较深容器
private val surfaceContainerHighestEyeCare = Color(0xFFCFBC9A) // 最深容器

val eyeCareColorScheme = lightColorScheme(
    primary = primaryEyeCare,
    onPrimary = onPrimaryEyeCare,
    primaryContainer = primaryContainerEyeCare,
    onPrimaryContainer = onPrimaryContainerEyeCare,
    secondary = secondaryEyeCare,
    onSecondary = onSecondaryEyeCare,
    secondaryContainer = secondaryContainerEyeCare,
    onSecondaryContainer = onSecondaryContainerEyeCare,
    tertiary = tertiaryEyeCare,
    onTertiary = onTertiaryEyeCare,
    tertiaryContainer = tertiaryContainerEyeCare,
    onTertiaryContainer = onTertiaryContainerEyeCare,
    error = errorEyeCare,
    onError = onErrorEyeCare,
    errorContainer = errorContainerEyeCare,
    onErrorContainer = onErrorContainerEyeCare,
    background = backgroundEyeCare,
    onBackground = onBackgroundEyeCare,
    surface = surfaceEyeCare,
    onSurface = onSurfaceEyeCare,
    surfaceVariant = surfaceVariantEyeCare,
    onSurfaceVariant = onSurfaceVariantEyeCare,
    outline = outlineEyeCare,
    outlineVariant = outlineVariantEyeCare,
    scrim = scrimEyeCare,
    inverseSurface = inverseSurfaceEyeCare,
    inverseOnSurface = inverseOnSurfaceEyeCare,
    inversePrimary = inversePrimaryEyeCare,
    surfaceDim = surfaceDimEyeCare,
    surfaceBright = surfaceBrightEyeCare,
    surfaceContainerLowest = surfaceContainerLowestEyeCare,
    surfaceContainerLow = surfaceContainerLowEyeCare,
    surfaceContainer = surfaceContainerEyeCare,
    surfaceContainerHigh = surfaceContainerHighEyeCare,
    surfaceContainerHighest = surfaceContainerHighestEyeCare,
)
