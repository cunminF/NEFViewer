package com.nefviewer.android.pipeline

import android.content.Context
import com.nefviewer.android.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 缓存统计与清理：缩略图 + 标准预览，按项目维度管理 */
class CacheManager(private val context: Context) {

    data class ProjectCacheInfo(
        val projectId: String,
        val projectName: String,
        val thumbBytes: Long,
        val previewBytes: Long,
    ) {
        val totalBytes: Long get() = thumbBytes + previewBytes
    }

    private fun dirSize(dir: File): Long =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    suspend fun compute(projects: List<ProjectEntity>): List<ProjectCacheInfo> =
        withContext(Dispatchers.IO) {
            projects.map { p ->
                ProjectCacheInfo(
                    projectId = p.id,
                    projectName = p.name,
                    thumbBytes = dirSize(ThumbnailCache.get(context).diskDir(p.id)),
                    previewBytes = dirSize(PreviewCache.get(context).diskDir(p.id)),
                )
            }
        }

    suspend fun clearProject(projectId: String) = withContext(Dispatchers.IO) {
        ThumbnailCache.get(context).diskDir(projectId).deleteRecursively()
        PreviewCache.get(context).diskDir(projectId).deleteRecursively()
    }

    /** 清理已删除项目留下的孤儿缓存目录，返回清理的字节数 */
    suspend fun clearOrphans(validProjectIds: Set<String>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        listOf(
            File(context.cacheDir, "thumbs"),
            File(context.cacheDir, "previews"),
        ).forEach { root ->
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in validProjectIds) {
                    freed += dirSize(dir)
                    dir.deleteRecursively()
                }
            }
        }
        freed
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        File(context.cacheDir, "thumbs").deleteRecursively()
        File(context.cacheDir, "previews").deleteRecursively()
    }

    companion object {
        @Volatile private var instance: CacheManager? = null
        fun get(context: Context): CacheManager =
            instance ?: synchronized(this) {
                instance ?: CacheManager(context.applicationContext).also { instance = it }
            }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
