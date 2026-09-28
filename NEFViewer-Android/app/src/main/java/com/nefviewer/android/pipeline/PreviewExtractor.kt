package com.nefviewer.android.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import com.nefviewer.android.nef.ByteArraySeekableInput
import com.nefviewer.android.nef.FileSeekableInput
import com.nefviewer.android.nef.NefInfo
import com.nefviewer.android.nef.NefParser
import com.nefviewer.android.nef.PreviewRef
import com.nefviewer.android.nef.SeekableInput
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/** SAF document URI 的随机访问实现 */
class PfdSeekableInput(context: Context, uri: Uri, knownSize: Long = -1) : SeekableInput {
    private val pfd: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("无法打开: $uri")
    private val channel = FileInputStream(pfd.fileDescriptor).channel
    override val size: Long = if (knownSize > 0) knownSize else pfd.statSize.toLong()

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        val bb = ByteBuffer.wrap(buffer, bufferOffset, length)
        var total = 0
        while (total < length) {
            val n = channel.read(bb, offset + total)
            if (n < 0) break
            total += n
        }
        return total
    }

    override fun close() = pfd.close()
}

/** 把 PhotoEntity 解析为可读的 NEF 字节源；调用方负责 close */
object PhotoInputResolver {
    fun open(context: Context, project: ProjectEntity, photo: PhotoEntity): SeekableInput {
        // URI 优先（链接模式 / SAF 图库副本）；否则应用私有图库文件
        photo.documentUri?.let {
            return PfdSeekableInput(context, Uri.parse(it), photo.fileSize)
        }
        val root = project.libraryPath ?: throw IllegalStateException("项目缺少 libraryPath")
        return FileSeekableInput(File(root, photo.relativePath ?: photo.fileName).absolutePath)
    }

    /** 已提取的 JPEG 预览字节也包一层，统一走解析/解码管线 */
    fun fromBytes(bytes: ByteArray): SeekableInput = ByteArraySeekableInput(bytes)
}

object PreviewExtractor {

    fun parse(input: SeekableInput): NefInfo = NefParser.parse(input)

    fun readJpeg(input: SeekableInput, ref: PreviewRef): ByteArray =
        NefParser.readPreviewBytes(input, ref)

    /**
     * 解码内嵌 JPEG，应用 EXIF 方向并缩放到最长边 targetMaxDim。
     * hardwareOut=true：方向+缩放由 GPU（hardware Canvas）完成，输出 HARDWARE bitmap
     * （显示零上传，但不可 compress/读像素）；false：软件输出（磁盘落盘用）。
     */
    fun decode(jpeg: ByteArray, orientation: Int, targetMaxDim: Int, hardwareOut: Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= targetMaxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // hardwareOut 时原图也直接解成 HARDWARE：方向+缩放变成 GPU→GPU，
            // 峰值内存少一份 181MB 软件拷贝（全尺寸解码内存风暴的成因之一）
            inPreferredConfig = if (hardwareOut) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw IllegalStateException("JPEG 解码失败")

        val (m, dims) = GpuImageOps.combinedMatrix(orientation, raw.width, raw.height, targetMaxDim)
        val out = GpuImageOps.drawTransformed(raw, m, dims.first, dims.second, hardwareOut)
        raw.recycle()
        return out
    }

    /** 软件 bitmap 原样转 HARDWARE（1:1 GPU 拷贝），供显示 */
    fun toHardware(src: Bitmap): Bitmap {
        val out = GpuImageOps.drawTransformed(src, Matrix(), src.width, src.height, true)
        src.recycle()
        return out
    }
}
