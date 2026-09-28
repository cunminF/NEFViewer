package com.nefviewer.android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.pipeline.CacheManager
import com.nefviewer.android.pipeline.PreviewBuilder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ProjectListViewModel(app: Application) : AndroidViewModel(app) {

    data class ProjectRow(
        val project: ProjectEntity,
        val photoCount: Int,
    )

    private val db = AppDatabase.get(app)

    val rows: StateFlow<List<ProjectRow>> =
        combine(
            db.projectDao().observeAll(),
            db.photoDao().observeCounts(),
        ) { projects, counts ->
            val countMap = counts.associate { it.projectId to it.cnt }
            projects.map { ProjectRow(it, countMap[it.id] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(id: String, name: String) = viewModelScope.launch {
        db.projectDao().rename(id, name.trim())
    }

    /** 只删数据库记录、缓存和拷贝进来的文件；链接模式绝不动源文件 */
    fun delete(row: ProjectRow) = viewModelScope.launch {
        val p = row.project
        db.photoDao().deleteByProject(p.id)
        db.projectDao().delete(p.id)
        CacheManager.get(getApplication()).clearProject(p.id)
        if (p.mode == ProjectEntity.MODE_COPY) {
            p.libraryPath?.let { File(it).deleteRecursively() }
            p.libraryUri?.let {
                androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(getApplication(), android.net.Uri.parse(it))?.delete()
            }
        }
    }

    fun buildPreviews(project: ProjectEntity) {
        PreviewBuilder.get(getApplication()).start(project)
    }

    fun clearCache(projectId: String) = viewModelScope.launch {
        CacheManager.get(getApplication()).clearProject(projectId)
    }
}
