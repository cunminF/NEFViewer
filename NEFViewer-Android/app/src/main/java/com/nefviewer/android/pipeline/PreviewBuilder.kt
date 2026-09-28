package com.nefviewer.android.pipeline

import android.content.Context
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 全量标准档预渲染：全局单例，驱动主界面进度浮层 */
class PreviewBuilder(private val context: Context) {

    data class Progress(
        val projectId: String,
        val projectName: String,
        val done: Int,
        val total: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    val isRunning: Boolean get() = job?.isActive == true

    fun start(project: ProjectEntity) {
        if (isRunning) return
        job = scope.launch {
            val db = AppDatabase.get(context)
            val photos = db.photoDao().byProject(project.id)
            _progress.value = Progress(project.id, project.name, 0, photos.size)
            var done = 0
            for (chunk in photos.chunked(4)) {
                if (job?.isActive != true) break
                chunk.map { photo ->
                    async { buildBoth(project, photo) }
                }.forEach { it.await(); done++ }
                _progress.value = Progress(project.id, project.name, done, photos.size)
            }
            _progress.value = null
        }
    }

    /**
     * 一次解码双档输出（3200 标准 + 640 缩略图），均落盘：
     * - 两档都缺：开 NEF 解码一次 3200，缩略图由它缩放得到
     * - 只缺缩略图：直接从已存在的 3200 档缩放，不碰 NEF
     */
    private suspend fun buildBoth(project: ProjectEntity, photo: PhotoEntity) {
        val cache = PreviewCache.get(context)
        val thumbs = ThumbnailCache.get(context)
        val pFile = java.io.File(cache.diskDir(project.id), "${photo.id}.jpg")
        val tFile = java.io.File(thumbs.diskDir(project.id), "${photo.id}.jpg")
        if (pFile.isFile && tFile.isFile) return

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                var sw = if (!pFile.isFile) {
                    val input = PhotoInputResolver.open(context, project, photo)
                    try {
                        val info = PreviewExtractor.parse(input)
                        val ref = info.previews.lastOrNull { maxOf(it.width, it.height) >= 3200 }
                            ?: info.largestPreview
                        val jpeg = PreviewExtractor.readJpeg(input, ref)
                        PreviewExtractor.decode(jpeg, info.orientation, 3200, hardwareOut = false)
                    } finally {
                        input.close()
                    }
                } else null

                if (sw != null) {
                    pFile.parentFile?.mkdirs()
                    pFile.outputStream().use { sw.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                }

                if (!tFile.isFile) {
                    // 缩略图源：刚解码的 3200（已方向归一）或磁盘上的 3200 档
                    val src = sw ?: android.graphics.BitmapFactory.decodeFile(pFile.absolutePath)
                    if (src != null) {
                        val (m, dims) = GpuImageOps.combinedMatrix(1, src.width, src.height, 640)
                        val tsw = GpuImageOps.drawTransformed(src, m, dims.first, dims.second, hardwareOut = false)
                        tFile.parentFile?.mkdirs()
                        tFile.outputStream().use { tsw.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                        tsw.recycle()
                    }
                }
                sw?.recycle()
            }.onFailure {
                android.util.Log.e("PreviewBuilder", "预渲染失败 ${photo.fileName}", it)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = null
    }

    companion object {
        @Volatile private var instance: PreviewBuilder? = null
        fun get(context: Context): PreviewBuilder =
            instance ?: synchronized(this) {
                instance ?: PreviewBuilder(context.applicationContext).also { instance = it }
            }
    }
}
