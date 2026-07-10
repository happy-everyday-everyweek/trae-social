# core-designsystem 设计系统

Apple iOS HIG 风格设计系统，namespace `com.trae.social.designsystem`。提供主题（色彩/字体/形状）与可复用组件。依赖 `compose-bom`、`material3`、`material-icons-extended`、`coil`。

## 主题层

### SocialColors

`data class`，13 个颜色 token：

- `systemBlue` / `systemRed` / `systemGreen` / `systemOrange` / `systemPurple`
- `systemBackground` / `systemSecondaryBackground` / `systemTertiaryBackground`
- `label` / `secondaryLabel` / `tertiaryLabel`
- `separator` / `surface`

`LightSocialColors`（`systemBlue = 0xFF007AFF` 等 iOS HIG 浅色）与 `DarkSocialColors`（`systemBlue = 0xFF0A84FF`，背景近黑 `0xFF000000` / `0xFF1C1C1E` / `0xFF2C2C2E`）。通过 `staticCompositionLocalOf` 暴露 `LocalSocialColors`；`socialColors()` composable 读取。

### SocialTypography

11 个 `TextStyle` 对齐 SF Pro：

| 名称 | 字号(sp) | 行高 |
| --- | --- | --- |
| largeTitle | 34 | 41 |
| title1 | 28 | 34 |
| title2 | 22 | 28 |
| title3 | 20 | 25 |
| headline | 17 | 22 |
| body | 17 | 22 |
| callout | 16 | 21 |
| subheadline | 15 | 20 |
| footnote | 13 | 18 |
| caption1 | 12 | 16 |
| caption2 | 11 | 13 |

`FontFamily.SansSerif` 默认中文回退系统字体。`LocalSocialTypography` 暴露。

### SocialShapes

4 档圆角 `small = 8.dp` / `medium = 12.dp` / `large = 16.dp` / `extraLarge = 20.dp`（`RoundedCornerShape`）。`toMaterialShapes()` 映射 Material3。

### SocialTheme

```kotlin
fun SocialTheme(
    darkTheme: Boolean,
    colors: SocialColors,
    typography: SocialTypography,
    shapes: SocialShapes,
    content: @Composable () -> Unit,
)
```

设计系统入口。`WindowCompat` 设置透明状态栏 + `enableEdgeToEdge()`，依 `darkTheme` 调整状态栏图标外观（`isAppearanceLightStatusBars`）。`toMaterialColorScheme` / `toMaterialTypography` 映射 Material3，最终调 `MaterialTheme`。`findActivity()` 用 `tailrec` 沿 `ContextWrapper` 链查找 `Activity`。

## 组件层

### ActionButton

胶囊形 `CircleShape` 主按钮，`containerColor = systemBlue` `contentColor = White`，disabled alpha 0.4，支持可选 icon + 文本。

### Avatar

默认 44dp `CircleShape` `SubcomposeAsyncImage`，loading / error 显示 `LoadingShimmer` 占位。

### CapsuleTab

横向 scrollable `Row`，单 tab `Clip(RoundedCornerShape(50))` 胶囊，`animateColorAsState` + `Spring.DampingRatioNoBouncy`，选中 `systemBlue` + `White`，未选中 `systemSecondaryBackground` + `label`。

### GlassBlurContainer

核心。enum `GlassBlurTier{HIGH(20.dp), MID(10.dp), LOW(0.dp)}`。`rememberGlassBlurTier()`：低端机（`isLowRamDevice`）-> LOW；API < S（<31）-> MID；`memoryClass >= 256` -> HIGH 否则 MID。双图层策略：背景层 `graphicsLayer` + `RenderEffect.createBlurEffect(radius, radius, MIRROR)` + 半透明 tint；内容层锐利。`LocalIsScrolling` 滚动中时半径减半。`canBlur = API >= S && effectiveRadius > 0`，否则降级 `surface.copy(alpha = 0.85f)` 单色遮罩。IMPL-33 修复：原 `Modifier.blur` 模糊整个节点，改为双图层 `RenderEffect` 仅模糊背景层。

### LoadingShimmer

`shimmerBrush()` `linearGradient` 1200ms `tween` `LinearEasing`，颜色 `systemSecondaryBackground` -> `systemTertiaryBackground` -> `systemSecondaryBackground`。`Modifier.shimmer()` 扩展。

### SocialCard

`Card` 默认 12dp cornerRadius 1dp elevation。

### SocialClickable

`Modifier.socialClickable()` 扩展，`ripple()` indication + `MutableInteractionSource`。

### SocialDivider

`HorizontalDivider` thickness `0.5.dp` color = `separator`。

### SocialSheet

`ModalBottomSheet` 包装 `containerColor = systemBackground` 顶部 12dp 圆角。

## build.gradle.kts

namespace `com.trae.social.designsystem`，`compileSdk 34` `minSdk 26` JVM 17。

依赖：`compose-bom` / `compose ui` / `material3` / `material-icons-extended` / `foundation` / `activity-compose` / `coil-compose` / `coil-svg`。
