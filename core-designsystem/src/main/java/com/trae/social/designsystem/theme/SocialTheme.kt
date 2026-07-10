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
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat

/**
 * 应用主题入口：提供 [SocialColors] / [SocialTypography] / [SocialShapes] 并桥接 Material3 [MaterialTheme]。
 *
 * 同时通过 [WindowCompat] 设置透明状态栏，并按主题切换状态栏图标明暗外观。
 *
 * @param darkTheme 是否深色模式，默认跟随系统
 * @param colors 自定义配色，默认按深色模式取 [LightSocialColors] / [DarkSocialColors]
 * @param typography 自定义排版，默认 [DefaultSocialTypography]
 * @param shapes 自定义圆角，默认 [DefaultSocialShapes]
 * @param content 主题包裹的内容
 */
@Composable
fun SocialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: SocialColors = if (darkTheme) DarkSocialColors else LightSocialColors,
    typography: SocialTypography = DefaultSocialTypography,
    shapes: SocialShapes = DefaultSocialShapes,
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
 */
private fun SocialColors.toMaterialColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = systemBlue,
            onPrimary = Color.White,
            primaryContainer = systemBlue.copy(alpha = 0.25f),
            onPrimaryContainer = Color.White,
            secondary = systemBlue,
            onSecondary = Color.White,
            background = systemBackground,
            onBackground = label,
            surface = systemBackground,
            onSurface = label,
            surfaceVariant = secondaryBackground,
            onSurfaceVariant = secondaryLabel,
            error = systemRed,
            onError = Color.White,
            outline = separator,
            outlineVariant = separator,
        )
    } else {
        lightColorScheme(
            primary = systemBlue,
            onPrimary = Color.White,
            primaryContainer = systemBlue.copy(alpha = 0.15f),
            onPrimaryContainer = systemBlue,
            secondary = systemBlue,
            onSecondary = Color.White,
            background = systemBackground,
            onBackground = label,
            surface = systemBackground,
            onSurface = label,
            surfaceVariant = secondaryBackground,
            onSurfaceVariant = secondaryLabel,
            error = systemRed,
            onError = Color.White,
            outline = separator,
            outlineVariant = separator,
        )
    }

/**
 * 将 [SocialTypography] 映射为 Material3 Typography，便于复用 Material 组件默认文字样式。
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
    labelLarge = TextStyle.Default,
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
