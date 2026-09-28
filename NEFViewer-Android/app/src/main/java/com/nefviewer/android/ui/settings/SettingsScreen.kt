package com.nefviewer.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nefviewer.android.pipeline.CacheManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val autoBuild by vm.autoBuildPreviews.collectAsState()
    val preferred by vm.preferredEditor.collectAsState()
    val libraryDisplay by vm.libraryDisplay.collectAsState()
    var editorDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val libraryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setLibrary(it) } }

    LaunchedEffect(vm.lastMessage) {
        vm.lastMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionTitle("通用")
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("导入后自动生成标准预览", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "类 Adobe 的全量预渲染，带进度条、可取消",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoBuild, onCheckedChange = { vm.setAutoBuild(it) })
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { libraryLauncher.launch(null) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("图库位置（拷贝模式）", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                libraryDisplay ?: "应用私有目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (libraryDisplay != null) {
                            TextButton(onClick = { vm.resetLibrary() }) { Text("恢复默认") }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editorDialog = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("默认外部编辑器", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                preferred?.let { flat ->
                                    vm.editorCandidates?.firstOrNull { it.flattened == flat }?.label
                                        ?: flat.substringBefore('/')
                                } ?: "每次询问",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("缓存管理")
                Card {
                    val infos = vm.cacheInfos
                    if (infos == null) {
                        Text(
                            "统计中…",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val total = infos.sumOf { it.totalBytes }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "总计 ${CacheManager.formatBytes(total)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            TextButton(onClick = { vm.clearOrphans() }) { Text("清理孤儿缓存") }
                            TextButton(onClick = { vm.clearAll() }) {
                                Text("全部清理", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        infos.forEach { info ->
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(info.projectName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "缩略图 ${CacheManager.formatBytes(info.thumbBytes)} · " +
                                            "预览 ${CacheManager.formatBytes(info.previewBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { vm.clearProjectCache(info.projectId) }) {
                                    Text("清理")
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionTitle("关于")
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("NEF Viewer Android", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "v0.1.0 · 轻量 NEF 选片工具 · GPL-2.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (editorDialog) {
        AlertDialog(
            onDismissRequest = { editorDialog = false },
            title = { Text("默认外部编辑器") },
            text = {
                LazyColumn {
                    item {
                        EditorOption(
                            label = "每次询问",
                            selected = preferred == null,
                            onClick = { vm.setEditor(null); editorDialog = false },
                        )
                    }
                    items(vm.editorCandidates ?: emptyList()) { app ->
                        EditorOption(
                            label = app.label,
                            selected = preferred == app.flattened,
                            onClick = { vm.setEditor(app.flattened); editorDialog = false },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editorDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun EditorOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
