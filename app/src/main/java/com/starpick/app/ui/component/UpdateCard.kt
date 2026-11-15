package com.starpick.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.BuildConfig
import dev.veneranative.updater.InstallResult
import dev.veneranative.updater.UpdateStatus
import kotlinx.coroutines.launch

/**
 * 自升级入口卡片。真相源是 [AppContainer.updater.status]，
 * 启动时 [AppContainer.startUpdateCheck] 已静默检查 + 预下载：
 *   - Downloaded：直接给「安装」
 *   - UpdateAvailable：预下载没成功（网络问题等），给「重试下载」
 */
@Composable
fun UpdateCard(container: AppContainer) {
    val status by container.updater.status.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    when (val s = status) {
        UpdateStatus.Idle -> Unit

        is UpdateStatus.UpdateAvailable -> UpdateBanner(
            title = "发现新版本",
            subtitle = message ?: "code ${s.versionCode}，尚未下载",
            actionLabel = "下载",
            busy = busy,
            onAction = {
                scope.launch {
                    busy = true
                    // checkAndPrefetch 会重试下载并在成功后把 status 推到 Downloaded。
                    container.updater.checkAndPrefetch(BuildConfig.VERSION_CODE.toLong())
                    busy = false
                }
            },
        )

        is UpdateStatus.Downloaded -> UpdateBanner(
            title = "新版本已就绪",
            subtitle = message ?: "code ${s.versionCode}，可安装",
            actionLabel = "安装",
            busy = busy,
            onAction = {
                scope.launch {
                    busy = true
                    when (val r = container.updater.install(s.apkFile)) {
                        InstallResult.Launched -> message = "已交给系统安装器，请确认"
                        is InstallResult.Failure -> message = "安装失败：${r.message}"
                    }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun UpdateBanner(
    title: String,
    subtitle: String,
    actionLabel: String,
    busy: Boolean,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.width(24.dp).height(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
