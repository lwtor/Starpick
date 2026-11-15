package com.starpick.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.starpick.app.StarpickApp
import com.starpick.app.ui.nav.StarpickNav
import com.starpick.app.ui.theme.StarpickTheme

class StarpickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as StarpickApp).container
        setContent {
            StarpickTheme {
                StarpickNav(container)
            }
        }
    }
}
