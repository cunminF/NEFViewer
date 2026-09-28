package com.nefviewer.android.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 网格缩略图：内存 LruCache + 磁盘 640px JPEG，按 photoId 命名 */
class ThumbnailCache(private val context: Context) {

    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun diskDir(projectId: String): File = File(context.cacheDir, "thumbs/$projectId")

    private fun diskFile(projectId: String, photoId: String) = File(diskDir(projectId), "$photoId.jpg")

    fun peek(photoId: String): Bitmap? = mem.get(photoId)

    /** 三级：内存 → 磁盘 → 从 NEF 提取。失败返回 null（调用方画占位图） */
    suspend fun get(project: ProjectEntity, photo: PhotoEntity): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(photo.id)?.let { return@withContext it }

        val file = diskFile(project.id, photo.id)
        if (file.isFile) {
            decodeHardware(file)?.let {
                mem.put(photo.id, it)
                return@withContext it
            }
            file.delete()
        }

        runCatching {
            val input = PhotoInputResolver.open(context, project, photo)
            val sw = try {
                val info = PreviewExtractor.parse(input)
                // 预览分级里挑一个 ≥640 的最小档，省解码时间
                val ref = info.previews.lastOrNull { maxOf(it.width, it.height) >= 640 }
                    ?: info.largestPreview
                val jpeg = PreviewExtractor.readJpeg(input, ref)
                PreviewExtractor.decode(jpeg, info.orientation, 640, hardwareOut = false)
            } finally {
                input.close()
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { sw.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val hw = PreviewExtractor.toHardware(sw) // 回收 sw，返回 GPU 版
            mem.put(photo.id, hw)
            hw
        }.onFailure {
            android.util.Log.e("ThumbnailCache", "生成失败 ${photo.fileName}", it)
        }.getOrNull()
    }

    /** 磁盘 JPEG 直接解码为 HARDWARE bitmap（GPU 存储，显示零上传） */
    private fun decodeHardware(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun remove(projectId: String, photoId: String) {
        mem.remove(photoId)
        diskFile(projectId, photoId).delete()
    }

    companion object {
        @Volatile private var instance: ThumbnailCache? = null
        fun get(context: Context): ThumbnailCache =
            instance ?: synchronized(this) {
                instance ?: ThumbnailCache(context.applicationContext).also { instance = it }
            }
    }
}
