package com.nefviewer.android.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModel(
    app: Application,
    private val projectId: String,
) : AndroidViewModel(app) {

    enum class Sort(val label: String) { RATING_DESC("评分从高到低"), DATE_ASC("拍摄时间") }
    enum class RatingFilter(val label: String) {
        ALL("全部"), GE3("≥3 分"), GE4("≥4 分"), ONLY5("仅 5 分"), LE2("≤2 分")
    }

    private val db = AppDatabase.get(app)

    private val projectIdFlow = MutableStateFlow(projectId)

    val project: StateFlow<ProjectEntity?> = projectIdFlow
        .flatMapLatest { id ->
            kotlinx.coroutines.flow.flow {
                emit(db.projectDao().get(id))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val photos: StateFlow<List<PhotoEntity>> = projectIdFlow
        .flatMapLatest { db.photoDao().observeByProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部照片（不过滤），批量删除计算用 */
    val allPhotos: StateFlow<List<PhotoEntity>> = photos

    val sort = MutableStateFlow(Sort.RATING_DESC)
    val ratingFilter = MutableStateFlow(RatingFilter.ALL)
    val hideUnrated = MutableStateFlow(false)

    /** 排序+筛选后的展示列表（视图层只碰这份快照） */
    val displayed: StateFlow<List<PhotoEntity>> =
        combine(photos, sort, ratingFilter, hideUnrated) { list, s, f, hide ->
            var out = when (f) {
                RatingFilter.ALL -> list
                RatingFilter.GE3 -> list.filter { it.rating >= 3 }
                RatingFilter.GE4 -> list.filter { it.rating >= 4 }
                RatingFilter.ONLY5 -> list.filter { it.rating == 5 }
                RatingFilter.LE2 -> list.filter { it.rating in 1..2 }
            }
            if (hide) out = out.filter { it.rating > 0 }
            when (s) {
                Sort.RATING_DESC -> out.sortedWith(
                    compareByDescending<PhotoEntity> { it.rating }.thenBy { it.dateTaken ?: 0 }
                )
                Sort.DATE_ASC -> out.sortedBy { it.dateTaken ?: 0 }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selection = MutableStateFlow<Set<String>>(emptySet())

    fun toggleSelect(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectOnly(id: String) {
        selection.value = setOf(id)
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun setRating(photoId: String, rating: Int) = viewModelScope.launch {
        db.photoDao().setRating(photoId, rating)
    }

    fun setRatingForSelection(rating: Int) = viewModelScope.launch {
        val ids = selection.value.toList()
        if (ids.isNotEmpty()) db.photoDao().setRatingBatch(ids, rating)
    }

    /**
     * 批量删除：删文件（链接模式删源文件/SAF 副本，拷贝模式删图库文件）、
     * 删缓存、删数据库记录。
     */
    fun deletePhotos(ids: Set<String>) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val app = getApplication<Application>()
        val targets = db.photoDao().byProject(projectId).filter { it.id in ids }
        withContext(Dispatchers.IO) {
            targets.forEach { p ->
                runCatching {
                    p.documentUri?.let {
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(app, android.net.Uri.parse(it))?.delete()
                    } ?: proj.libraryPath?.let {
                        java.io.File(it, p.relativePath ?: p.fileName).delete()
                    }
                }
                com.nefviewer.android.pipeline.ThumbnailCache.get(app).remove(proj.id, p.id)
                java.io.File(
                    com.nefviewer.android.pipeline.PreviewCache.get(app).diskDir(proj.id),
                    "${p.id}.jpg",
                ).delete()
            }
            db.photoDao().deleteByIds(targets.map { it.id })
        }
        selection.value = emptySet()
    }
}
