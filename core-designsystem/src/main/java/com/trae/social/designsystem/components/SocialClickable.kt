package com.trae.social.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

/**
 * 带水波纹按压反馈的可点击修饰符（#21）。
 *
 * 项目内多处可点击元素直接使用 `Modifier.clickable(onClick = ...)`，该重载默认无 indication，
 * 按下无视觉响应。本修饰符统一注入 Material3 [ripple] 水波纹，提供一致的按压反馈。
 *
 * 用法：`Modifier.socialClickable { onClick() }`
 *
 * @param role 可访问性角色，默认 null
 * @param enabled 是否启用点击
 * @param onClick 点击回调
 */
fun Modifier.socialClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(),
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}
