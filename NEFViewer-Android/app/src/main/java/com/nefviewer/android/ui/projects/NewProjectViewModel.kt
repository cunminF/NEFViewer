package com.nefviewer.android.ui.projects

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.data.SettingsRepository
import com.nefviewer.android.importer.LibraryImporter
import com.nefviewer.android.importer.SafTree
import com.nefviewer.android.importer.SourceFile
import com.nefviewer.android.pipeline.PreviewBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class NewProjectViewModel(app: Application) : AndroidViewModel(app) {

    enum class Step { Basics, Source, Importing }

    var step by mutableStateOf(Step.Basics)
    var name by mutableStateOf("")
    var mode by mutableStateOf(ProjectEntity.MODE_COPY)

    var sourceDisplay by mutableStateOf<String?>(null)
        private set
    private var sourceTreeUri: Uri? = null

    /** 可移除卷（读卡器）信息，用于「外接储存卡」按钮直跳 */
    var removableVolumeLabel by mutableStateOf<String?>(null)
        private set

    val files = mutableStateListOf<SourceFile>()
    var selected by mutableStateOf(setOf<Int>())
        private set
    var enumerateDone by mutableIntStateOf(-1)   // -1 = 未在扫描
        private set
    var enumerateTotal by mutableIntStateOf(0)
        private set
    var importDone by mutableIntStateOf(0)
        private set
    var importTotal by mutableIntStateOf(0)
        private set
    var rangeAnchor by mutableStateOf<Int?>(null)
    var rangeMode by mutableStateOf(false)

    init {
        val sm = app.getSystemService(StorageManager::class.java)
        removableVolumeLabel = sm?.storageVolumes
            ?.firstOrNull { it.isRemovable }
            ?.getDescription(app)
    }

    /** SAF picker 的初始位置：外接卷根目录或本机存储根目录 */
    fun initialUri(removable: Boolean): Uri {
        val context = getApplication<Application>()
        val sm = context.getSystemService(StorageManager::class.java)
        val vol = sm?.storageVolumes?.firstOrNull { it.isRemovable == removable }
        val volumeId = vol?.uuid ?: "primary"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "$volumeId:"
        )
    }

    /** SAF 选树回调（读写权限都持久化；写权限用于 XMP 导出/批量删除） */
    fun pickTree(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        sourceTreeUri = uri
        sourceDisplay = uri.lastPathSegment
        step = Step.Source
        enumerate()
    }

    private fun enumerate() {
        val context = getApplication<Application>()
        files.clear()
        selected = emptySet()
        rangeAnchor = null
        rangeMode = false
        enumerateDone = 0
        viewModelScope.launch {
            val tree = sourceTreeUri ?: return@launch
            val result = LibraryImporter.enumerateSafTree(context, tree) { n ->
                enumerateDone = n
            }
            files.addAll(result.sortedBy { it.name })
            enumerateTotal = files.size
            selected = files.indices.toSet()
            enumerateDone = -1
        }
    }

    fun toggle(index: Int) {
        val anchor = rangeAnchor
        if (anchor != null && anchor != index) {
            val range = minOf(anchor, index)..maxOf(anchor, index)
            selected = selected + range.toSet()
            rangeAnchor = null
            rangeMode = false
        } else {
            if (rangeMode) {
                rangeAnchor = index
            } else {
                selected = if (index in selected) selected - index else selected + index
            }
        }
    }

    fun setAnchor(index: Int) {
        rangeAnchor = if (rangeAnchor == index) null else index
    }

    fun toggleRangeMode() {
        rangeMode = !rangeMode
        if (!rangeMode) rangeAnchor = null
    }

    fun selectAll() {
        selected = files.indices.toSet()
    }

    fun selectNone() {
        selected = emptySet()
    }

    fun createProject(onDone: (String) -> Unit) {
        val context = getApplication<Application>()
        val db = AppDatabase.get(context)
        val settings = SettingsRepository.get(context)
        val id = UUID.randomUUID().toString()
        step = Step.Importing
        viewModelScope.launch {
            val customLibrary = settings.libraryTreeUri.first()?.let { Uri.parse(it) }
            var libraryPath: String? = null
            var libraryUri: String? = null
            if (mode == ProjectEntity.MODE_COPY) {
                if (customLibrary != null) {
                    // 在用户自选目录下建 NEF Viewer/<项目名-id> 子目录，保持整洁、便于删除
                    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, customLibrary)
                    val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val dir = root?.let { SafTree.ensureDir(it, "NEF Viewer/$safe-${id.take(6)}") }
                    libraryUri = dir?.uri?.toString()
                } else {
                    libraryPath = LibraryImporter.libraryDirFor(context, name, id).absolutePath
                }
            }
            val project = ProjectEntity(
                id = id,
                name = name.trim(),
                createdAt = System.currentTimeMillis(),
                mode = mode,
                sourceUri = sourceTreeUri?.toString(),
                sourceDisplayPath = sourceDisplay,
                libraryPath = libraryPath,
                libraryUri = libraryUri,
            )
            db.projectDao().upsert(project)
            val sel = selected.sorted().map { files[it] }
            importTotal = sel.size
            when {
                mode == ProjectEntity.MODE_LINK -> {
                    LibraryImporter.importLink(db, project, sel)
                    importDone = sel.size
                }
                libraryUri != null -> LibraryImporter.importCopyToTree(context, db, project, sel) { d, _ ->
                    importDone = d
                }
                else -> LibraryImporter.importCopy(context, db, project, sel) { d, _ -> importDone = d }
            }
            if (settings.autoBuildPreviews.first()) {
                PreviewBuilder.get(context).start(project)
            }
            onDone(id)
        }
    }
}
