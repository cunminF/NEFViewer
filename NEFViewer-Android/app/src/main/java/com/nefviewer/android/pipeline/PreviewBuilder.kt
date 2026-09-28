package com.nefviewer.android.pipeline

import android.content.Context
import com.nefviewer.android.data.AppDatabase
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
            val cache = PreviewCache.get(context)
            val photos = db.photoDao().byProject(project.id)
            _progress.value = Progress(project.id, project.name, 0, photos.size)
            var done = 0
            for (chunk in photos.chunked(4)) {
                if (job?.isActive != true) break
                chunk.map { photo ->
                    async { cache.ensureStandardOnDisk(project, photo) }
                }.forEach { it.await(); done++ }
                _progress.value = Progress(project.id, project.name, done, photos.size)
            }
            _progress.value = null
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
