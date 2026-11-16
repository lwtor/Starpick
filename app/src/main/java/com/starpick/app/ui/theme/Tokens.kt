package com.starpick.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计 token：深色是唯一主题。
 *
 * 追剧 App 的封面是彩色照片，只有深底才能让画面边缘消失、形成连续感。
 * 分层不靠阴影（纯黑上阴影不可见），靠三级明度：Base → Card → Raised。
 */
object Ink {
    val Base = Color(0xFF0A0E14)
    val Card = Color(0xFF141A24)
    val Raised = Color(0xFF19212C)
    val Selected = Color(0xFF212B38)
    val Line = Color(0x14FFFFFF)
    val LineStrong = Color(0x24FFFFFF)
    val Text1 = Color(0xFFF4F6FA)
    val Text2 = Color(0xFF98A2B3)
    val Text3 = Color(0xFF68727F)
}

/** 语义色：品牌蓝只表示「可交互」，星金只表示「值得注意」（评分 / 进度 / 当前集）。 */
object Accent {
    val Brand = Color(0xFF4C8DFF)
    val Gold = Color(0xFFF6BE4F)
    val New = Color(0xFFF55F57)
    val Online = Color(0xFF34D399)
}

/** 六个层级的字阶，字重只用 400 / 600 / 800 三档。 */
val StarTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)
