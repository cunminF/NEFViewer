package com.nefviewer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nefviewer.android.pipeline.PreviewBuilder

/** 全局预渲染进度浮层（主界面底部，可取消） */
@Composable
fun PreviewProgressOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val builder = androidx.compose.runtime.remember { PreviewBuilder.get(context) }
    val progress by builder.progress.collectAsState()
    val p = progress ?: return

    Card(modifier = modifier.padding(16.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "正在生成标准预览 · ${p.projectName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { if (p.total > 0) p.done / p.total.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${p.done} / ${p.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { builder.cancel() }) { Text("取消") }
        }
    }
}
