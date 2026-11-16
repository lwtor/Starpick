package com.starpick.app.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.starpick.app.StarpickApp
import com.starpick.app.ui.nav.StarpickNav
import com.starpick.app.ui.theme.StarpickTheme

class StarpickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏沉浸（内容顶到系统栏下），由各页面的 inset padding 自己让位。
        // 关键：主题恒为浅色（见 Theme.kt），状态栏/导航栏必须是「深色图标」，
        // 否则 edge-to-edge 下白字会糊在浅色背景上完全看不清。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as StarpickApp).container
        setContent {
            StarpickTheme {
                StarpickNav(container)
            }
        }
    }
}
