package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Eye Care (Sepia) Theme ────────────────────────────────────────────────
// 护眼模式：仿 Kindle Sepia 纸张质感
// 背景近似 #F5EDD8（暖米黄），正文色 #3B2F1E（深暖棕）
// 对比度约 9.6:1，满足 WCAG AAA（7:1）
// ──────────────────────────────────────────────────────────────────────────

// Primary — 暖棕，用于按钮、开关、强调
private val primaryEyeCare = Color(0xFF7D5A3C)
private val onPrimaryEyeCare = Color(0xFFFFFFFF)
private val primaryContainerEyeCare = Color(0xFFFFDCC1)
private val onPrimaryContainerEyeCare = Color(0xFF2D1200)

// Secondary — 淡棕，用于次要操作
private val secondaryEyeCare = Color(0xFF9B7B62)
private val onSecondaryEyeCare = Color(0xFFFFFFFF)
private val secondaryContainerEyeCare = Color(0xFFFFDCC1)
private val onSecondaryContainerEyeCare = Color(0xFF341100)

// Tertiary — 墨绿点缀，用于第三强调色
private val tertiaryEyeCare = Color(0xFF5A6E4A)
private val onTertiaryEyeCare = Color(0xFFFFFFFF)
private val tertiaryContainerEyeCare = Color(0xFFDCF0C8)
private val onTertiaryContainerEyeCare = Color(0xFF192D0C)

// Error
private val errorEyeCare = Color(0xFFBA1A1A)
private val onErrorEyeCare = Color(0xFFFFFFFF)
private val errorContainerEyeCare = Color(0xFFFFDAD6)
private val onErrorContainerEyeCare = Color(0xFF93000A)

// Background / Surface — 米黄主色调
private val backgroundEyeCare = Color(0xFFF5EDD8)       // 主背景，暖米黄
private val onBackgroundEyeCare = Color(0xFF3B2F1E)      // 主文字，深暖棕

private val surfaceEyeCare = Color(0xFFF5EDD8)
private val onSurfaceEyeCare = Color(0xFF3B2F1E)

private val surfaceVariantEyeCare = Color(0xFFEADFC8)
private val onSurfaceVariantEyeCare = Color(0xFF5A4A38)

private val outlineEyeCare = Color(0xFF9C8672)
private val outlineVariantEyeCare = Color(0xFFD4C4AE)

private val scrimEyeCare = Color(0xFF000000)

private val inverseSurfaceEyeCare = Color(0xFF3B2F1E)
private val inverseOnSurfaceEyeCare = Color(0xFFF5EDD8)
private val inversePrimaryEyeCare = Color(0xFFFFB77C)

// Surface 层级（从暗到亮）
private val surfaceDimEyeCare = Color(0xFFDDD3BC)             // 最暗层，分割线/阴影区
private val surfaceBrightEyeCare = Color(0xFFF8F3E5)          // 最亮层，输入框/高亮卡片
private val surfaceContainerLowestEyeCare = Color(0xFFFFFBF2) // 最浅容器
private val surfaceContainerLowEyeCare = Color(0xFFF2E9D3)    // 较浅容器
private val surfaceContainerEyeCare = Color(0xFFEDE3CD)       // 标准容器（CardGroup 等）
private val surfaceContainerHighEyeCare = Color(0xFFE7DCCA)   // 较深容器
private val surfaceContainerHighestEyeCare = Color(0xFFE0D5C0) // 最深容器

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
