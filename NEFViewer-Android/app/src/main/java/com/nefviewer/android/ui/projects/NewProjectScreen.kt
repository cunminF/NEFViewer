package com.nefviewer.android.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nefviewer.android.data.ProjectEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewProjectScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    vm: NewProjectViewModel = viewModel(),
) {
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.pickTree(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (vm.step) {
                            NewProjectViewModel.Step.Basics -> "新建项目"
                            NewProjectViewModel.Step.Source -> "选择导入文件"
                            NewProjectViewModel.Step.Importing -> "正在导入"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when (vm.step) {
            NewProjectViewModel.Step.Basics -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("图片库方式", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = vm.mode == ProjectEntity.MODE_COPY,
                            onClick = { vm.mode = ProjectEntity.MODE_COPY },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("拷贝到本机") }
                        SegmentedButton(
                            selected = vm.mode == ProjectEntity.MODE_LINK,
                            onClick = { vm.mode = ProjectEntity.MODE_LINK },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("保留原地（链接）") }
                    }
                    Text(
                        if (vm.mode == ProjectEntity.MODE_COPY)
                            "文件将从储存卡复制到图库目录（可在设置中更改），之后拔卡也能浏览打分"
                        else
                            "只登记文件位置，不复制；储存卡拔出后无法查看未缓存内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("来源", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { treeLauncher.launch(vm.initialUri(true)) },
                            enabled = vm.name.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.SdCard, contentDescription = null)
                            Text(
                                vm.removableVolumeLabel ?: "外接储存卡",
                                Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { treeLauncher.launch(vm.initialUri(false)) },
                            enabled = vm.name.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Text("本机文件夹", Modifier.padding(start = 8.dp))
                        }
                    }
                    if (vm.removableVolumeLabel == null) {
                        Text(
                            "未检测到外接储存卡；点按钮也可手动浏览选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NewProjectViewModel.Step.Source -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                if (vm.files.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (vm.enumerateDone >= 0) {
                            CircularProgressIndicator()
                            Text(
                                if (vm.enumerateDone > 0) "正在读取 EXIF… ${vm.enumerateDone}"
                                else "正在扫描文件夹…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "这个文件夹里没有 NEF 文件",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            OutlinedButton(onClick = { vm.step = NewProjectViewModel.Step.Basics }) {
                                Text("重新选择来源")
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已选 ${vm.selected.size} / ${vm.files.size}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.toggleRangeMode() }) {
                            Text(
                                if (vm.rangeMode) "退出区间" else "区间选择",
                                color = if (vm.rangeMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(onClick = { vm.selectAll() }) { Text("全选") }
                        TextButton(onClick = { vm.selectNone() }) { Text("全不选") }
                    }
                    if (vm.rangeMode) {
                        Text(
                            if (vm.rangeAnchor == null) "区间选择：点第一个文件作为起点"
                            else "再点最后一个文件，完成区间选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        items(vm.files.size, key = { vm.files[it].relativePath }) { i ->
                            val f = vm.files[i]
                            val checked = i in vm.selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { vm.toggle(i) },
                                        onLongClick = { vm.setAnchor(i) },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { vm.toggle(i) },
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(
                                        f.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(f.dateTaken?.let { dateFmt.format(Date(it)) } ?: "时间未知")
                                            append(" · %.1f MB".format(f.size / 1048576.0))
                                            if (f.width > 0) append(" · ${f.width}×${f.height}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (vm.rangeAnchor == i) {
                                    Text(
                                        "起点",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { vm.createProject(onCreated) },
                        enabled = vm.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("导入 ${vm.selected.size} 张") }
                }
            }

            NewProjectViewModel.Step.Importing -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (vm.importTotal > 0) {
                        LinearProgressIndicator(
                            progress = { vm.importDone / vm.importTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                        Text("已导入 ${vm.importDone} / ${vm.importTotal}")
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
