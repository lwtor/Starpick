package com.starpick.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starpick.app.ui.theme.Ink

/**
 * 设置类页面共用的小零件。
 *
 * 设置页与「内容源」二级页同属一套视觉语言：卡片靠明度分层、状态靠胶囊、失败用同一个红。
 * 这些比按钮更「形状」的东西放这里，避免两个文件各写一份、改一处漏一处。
 */

/** 统一卡片外观：16dp 圆角 + 极淡描边，靠明度分层而不是阴影。 */
fun Modifier.settingsCard(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(16.dp))
    .background(Ink.Card)
    .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))

/** 分区标题：比正文重、比页头轻，一眼能切出「这是第几类」。 */
@Composable
fun SettingsSectionHeader(
    title: String,
    trailing: String? = null,
    topPadding: Dp = 20.dp,
    bottomPadding: Dp = 10.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.3.sp),
            color = Ink.Text2,
            fontWeight = FontWeight.SemiBold,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )
        }
    }
}

/** 状态胶囊：底色是同色 14% 透明，读起来像标签而不是按钮。 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/** 失败态红：比 Accent.New 稍亮，深底上小字号也读得清。 */
val WarnColor = Color(0xFFFF6B6B)

/** 缓存大小按量级取单位，1 GB 上限显示成「1.0 GB」比「1024 MB」好读。 */
fun formatCacheSize(bytes: Long): String = when {
    bytes >= 3L * 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    else -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
}
