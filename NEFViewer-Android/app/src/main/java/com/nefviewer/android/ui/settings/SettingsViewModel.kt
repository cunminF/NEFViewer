package com.nefviewer.android.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.SettingsRepository
import com.nefviewer.android.editor.ExternalEditor
import com.nefviewer.android.pipeline.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository.get(app)
    private val db = AppDatabase.get(app)

    val autoBuildPreviews: StateFlow<Boolean> = settings.autoBuildPreviews
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val preferredEditor: StateFlow<String?> = settings.preferredEditor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val libraryDisplay: StateFlow<String?> = settings.libraryTreeDisplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setLibrary(uri: android.net.Uri) = viewModelScope.launch {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        settings.setLibraryTree(uri.toString(), uri.lastPathSegment)
    }

    fun resetLibrary() = viewModelScope.launch {
        settings.setLibraryTree(null, null)
    }

    var cacheInfos by mutableStateOf<List<CacheManager.ProjectCacheInfo>?>(null)
        private set
    var editorCandidates by mutableStateOf<List<ExternalEditor.EditorApp>?>(null)
        private set
    var lastMessage by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            editorCandidates = withContext(Dispatchers.IO) {
                ExternalEditor.candidateEditors(app)
            }
        }
        refreshCache()
    }

    fun refreshCache() {
        viewModelScope.launch {
            val projects = db.projectDao().observeAll().first()
            cacheInfos = CacheManager.get(getApplication()).compute(projects)
        }
    }

    fun setAutoBuild(value: Boolean) = viewModelScope.launch {
        settings.setAutoBuildPreviews(value)
    }

    fun setEditor(flattened: String?) = viewModelScope.launch {
        settings.setPreferredEditor(flattened)
    }

    fun clearProjectCache(projectId: String) = viewModelScope.launch {
        CacheManager.get(getApplication()).clearProject(projectId)
        refreshCache()
    }

    fun clearOrphans() = viewModelScope.launch {
        val ids = db.projectDao().observeAll().first().map { it.id }.toSet()
        val freed = CacheManager.get(getApplication()).clearOrphans(ids)
        lastMessage = "已清理 ${CacheManager.formatBytes(freed)}"
        refreshCache()
    }

    fun clearAll() = viewModelScope.launch {
        CacheManager.get(getApplication()).clearAll()
        lastMessage = "已全部清理"
        refreshCache()
    }

    fun consumeMessage() {
        lastMessage = null
    }
}
