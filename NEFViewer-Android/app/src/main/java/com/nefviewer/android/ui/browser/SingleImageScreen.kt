package com.nefviewer.android.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.data.SettingsRepository
import com.nefviewer.android.editor.ExternalEditor
import com.nefviewer.android.pipeline.PreviewCache
import com.nefviewer.android.pipeline.ThumbnailCache
import com.nefviewer.android.ui.components.RatingBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SingleImageScreen(
    photos: List<PhotoEntity>,
    livePhotos: List<PhotoEntity>,
    startIndex: Int,
    project: ProjectEntity,
    onClose: () -> Unit,
    onRate: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex) { photos.size }
    var zoomed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val settings = remember { SettingsRepository.get(context) }
    val preferredEditor by settings.preferredEditor.collectAsState(initial = null)

    /** photos 是进入单图时的冻结快照（页序稳定）；评分显示走 livePhotos 实时映射 */
    val ratingMap = remember(livePhotos) { livePhotos.associate { it.id to it.rating } }

    fun openEditor(photo: PhotoEntity) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                ExternalEditor.contentUriFor(context, project, photo)
            }
            val editor = preferredEditor?.let { flat ->
                withContext(Dispatchers.IO) { ExternalEditor.candidateEditors(context, uri) }
                    .firstOrNull { it.flattened == flat }
            }
            if (!ExternalEditor.open(context, uri, editor)) {
                Toast.makeText(context, "没有找到能打开 NEF 的应用，可试试分享 JPEG", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 分享 3200px 标准预览 JPEG（像素蛋糕等未注册 NEF 编辑的应用可走分享） */
    fun shareJpeg(photo: PhotoEntity) {
        scope.launch {
            val cache = PreviewCache.get(context)
            withContext(Dispatchers.IO) { cache.ensureStandardOnDisk(project, photo) }
            val file = java.io.File(cache.diskDir(project.id), "${photo.id}.jpg")
            if (!file.isFile) {
                Toast.makeText(context, "预览生成失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "com.nefviewer.android.fileprovider", file,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享标准预览 JPEG"))
        }
    }

    BackHandler(onBack = onClose)

    // 翻页：作废旧全尺寸解码，预取 ±2 标准档（翻页轻路径）
    LaunchedEffect(pagerState.currentPage) {
        PreviewCache.get(context).bumpGeneration()
        val cache = PreviewCache.get(context)
        for (i in pagerState.currentPage - 2..pagerState.currentPage + 2) {
            photos.getOrNull(i)?.let { launch { cache.standard(project, it) } }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        true
                    }
                    Key.DirectionRight -> {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        true
                    }
                    Key.Escape -> { onClose(); true }
                    Key.E -> {
                        photos.getOrNull(pagerState.currentPage)?.let { openEditor(it) }
                        true
                    }
                    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five -> {
                        val r = when (ev.key) {
                            Key.Zero -> 0; Key.One -> 1; Key.Two -> 2
                            Key.Three -> 3; Key.Four -> 4; else -> 5
                        }
                        photos.getOrNull(pagerState.currentPage)?.let { onRate(it.id, r) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            ZoomablePhoto(
                project = project,
                photo = photo,
                onZoomChanged = { if (page == pagerState.currentPage) zoomed = it },
                onDoubleTap = { openEditor(photo) },
            )
        }

        val current = photos.getOrNull(pagerState.currentPage)
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    current?.fileName ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { current?.let { openEditor(it) } }) {
                Icon(Icons.Default.Edit, contentDescription = "外部编辑", tint = Color.White)
            }
            IconButton(onClick = { current?.let { shareJpeg(it) } }) {
                Icon(Icons.Default.Share, contentDescription = "分享 JPEG", tint = Color.White)
            }
        }

        if (current != null) {
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                RatingBar(
                    rating = ratingMap[current.id] ?: current.rating,
                    onRate = { onRate(current.id, it) },
                    starColor = Color(0xFFFFD60A),
                    showClear = true,
                )
            }
        }
    }
}

/** 单页照片：缩略图 → 标准档渐进显示，放大超过 1.2× 解码全尺寸；双击开编辑器 */
@Composable
private fun ZoomablePhoto(
    project: ProjectEntity,
    photo: PhotoEntity,
    onZoomChanged: (Boolean) -> Unit,
    onDoubleTap: () -> Unit,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fullBmp by remember { mutableStateOf<ImageBitmap?>(null) }

    val bitmap by produceState<ImageBitmap?>(null, photo.id) {
        value = ThumbnailCache.get(context).get(project, photo)?.asImageBitmap()
        PreviewCache.get(context).standard(project, photo)?.let { value = it.asImageBitmap() }
    }

    LaunchedEffect(photo.id) {
        scale = 1f
        offset = Offset.Zero
        fullBmp = null
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.05f)
        if (scale > 1.2f && fullBmp == null) {
            val cache = PreviewCache.get(context)
            val gen = cache.generation.get()
            cache.fullSize(project, photo, gen)?.let {
                if (scale > 1.2f) fullBmp = it.asImageBitmap()
            }
        }
    }

    val shown = fullBmp ?: bitmap
    Box(Modifier.fillMaxSize()) {
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(photo.id) {
                        // 未放大时的单指滑动不接管（让给 Pager 翻页）；
                        // 双指捏合或已放大时的单指平移才消费手势
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var takeOver = scale > 1.05f
                            if (!takeOver) {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2) { takeOver = true; break }
                                    if (pressed == 0) break
                                    if (event.changes.first().positionChanged()) break // 单指拖动 → Pager
                                }
                                if (!takeOver) return@awaitEachGesture
                            }
                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                                if (event.changes.none { it.pressed }) break
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    }
                    .pointerInput(photo.id) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = {
                scale = (scale / 1.5f).coerceAtLeast(1f)
                if (scale <= 1f) offset = Offset.Zero
            }) {
                Icon(Icons.Default.ZoomOut, contentDescription = "缩小", tint = Color.White)
            }
            IconButton(onClick = {
                scale = (scale * 1.5f).coerceAtMost(8f)
            }) {
                Icon(Icons.Default.ZoomIn, contentDescription = "放大", tint = Color.White)
            }
        }
    }
}
