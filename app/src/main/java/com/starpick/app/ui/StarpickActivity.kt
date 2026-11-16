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
        // 主题是深色，系统栏必须用「浅色图标」——否则深底黑字等于看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
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
