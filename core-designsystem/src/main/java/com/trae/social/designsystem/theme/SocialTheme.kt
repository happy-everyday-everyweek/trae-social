package com.trae.social.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 应用主题入口：提供 [SocialColors] / [SocialTypography] / [SocialShapes] / [SocialSpacing] 并桥接 Material3 [MaterialTheme]。
 *
 * 同时通过 [WindowCompat] 设置透明状态栏，并按主题切换状态栏图标明暗外观。
 *
 * @param darkTheme 是否深色模式，默认跟随系统
 * @param colors 自定义配色，默认按深色模式取 [LightSocialColors] / [DarkSocialColors]
 * @param typography 自定义排版，默认 [DefaultSocialTypography]
 * @param shapes 自定义圆角，默认 [DefaultSocialShapes]
 * @param spacing 自定义间距 Token，默认 [SocialSpacing]（4dp baseline grid）
 * @param content 主题包裹的内容
 */
@Composable
fun SocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: SocialColors = if (darkTheme) DarkSocialColors else LightSocialColors,
    typography: SocialTypography = DefaultSocialTypography,
    shapes: SocialShapes = DefaultSocialShapes,
    spacing: SocialSpacing = SocialSpacing(),
    content: @Composable () -> Unit,
) {
    val materialColorScheme = colors.toMaterialColorScheme(darkTheme)
    val materialTypography = typography.toMaterialTypography()
    val materialShapes = shapes.toMaterialShapes()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.let { activity ->
                val window = activity.window
                // 透明状态栏 + 导航栏，内容延伸至系统栏下方（edge-to-edge）
                // #56：补设 navigationBarColor=Transparent，清除 enableEdgeToEdge()
                // （activity-compose 1.9.1）默认对导航栏施加的半透明 contrast tint，
                // 否则导航栏区域带系统着色与底栏玻璃叠加产生色差。
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.setDecorFitsSystemWindows(window, false)
                // 状态栏 / 导航栏图标外观：深色模式浅色图标，明色模式深色图标
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSocialColors provides colors,
        LocalSocialTypography provides typography,
        LocalSocialShapes provides shapes,
        LocalSocialSpacing provides spacing,
        // #200：减弱动效偏好下放到整个子树，所有动效点统一读取，避免散落判定
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = materialTypography,
            shapes = materialShapes,
            content = content,
        )
    }
}

/**
 * 将 [SocialColors] 映射为 Material3 ColorScheme，便于复用 Material 组件默认配色。
 *
 * #180：补齐 tertiary / tertiaryContainer / onTertiaryContainer / errorContainer /
 * onErrorContainer / inverseSurface / inverseOnSurface / inversePrimary / scrim /
 * surfaceTint 等 token。原实现仅设置部分 token，导致 Material3 组件（Slider、
 * SegmentedButton、Snackbar、Chip 等）取到默认的粉紫色 (#7D5260/#EFB8C8)，
 * 与 iOS systemBlue 体系冲突，且 dark/light 切换时不跟随 SocialColors 变化。
 */
private fun SocialColors.toMaterialColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = systemBlue,
            onPrimary = Color.White,
            primaryContainer = systemBlue.copy(alpha = 0.25f),
            onPrimaryContainer = Color.White,
            // #180：inversePrimary 在 inverseSurface 背景上使用，取明色 systemBlue 互补
            inversePrimary = Color(0xFF007AFF),
            secondary = systemBlue,
            onSecondary = Color.White,
            // #180：tertiary 映射到 systemPurple，作为第三级强调色
            tertiary = systemPurple,
            onTertiary = Color.White,
            tertiaryContainer = systemPurple.copy(alpha = 0.25f),
            onTertiaryContainer = Color.White,
            background = systemBackground,
            onBackground = label,
            surface = systemBackground,
            onSurface = label,
            surfaceVariant = secondaryBackground,
            onSurfaceVariant = secondaryLabel,
            // #180：surfaceTint 在 M3 中默认等于 primary，控制 elevation 上的色调叠加
            surfaceTint = systemBlue,
            // #180：inverseSurface 用于 Snackbar 反色背景等，深色模式下取浅色 (label)
            inverseSurface = label,
            inverseOnSurface = systemBackground,
            error = systemRed,
            onError = Color.White,
            // #180：errorContainer 用于错误状态下的浅色背景容器
            errorContainer = systemRed.copy(alpha = 0.25f),
            onErrorContainer = Color.White,
            outline = separator,
            outlineVariant = separator,
            // #180：scrim 用于 Modal/Dialog 等遮罩，统一为半透明黑
            scrim = Color.Black.copy(alpha = 0.6f),
        )
    } else {
        lightColorScheme(
            primary = systemBlue,
            onPrimary = Color.White,
            primaryContainer = systemBlue.copy(alpha = 0.15f),
            onPrimaryContainer = systemBlue,
            // #180：inversePrimary 在 inverseSurface 背景上使用，取深色 systemBlue 互补
            inversePrimary = Color(0xFF0A84FF),
            secondary = systemBlue,
            onSecondary = Color.White,
            // #180：tertiary 映射到 systemPurple，作为第三级强调色
            tertiary = systemPurple,
            onTertiary = Color.White,
            tertiaryContainer = systemPurple.copy(alpha = 0.15f),
            onTertiaryContainer = systemPurple,
            background = systemBackground,
            onBackground = label,
            surface = systemBackground,
            onSurface = label,
            surfaceVariant = secondaryBackground,
            onSurfaceVariant = secondaryLabel,
            // #180：surfaceTint 在 M3 中默认等于 primary，控制 elevation 上的色调叠加
            surfaceTint = systemBlue,
            // #180：inverseSurface 用于 Snackbar 反色背景等，明色模式下取深色 (label)
            inverseSurface = label,
            inverseOnSurface = systemBackground,
            error = systemRed,
            onError = Color.White,
            // #180：errorContainer 用于错误状态下的浅色背景容器
            errorContainer = systemRed.copy(alpha = 0.15f),
            onErrorContainer = systemRed,
            outline = separator,
            outlineVariant = separator,
            // #180：scrim 用于 Modal/Dialog 等遮罩，统一为半透明黑
            scrim = Color.Black.copy(alpha = 0.6f),
        )
    }

/**
 * 将 [SocialTypography] 映射为 Material3 Typography，便于复用 Material 组件默认文字样式。
 *
 * #180：labelLarge 原映射为 [TextStyle.Default]，导致 Material3 原生按钮
 * （Button/OutlinedButton/TextButton/FilterChip/SegmentedButton）文字落到默认 14sp/400
 * Roboto 样式，与 SocialTypography 体系不一致。此处映射到 subheadline（15sp Normal），
 * 与 bodySmall 保持一致，保证按钮文字仍走 SocialTypography 字体回退链与字号体系。
 */
private fun SocialTypography.toMaterialTypography(): Typography = Typography(
    displayLarge = largeTitle,
    displayMedium = title1,
    displaySmall = title2,
    headlineLarge = title1,
    headlineMedium = title2,
    headlineSmall = title3,
    titleLarge = headline,
    titleMedium = body,
    titleSmall = subheadline,
    bodyLarge = body,
    bodyMedium = callout,
    bodySmall = subheadline,
    labelLarge = subheadline,
    labelMedium = caption1,
    labelSmall = caption2,
)

/**
 * 沿 ContextWrapper 链查找 Activity，用于获取 Window 设置状态栏。
 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
