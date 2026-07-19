package com.trae.social.designsystem.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
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
 *
 * 使用 [compositionLocalOf] 而非 `staticCompositionLocalOf`：该值会随系统设置/辅助功能
 * 状态在运行时变化（见 [rememberReduceMotion]），读取方需要建立订阅以便值变化时重组。
 * `staticCompositionLocalOf` 不建立订阅，会导致运行时切换「移除动画」/ TalkBack 后读取方
 * 不重组、仍按旧值渲染。
 */
val LocalReduceMotion = compositionLocalOf { false }

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
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    // #229：调用纯函数 computeReduceMotion(scale, a11yEnabled, touchExplorationEnabled)
    // 以便单测在无 Robolectric 的情况下覆盖各分支逻辑。
    return computeReduceMotion(
        scale = scale,
        a11yEnabled = am?.isEnabled == true,
        touchExplorationEnabled = am?.isTouchExplorationEnabled == true,
    )
}

/**
 * #229：纯逻辑版本的减弱动效判定，便于单测。
 *
 * 规则：
 * 1. 若 `ANIMATOR_DURATION_SCALE <= 0`（开发者选项「移除动画」），直接返回 true，
 *    不再考虑辅助功能状态。
 * 2. 否则仅当 AccessibilityManager 可用（非 null，由调用方保证）且
 *    `isEnabled && isTouchExplorationEnabled` 同时为 true 时才返回 true。
 *
 * @param scale 系统动画时长缩放（Settings.Global.ANIMATOR_DURATION_SCALE），默认 1f
 * @param a11yEnabled AccessibilityManager.isEnabled
 * @param touchExplorationEnabled AccessibilityManager.isTouchExplorationEnabled
 */
internal fun computeReduceMotion(
    scale: Float,
    a11yEnabled: Boolean,
    touchExplorationEnabled: Boolean,
): Boolean {
    if (scale <= 0f) return true
    return a11yEnabled && touchExplorationEnabled
}

/**
 * 订阅系统减弱动效状态，返回当前是否应减弱动效。
 *
 * 同时监听两个独立的运行时信号源，任一变化都会重算 [computeReduceMotion]：
 * 1. [ContentObserver] 监听 `ANIMATOR_DURATION_SCALE`（开发者选项「移除动画」开关）
 * 2. [AccessibilityManager.addTouchExplorationStateChangeListener] 监听触控探索开关
 *    （TalkBack 等屏幕阅读器）。触控探索是独立的辅助功能状态，不会被信号 1 的
 *    `ContentObserver` 感知，必须用专用 listener 订阅，否则运行时开启/关闭 TalkBack
 *    不会即时反映。
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
        // 触控探索状态由独立的 listener 订阅；ContentObserver 只监听 ANIMATOR_DURATION_SCALE，
        // 不会在 TalkBack 开关时触发。
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener {
            value = context.computeReduceMotion()
        }
        am?.addTouchExplorationStateChangeListener(touchListener)
        // awaitDispose 在 composition 离开时执行 lambda 并结束协程，清理只需在此处做一次。
        awaitDispose {
            context.contentResolver.unregisterContentObserver(observer)
            am?.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return reduceState.value
}
