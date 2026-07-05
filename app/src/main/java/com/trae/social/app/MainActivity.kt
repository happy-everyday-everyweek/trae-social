package com.trae.social.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trae.social.app.ui.PlaceholderScreen
import com.trae.social.designsystem.theme.SocialTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用主入口 Activity。
 *
 * 骨架阶段仅渲染空白占位界面，后续 Task 将接入 Navigation Compose 与各 feature 屏幕。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SocialTheme {
                PlaceholderScreen()
            }
        }
    }
}
