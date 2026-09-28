package com.nefviewer.android.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nefviewer.android.data.AppDatabase
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.nef.NefParser
import com.nefviewer.android.nef.SeekableInput
import com.nefviewer.android.pipeline.PfdSeekableInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 导入源里的一个待选文件（SAF） */
data class SourceFile(
    val uri: Uri?,
    val name: String,
    val relativePath: String,
    val size: Long,
    val dateTaken: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
)

object LibraryImporter {

    /** 枚举 SAF 树下的全部 NEF（递归），并读 EXIF 拍摄时间/尺寸供勾选列表展示 */
    suspend fun enumerateSafTree(
        context: Context,
        treeUri: Uri,
        onCount: (Int) -> Unit = {},
    ): List<SourceFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val out = mutableListOf<Pair<DocumentFile, String>>()

        fun walk(dir: DocumentFile, prefix: String) {
            dir.listFiles().forEach { f ->
                if (f.isDirectory) {
                    walk(f, if (prefix.isEmpty()) f.name.orEmpty() else "$prefix/${f.name}")
                } else if (f.name?.substringAfterLast('.', "")?.equals("nef", true) == true) {
                    out += f to prefix
                }
            }
        }
        walk(root, "")

        val sem = Semaphore(4)
        val results = arrayOfNulls<SourceFile>(out.size)
        coroutineScope {
            out.mapIndexed { i, (doc, prefix) ->
                async {
                    sem.withPermit {
                        val meta = runCatching {
                            val input: SeekableInput = PfdSeekableInput(context, doc.uri, doc.length())
                            val info = try { NefParser.parse(input) } finally { input.close() }
                            val (w, h) = NefParser.normalizedSize(
                                info.largestPreview.width, info.largestPreview.height, info.orientation
                            )
                            Triple(info.dateTakenMs, w, h)
                        }.getOrNull()
                        results[i] = SourceFile(
                            uri = doc.uri,
                            name = doc.name ?: "unknown.nef",
                            relativePath = if (prefix.isEmpty()) doc.name ?: "unknown.nef" else "$prefix/${doc.name}",
                            size = doc.length(),
                            dateTaken = meta?.first,
                            width = meta?.second ?: 0,
                            height = meta?.third ?: 0,
                        )
                        onCount(i + 1)
                    }
                }
            }.forEach { it.await() }
        }
        results.filterNotNull()
    }

    /** 拷贝模式：把勾选的文件复制进图库目录（保留相对结构），写库 */
    suspend fun importCopy(
        context: Context,
        db: AppDatabase,
        project: ProjectEntity,
        selected: List<SourceFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val root = File(project.libraryPath ?: throw IllegalStateException("项目缺少 libraryPath"))
        root.mkdirs()
        val sem = Semaphore(4)
        val photos = arrayOfNulls<PhotoEntity>(selected.size)
        var done = 0
        coroutineScope {
            selected.mapIndexed { i, src ->
                async {
                    sem.withPermit {
                        runCatching {
                            val dest = File(root, src.relativePath)
                            dest.parentFile?.mkdirs()
                            if (!dest.isFile || dest.length() != src.size) {
                                openSource(context, src).use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                                }
                            }
                            photos[i] = PhotoEntity(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                fileName = src.name,
                                relativePath = src.relativePath,
                                documentUri = null,
                                rating = 0,
                                width = src.width,
                                height = src.height,
                                dateTaken = src.dateTaken,
                                fileSize = src.size,
                            )
                        }
                        done++
                        onProgress(done, selected.size)
                    }
                }
            }.forEach { it.await() }
        }
        insertChunked(db, photos.filterNotNull())
    }

    /** 拷贝到 SAF 图库目录（用户自选的本机文件夹）：保留相对结构，照片登记为副本 URI */
    suspend fun importCopyToTree(
        context: Context,
        db: AppDatabase,
        project: ProjectEntity,
        selected: List<SourceFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(
            context, android.net.Uri.parse(project.libraryUri)
        ) ?: throw IllegalStateException("图库目录不可用")
        val sem = Semaphore(4)
        val photos = arrayOfNulls<PhotoEntity>(selected.size)
        var done = 0
        coroutineScope {
            selected.mapIndexed { i, src ->
                async {
                    sem.withPermit {
                        runCatching {
                            val dir = SafTree.ensureDir(root, SafTree.parentOf(src.relativePath))
                            val doc = SafTree.createOrReplace(dir, src.name, "application/octet-stream")
                                ?: throw IllegalStateException("无法创建: ${src.name}")
                            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                                openSource(context, src).use { input -> input.copyTo(output, 1024 * 1024) }
                            }
                            photos[i] = PhotoEntity(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                fileName = src.name,
                                relativePath = src.relativePath,
                                documentUri = doc.uri.toString(),
                                rating = 0,
                                width = src.width,
                                height = src.height,
                                dateTaken = src.dateTaken,
                                fileSize = src.size,
                            )
                        }
                        done++
                        onProgress(done, selected.size)
                    }
                }
            }.forEach { it.await() }
        }
        insertChunked(db, photos.filterNotNull())
    }

    /** 链接模式：不复制，直接登记 document URI */
    suspend fun importLink(
        db: AppDatabase,
        project: ProjectEntity,
        selected: List<SourceFile>,
    ) = withContext(Dispatchers.IO) {
        val photos = selected.map { src ->
            PhotoEntity(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                fileName = src.name,
                relativePath = src.relativePath,
                documentUri = src.uri?.toString(),
                rating = 0,
                width = src.width,
                height = src.height,
                dateTaken = src.dateTaken,
                fileSize = src.size,
            )
        }
        insertChunked(db, photos)
    }

    private suspend fun insertChunked(db: AppDatabase, photos: List<PhotoEntity>) {
        photos.chunked(200).forEach { db.photoDao().insertAll(it) }
    }

    private fun openSource(context: Context, src: SourceFile): java.io.InputStream =
        context.contentResolver.openInputStream(
            src.uri ?: throw IllegalArgumentException("空来源: ${src.name}")
        ) ?: throw IllegalArgumentException("无法读取: ${src.name}")

    /** 新建 copy 项目的图库目录：外部应用私有 files/library/<项目名-短id>/ */
    fun libraryDirFor(context: Context, projectName: String, projectId: String): File {
        val safe = projectName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(context.getExternalFilesDir(null), "library/$safe-${projectId.take(6)}")
    }
}
