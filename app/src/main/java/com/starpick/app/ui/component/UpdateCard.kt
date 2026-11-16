package com.starpick.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.UpdateUiState
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink

/**
 * 片匣顶部的更新提示条。
 *
 * 只做「告知 + 指路」，不在这里下载：下载进度和安装确认都集中在
 * 「设置 → 版本更新」一处，避免同一个动作有两套进度显示。
 * 状态源和设置页是同一个（[AppContainer.update]），两处永远一致。
 */
@Composable
fun UpdateCard(container: AppContainer, onOpenSettings: () -> Unit) {
    val state by container.update.collectAsState()

    val banner = when (val s = state) {
        is UpdateUiState.Available -> Triple(
            "发现新版本 ${s.tag}",
            "可应用内下载，也可用浏览器直接下",
            Accent.Gold,
        )

        is UpdateUiState.Downloading -> Triple(
            "正在下载新版本",
            s.fraction?.let { "已完成 ${(it * 100).toInt()}%，可在设置里查看" } ?: "可在设置里查看进度",
            Accent.Brand,
        )

        is UpdateUiState.Ready -> Triple(
            "新版本已下载",
            "去「设置 → 版本更新」安装",
            Accent.Gold,
        )

        else -> null
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.Raised)
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = banner.third,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = banner.first,
                style = MaterialTheme.typography.labelLarge,
                color = Ink.Text1,
            )
            Text(
                text = banner.second,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Ink.Text3,
            modifier = Modifier.size(18.dp),
        )
    }
}
