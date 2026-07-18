package com.trae.social.designsystem.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 减弱动效偏好 CompositionLocal。
 *
 * Android 没有直接对应 web 的 `prefers-reduced-motion`，这里综合两个信号作为代理：
 * 1. `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`（开发者选项中的「移除动画」）
 * 2. `AccessibilityManager.isTouchExplorationEnabled`（屏幕阅读器/Touch Exploration 开启时
 *    视为用户希望界面少动）
 *
 * 为 true 时，自定义动画应：
 * - 用 `tween(150, FastOutSlowInEasing)` 替代 spring/MediumBouncy 等 overshoot 动画
 * - 移除 transform/位移类动画，仅保留 opacity 与 color 过渡
 * - 减弱动效 ≠ 零反馈，状态变化仍需被感知
 *
 * 默认值为 false，未在 [SocialTheme] 包裹时仍可读取。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 计算当前是否应减弱动效。
 *
 * 将计算逻辑抽出便于单测与 [produceState] 复用。
 */
internal fun Context.computeReduceMotion(): Boolean {
    val scale = Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    if (scale <= 0f) return true
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    // 仅当辅助功能开启且触控探索开启时视为减弱动效
    return am.isEnabled && am.isTouchExplorationEnabled
}

/**
 * 订阅系统减弱动效状态，返回当前是否应减弱动效。
 *
 * 通过 [ContentObserver] 监听 `ANIMATOR_DURATION_SCALE` 变化，
 * 用户在开发者选项中切换后能即时反映；触控探索状态在 onChange 时一并重算。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val reduceState = produceState(initialValue = context.computeReduceMotion(), context) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                value = context.computeReduceMotion()
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        try {
            awaitDispose {
                context.contentResolver.unregisterContentObserver(observer)
            }
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return reduceState.value
}
