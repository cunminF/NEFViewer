package com.nefviewer.android.ui.browser

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.pipeline.CacheManager
import com.nefviewer.android.pipeline.PreviewBuilder
import com.nefviewer.android.pipeline.ThumbnailCache
import com.nefviewer.android.ui.components.RatingBar
import com.nefviewer.android.xmp.XmpExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    projectId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: BrowserViewModel = viewModel(
        key = "browser-$projectId",
        factory = viewModelFactory { initializer { BrowserViewModel(app, projectId) } },
    )
    val project by vm.project.collectAsState()
    val displayed by vm.displayed.collectAsState()
    val allPhotos by vm.allPhotos.collectAsState()
    val selection by vm.selection.collectAsState()
    val sort by vm.sort.collectAsState()
    val filter by vm.ratingFilter.collectAsState()
    val hideUnrated by vm.hideUnrated.collectAsState()
    val scope = rememberCoroutineScope()
    var singleIndex by rememberSaveable { mutableIntStateOf(-1) }
    /** 单图模式使用进入时的列表快照：翻页顺序不随评分重排变化 */
    var singleList by remember { mutableStateOf<List<PhotoEntity>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun openSingle(index: Int) {
        singleList = displayed
        singleIndex = index
    }

    val currentProject = project
    val frozenList = singleList
    if (singleIndex >= 0 && frozenList != null && frozenList.isNotEmpty() && currentProject != null) {
        SingleImageScreen(
            photos = frozenList,
            livePhotos = displayed,
            startIndex = singleIndex.coerceAtMost(frozenList.size - 1),
            project = currentProject,
            onClose = { singleIndex = -1; singleList = null },
            onRate = { id, r -> vm.setRating(id, r) },
        )
        return
    }

    BackHandler {
        if (selection.isNotEmpty()) vm.clearSelection() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (currentProject?.name ?: "") +
                            if (displayed.isNotEmpty()) "（${displayed.size}）" else ""
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        BrowserViewModel.Sort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text((if (s == sort) "✓ " else "　") + s.label) },
                                onClick = { vm.sort.value = s; sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { filterMenu = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "筛选")
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        BrowserViewModel.RatingFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text((if (f == filter) "✓ " else "　") + f.label) },
                                onClick = { vm.ratingFilter.value = f; filterMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text((if (hideUnrated) "✓ " else "　") + "隐藏未评分") },
                            onClick = { vm.hideUnrated.value = !hideUnrated; filterMenu = false },
                        )
                    }
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("生成全部标准预览") },
                            onClick = {
                                currentProject?.let { PreviewBuilder.get(context).start(it) }
                                moreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 XMP 评分") },
                            onClick = {
                                val p = currentProject
                                if (p != null) {
                                    scope.launch {
                                        val photos = displayed
                                        val n = withContext(Dispatchers.IO) {
                                            XmpExporter.export(context, p, photos)
                                        }
                                        Toast.makeText(context, "已导出 $n 个 XMP", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                moreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清理项目缓存") },
                            onClick = {
                                scope.launch { CacheManager.get(context).clearProject(projectId) }
                                moreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("批量删除照片…", color = MaterialTheme.colorScheme.error) },
                            onClick = { showDeleteDialog = true; moreMenu = false },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("已选 ${selection.size}")
                        RatingBar(
                            rating = 0,
                            onRate = { vm.setRatingForSelection(it) },
                            showClear = true,
                        )
                        TextButton(onClick = { vm.clearSelection() }) { Text("取消选择") }
                    }
                }
            }
        },
        modifier = Modifier.onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five -> {
                    if (selection.isNotEmpty()) {
                        val r = when (ev.key) {
                            Key.Zero -> 0; Key.One -> 1; Key.Two -> 2
                            Key.Three -> 3; Key.Four -> 4; else -> 5
                        }
                        vm.setRatingForSelection(r)
                        true
                    } else false
                }
                Key.Enter, Key.NumPadEnter -> {
                    val first = selection.firstOrNull()
                    val idx = displayed.indexOfFirst { it.id == first }
                    if (idx >= 0) { openSingle(idx); true } else false
                }
                else -> false
            }
        },
    ) { padding ->
        if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "这个项目还没有照片",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            // 滚动预取：把可视区之后两屏的缩略图提前拉进缓存
            androidx.compose.runtime.LaunchedEffect(gridState, displayed, currentProject?.id) {
                val proj = currentProject ?: return@LaunchedEffect
                val queued = mutableSetOf<String>()
                androidx.compose.runtime.snapshotFlow {
                    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                }.collect { last ->
                    if (last < 0) return@collect
                    val to = (last + 24).coerceAtMost(displayed.size)
                    for (i in (last + 1).coerceAtLeast(0) until to) {
                        val p = displayed.getOrNull(i) ?: continue
                        if (queued.add(p.id)) {
                            launch { ThumbnailCache.get(context).get(proj, p) }
                        }
                    }
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayed, key = { it.id }) { photo ->
                    PhotoCell(
                        photo = photo,
                        project = currentProject,
                        selected = photo.id in selection,
                        onClick = {
                            if (selection.isEmpty()) {
                                openSingle(displayed.indexOfFirst { it.id == photo.id })
                            } else {
                                vm.toggleSelect(photo.id)
                            }
                        },
                        onLongClick = { vm.toggleSelect(photo.id) },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        val displayedIds = displayed.map { it.id }.toSet()
        val unratedIds = allPhotos.filter { it.rating == 0 }.map { it.id }.toSet()
        val outsideFilterIds = allPhotos.map { it.id }.toSet() - displayedIds
        val filterActive = filter != BrowserViewModel.RatingFilter.ALL || hideUnrated
        val isLink = currentProject?.mode == "link"
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("批量删除照片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isLink) "链接模式：将同时删除储存卡上的源文件，不可恢复！"
                        else "将删除图库中的文件和缓存，不可恢复。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { vm.deletePhotos(unratedIds); showDeleteDialog = false },
                        enabled = unratedIds.isNotEmpty(),
                    ) { Text("删除未评分照片（${unratedIds.size}）") }
                    TextButton(
                        onClick = { vm.deletePhotos(outsideFilterIds); showDeleteDialog = false },
                        enabled = filterActive && outsideFilterIds.isNotEmpty(),
                    ) {
                        Text(
                            if (filterActive) "删除当前筛选之外的照片（${outsideFilterIds.size}）"
                            else "删除当前筛选之外的照片（先设置筛选）"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: PhotoEntity,
    project: ProjectEntity?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, photo.id, project?.id) {
        val p = project ?: return@produceState
        value = ThumbnailCache.get(context).get(p, photo)?.asImageBitmap()
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .border(width = if (selected) 3.dp else 0.dp, color = borderColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = photo.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        photo.fileName.substringAfterLast('.').uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (photo.rating > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD60A),
                            modifier = Modifier.size(12.dp).padding(end = 2.dp),
                        )
                        Text(
                            "${photo.rating}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
