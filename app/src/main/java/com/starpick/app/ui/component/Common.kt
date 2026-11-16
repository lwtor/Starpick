package com.starpick.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/**
 * 影视封面。空封面用首字占位；有封面时按圆角裁切铺满。
 * 豆瓣图片服务器有防盗链：自动给 doubanio.com 注入 Referer 与 UA。
 */
@Composable
fun VodCover(title: String, cover: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    if (cover.isBlank()) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0xFFE3EAF3)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF5A6B80),
            )
        }
    } else {
        // 豆瓣图片防盗链 Referer 由全局 ImageLoader 的 Interceptor 统一注入（见 StarpickApp）。
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cover)
                .crossfade(true)
                .build(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(shape)
                .background(Color(0xFFE3EAF3)),
        )
    }
}

/**
 * 手写分行网格：把一维列表按固定列数切成若干 Row，供 LazyColumn 的 item 内部使用。
 *
 * 为什么不用 LazyVerticalGrid：LazyVerticalGrid 是惰性滚动容器，不能嵌套在另一个
 * 纵向滚动容器（LazyColumn）里——会触发 "Vertically scrollable component was measured
 * with an infinity maximum height constraints" 崩溃。这里用普通 Row/Column 平铺，
 * 数据量不大（榜单/片匣一般几十项），性能足够。
 */
@Composable
fun FlowPosterGrid(
    columns: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    horizontalSpacing: androidx.compose.ui.unit.Dp = 12.dp,
    verticalSpacing: androidx.compose.ui.unit.Dp = 16.dp,
    itemContent: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        var index = 0
        while (index < itemCount) {
            val rowItems = (index until (index + columns).coerceAtMost(itemCount)).toList()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            ) {
                rowItems.forEach { i ->
                    itemContent(
                        i,
                        Modifier.weight(1f),
                    )
                }
                // 末行补位：用占位 Box 保证不足一行的项左对齐。
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            index += columns
        }
    }
}

/** 空状态占位：居中标题 + 说明。 */
@Composable
fun EmptyHint(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.padding(vertical = 4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
