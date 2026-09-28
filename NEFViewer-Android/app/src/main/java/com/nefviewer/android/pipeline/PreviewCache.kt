package com.nefviewer.android.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 单图预览：标准档 3200px 磁盘持久化 + 全尺寸单槽内存（generation 机制：
 * 翻页时递增 generation，旧解码完成后发现代次过期即丢弃，不阻塞快速连翻）。
 */
class PreviewCache(private val context: Context) {

    fun diskDir(projectId: String): File = File(context.cacheDir, "previews/$projectId")
    private fun diskFile(projectId: String, photoId: String) = File(diskDir(projectId), "$photoId.jpg")

    val generation = AtomicLong(0)

    @Volatile private var fullSlot: Pair<String, Bitmap>? = null

    /** 标准档内存缓存（HARDWARE bitmap，快速连翻不重复解码） */
    private val standardMem = object : LruCache<String, Bitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bumpGeneration(): Long = generation.incrementAndGet()

    private fun decodeHardware(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** 标准档 3200px；内存 → 磁盘 → 从 NEF 提取并落盘。返回 HARDWARE bitmap */
    suspend fun standard(project: ProjectEntity, photo: PhotoEntity): Bitmap? = withContext(Dispatchers.IO) {
        standardMem.get(photo.id)?.let { return@withContext it }
        val file = diskFile(project.id, photo.id)
        if (file.isFile) {
            decodeHardware(file)?.let {
                standardMem.put(photo.id, it)
                return@withContext it
            }
            file.delete()
        }
        runCatching {
            val sw = buildStandard(project, photo)
            file.parentFile?.mkdirs()
            file.outputStream().use { sw.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val hw = PreviewExtractor.toHardware(sw)
            standardMem.put(photo.id, hw)
            hw
        }.getOrNull()
    }

    /** 只落盘不保留 Bitmap（全量预渲染用），已存在则跳过；返回是否新生成 */
    suspend fun ensureStandardOnDisk(project: ProjectEntity, photo: PhotoEntity): Boolean =
        withContext(Dispatchers.IO) {
            val file = diskFile(project.id, photo.id)
            if (file.isFile) return@withContext false
            runCatching {
                val bmp = buildStandard(project, photo)
                file.parentFile?.mkdirs()
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
            }.isSuccess
        }

    private fun buildStandard(project: ProjectEntity, photo: PhotoEntity): Bitmap {
        val input = PhotoInputResolver.open(context, project, photo)
        return try {
            val info = PreviewExtractor.parse(input)
            val ref = info.previews.lastOrNull { maxOf(it.width, it.height) >= 3200 }
                ?: info.largestPreview
            val jpeg = PreviewExtractor.readJpeg(input, ref)
            PreviewExtractor.decode(jpeg, info.orientation, 3200, hardwareOut = false)
        } finally {
            input.close()
        }
    }

    /**
     * 全尺寸（仅放大超过阈值时调用），输出 HARDWARE bitmap。gen 必须取调用时的
     * generation；解码完成后若 generation 已变，结果直接回收，保证快速翻页不被大解码堵住。
     * 同一时刻全尺寸解码只有一个在飞：同照片的并发调用共享同一个 Deferred，
     * 换照片则取消上一张——捏合手势每帧重启 LaunchedEffect，没有去重就是 N 个
     * 181MB 解码并发（实测内存风暴 → 主线程饿死 → ANR 的元凶）
     */
    private val fullMutex = Mutex()
    private var fullJob: Pair<String, Deferred<Bitmap?>>? = null

    suspend fun fullSize(project: ProjectEntity, photo: PhotoEntity, gen: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            fullSlot?.let { (id, bmp) -> if (id == photo.id) return@withContext bmp }
            val deferred = fullMutex.withLock {
                val cur = fullJob
                if (cur != null && cur.first == photo.id && cur.second.isActive) {
                    cur.second
                } else {
                    cur?.second?.cancel()
                    async { decodeFull(project, photo, gen) }.also { fullJob = photo.id to it }
                }
            }
            deferred.await()
        }

    private suspend fun decodeFull(project: ProjectEntity, photo: PhotoEntity, gen: Long): Bitmap? {
        val bmp = runCatching {
            val input = PhotoInputResolver.open(context, project, photo)
            try {
                val info = PreviewExtractor.parse(input)
                val jpeg = PreviewExtractor.readJpeg(input, info.largestPreview)
                PreviewExtractor.decode(jpeg, info.orientation, Int.MAX_VALUE, hardwareOut = true)
            } finally {
                input.close()
            }
        }.getOrNull() ?: return null
        if (!currentCoroutineContext().isActive || generation.get() != gen) {
            bmp.recycle()
            return null
        }
        fullSlot = photo.id to bmp
        return bmp
    }

    /** 只丢引用不 recycle：旧页面可能还在 Compose 里绘制这张 bitmap，recycle 会崩，交给 GC */
    fun clearFullSlot() {
        fullSlot = null
    }

    companion object {
        @Volatile private var instance: PreviewCache? = null
        fun get(context: Context): PreviewCache =
            instance ?: synchronized(this) {
                instance ?: PreviewCache(context.applicationContext).also { instance = it }
            }
    }
}
