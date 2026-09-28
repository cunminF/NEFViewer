package com.nefviewer.android.editor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import java.io.File

object ExternalEditor {

    const val MIME_NEF = "image/x-nikon-nef"

    data class EditorApp(
        val label: String,
        val packageName: String,
        val activityName: String,
    ) {
        val flattened: String get() = "$packageName/$activityName"
    }

    fun contentUriFor(context: Context, project: ProjectEntity, photo: PhotoEntity): Uri {
        photo.documentUri?.let { return Uri.parse(it) }
        val root = project.libraryPath ?: throw IllegalStateException("项目缺少 libraryPath")
        return FileProvider.getUriForFile(
            context,
            "com.nefviewer.android.fileprovider",
            File(root, photo.relativePath ?: photo.fileName),
        )
    }

    /** 查询声称能编辑/查看图片的应用（EDIT 优先，VIEW 兜底合并） */
    fun candidateEditors(context: Context, uri: Uri): List<EditorApp> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, EditorApp>()
        fun query(action: String, mime: String) {
            val intent = Intent(action).setDataAndType(uri, mime)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { ri ->
                val key = ri.activityInfo.packageName + "/" + ri.activityInfo.name
                seen.putIfAbsent(
                    key,
                    EditorApp(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                    ),
                )
            }
        }
        query(Intent.ACTION_EDIT, MIME_NEF)
        query(Intent.ACTION_EDIT, "image/*")
        query(Intent.ACTION_VIEW, MIME_NEF)
        query(Intent.ACTION_VIEW, "image/*")
        return seen.values.toList()
    }

    /** 用指定（或系统默认）编辑器打开；返回是否成功发出 */
    fun open(context: Context, uri: Uri, editor: EditorApp?): Boolean {
        val intents = listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW).flatMap { action ->
            listOf(MIME_NEF, "image/*").map { mime ->
                Intent(action).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        for (base in intents) {
            val intent = Intent(base)
            if (editor != null) {
                intent.component = ComponentName(editor.packageName, editor.activityName)
            }
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                if (editor != null) break // 指定编辑器失败不必降级重试同一 component
            }
        }
        return false
    }

    /** 不依赖具体文件，按 MIME 查询可编辑/查看图片的应用（设置页用） */
    fun candidateEditors(context: Context): List<EditorApp> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, EditorApp>()
        fun query(action: String, mime: String) {
            val intent = Intent(action).setType(mime)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { ri ->
                val key = ri.activityInfo.packageName + "/" + ri.activityInfo.name
                seen.putIfAbsent(
                    key,
                    EditorApp(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                    ),
                )
            }
        }
        query(Intent.ACTION_EDIT, "image/*")
        query(Intent.ACTION_VIEW, "image/*")
        return seen.values.toList()
    }
}
