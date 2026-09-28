package com.nefviewer.android.ui.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectListScreen(
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ProjectListViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsState()
    var menuFor by remember { mutableStateOf<ProjectListViewModel.ProjectRow?>(null) }
    var renameFor by remember { mutableStateOf<ProjectListViewModel.ProjectRow?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectListViewModel.ProjectRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NEF Viewer") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewProject) {
                Icon(Icons.Default.Add, contentDescription = "新建项目")
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "还没有项目\n点右下角 + 新建一个图片库",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.project.id }) { row ->
                    Box {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenProject(row.project.id) },
                                    onLongClick = { menuFor = row },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Icon(
                                    if (row.project.mode == "copy") Icons.Default.SdCard else Icons.Default.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.project.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(if (row.project.mode == "copy") "拷贝" else "链接")
                                            append(" · ")
                                            append(row.photoCount)
                                            append(" 张 · ")
                                            append(
                                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                    .format(Date(row.project.createdAt))
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = menuFor == row,
                            onDismissRequest = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("生成全部标准预览") },
                                onClick = { vm.buildPreviews(row.project); menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text("清理项目缓存") },
                                onClick = { vm.clearCache(row.project.id); menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = { renameFor = row; menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text("删除项目", color = MaterialTheme.colorScheme.error) },
                                onClick = { deleteFor = row; menuFor = null },
                            )
                        }
                    }
                }
            }
        }
    }

    renameFor?.let { row ->
        var name by remember(row.project.id) { mutableStateOf(row.project.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名项目") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.rename(row.project.id, name); renameFor = null },
                    enabled = name.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }

    deleteFor?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除项目「${row.project.name}」？") },
            text = {
                Text(
                    if (row.project.mode == "copy")
                        "将删除数据库记录、缓存，以及已拷贝到应用内的 ${row.photoCount} 个文件。"
                    else
                        "将删除数据库记录和缓存；源文件保留在原处，不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(row); deleteFor = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
}
