package com.nefviewer.android.nef

import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 可随机访问的字节源。拷贝模式用本地文件实现；链接模式（SAF）由 Android 侧用
 * ParcelFileDescriptor 实现同一接口。
 */
interface SeekableInput {
    val size: Long
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset): Int
    fun close()
}

class FileSeekableInput(path: String) : SeekableInput {
    private val raf = RandomAccessFile(path, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        raf.seek(offset)
        var total = 0
        while (total < length) {
            val n = raf.read(buffer, bufferOffset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    override fun close() = raf.close()
}

class ByteArraySeekableInput(private val bytes: ByteArray) : SeekableInput {
    override val size: Long get() = bytes.size.toLong()
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        val n = minOf(length, bytes.size - offset.toInt()).coerceAtLeast(0)
        System.arraycopy(bytes, offset.toInt(), buffer, bufferOffset, n)
        return n
    }
    override fun close() {}
}

/** 一段内嵌 JPEG 预览的位置与尺寸（像素尺寸来自 IFD；为 0 时需读 SOF 探测） */
data class PreviewRef(
    val offset: Long,
    val length: Long,
    val width: Int,
    val height: Int,
) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

data class NefInfo(
    val orientation: Int,          // EXIF 1-8
    val dateTakenMs: Long?,
    val make: String?,
    val model: String?,
    val previews: List<PreviewRef>, // 按像素从大到小
) {
    val largestPreview: PreviewRef get() = previews.first()
    val smallestPreview: PreviewRef get() = previews.last()
}

class NefParseException(message: String) : Exception(message)

/**
 * 最小 TIFF/NEF 解析器：只找内嵌 JPEG 预览与少量 EXIF，不解 RAW。
 * NEF = classic TIFF（magic 42）；JPEG 预览 IFD 的 Compression 为 6/7，
 * 数据位置在 JPEGInterchangeFormat(0x0201)/Length(0x0202)，兜底 StripOffsets。
 */
object NefParser {

    private const val TAG_WIDTH = 0x0100
    private const val TAG_HEIGHT = 0x0101
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_STRIP_COUNTS = 0x0117
    private const val TAG_DATETIME = 0x0132
    private const val TAG_SUBIFDS = 0x014A
    private const val TAG_JPEG_OFFSET = 0x0201
    private const val TAG_JPEG_LENGTH = 0x0202
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATETIME_ORIGINAL = 0x9003

    private class IfdEntry(val tag: Int, val type: Int, val count: Long, val valueOffset: Long, val inline: Boolean)

    private class Reader(private val input: SeekableInput) {
        var littleEndian = true
        private val buf8 = ByteArray(8)

        fun bytes(offset: Long, length: Int): ByteArray {
            val b = ByteArray(length)
            val n = input.readAt(offset, b)
            if (n < length) throw NefParseException("读取越界 offset=$offset len=$length")
            return b
        }

        fun u16(offset: Long): Int {
            input.readAt(offset, buf8, 0, 2)
            return if (littleEndian) {
                (buf8[0].toInt() and 0xFF) or ((buf8[1].toInt() and 0xFF) shl 8)
            } else {
                ((buf8[0].toInt() and 0xFF) shl 8) or (buf8[1].toInt() and 0xFF)
            }
        }

        fun u32(offset: Long): Long {
            input.readAt(offset, buf8, 0, 4)
            return if (littleEndian) {
                (buf8[0].toLong() and 0xFF) or ((buf8[1].toLong() and 0xFF) shl 8) or
                    ((buf8[2].toLong() and 0xFF) shl 16) or ((buf8[3].toLong() and 0xFF) shl 24)
            } else {
                ((buf8[0].toLong() and 0xFF) shl 24) or ((buf8[1].toLong() and 0xFF) shl 16) or
                    ((buf8[2].toLong() and 0xFF) shl 8) or (buf8[3].toLong() and 0xFF)
            }
        }
    }

    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    fun parse(input: SeekableInput): NefInfo {
        val r = Reader(input)
        val header = r.bytes(0, 8)
        val bo = String(header, 0, 2, Charsets.US_ASCII)
        when (bo) {
            "II" -> r.littleEndian = true
            "MM" -> r.littleEndian = false
            else -> throw NefParseException("不是 TIFF/NEF 文件")
        }
        if (r.u16(2) != 42) throw NefParseException("不是 classic TIFF（magic != 42，BigTIFF 不支持）")

        val previews = mutableListOf<PreviewRef>()
        var orientation = 1
        var make: String? = null
        var model: String? = null
        var dateStr: String? = null
        val visited = HashSet<Long>()

        fun readIfdEntries(offset: Long): List<IfdEntry> {
            val count = r.u16(offset)
            if (count > 4096) throw NefParseException("IFD 条目数异常: $count")
            val raw = r.bytes(offset + 2, count * 12)
            return (0 until count).map { i ->
                val base = i * 12
                fun e16(p: Int) = if (r.littleEndian) {
                    (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8)
                } else {
                    ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
                }
                fun e32(p: Int) = if (r.littleEndian) {
                    (raw[p].toInt() and 0xFF).toLong() or ((raw[p + 1].toInt() and 0xFF).toLong() shl 8) or
                        ((raw[p + 2].toInt() and 0xFF).toLong() shl 16) or ((raw[p + 3].toInt() and 0xFF).toLong() shl 24)
                } else {
                    ((raw[p].toInt() and 0xFF).toLong() shl 24) or ((raw[p + 1].toInt() and 0xFF).toLong() shl 16) or
                        ((raw[p + 2].toInt() and 0xFF).toLong() shl 8) or (raw[p + 3].toInt() and 0xFF).toLong()
                }
                val tag = e16(base)
                val type = e16(base + 2)
                val cnt = e32(base + 4)
                val total = typeSize(type) * cnt
                IfdEntry(tag, type, cnt, if (total <= 4) (offset + 2 + base + 8) else e32(base + 8), total <= 4)
            }
        }

        fun IfdEntry.uintValues(): LongArray {
            val out = LongArray(count.toInt().coerceAtMost(4096))
            val b = r.bytes(this.valueOffset, (typeSize(type) * out.size).toInt())
            for (i in out.indices) {
                out[i] = when (type) {
                    3 -> if (r.littleEndian) (b[i * 2].toInt() and 0xFF or ((b[i * 2 + 1].toInt() and 0xFF) shl 8)).toLong()
                        else ((b[i * 2].toInt() and 0xFF) shl 8 or (b[i * 2 + 1].toInt() and 0xFF)).toLong()
                    4, 13 -> if (r.littleEndian) (b[i * 4].toInt() and 0xFF).toLong() or ((b[i * 4 + 1].toInt() and 0xFF).toLong() shl 8) or
                        ((b[i * 4 + 2].toInt() and 0xFF).toLong() shl 16) or ((b[i * 4 + 3].toInt() and 0xFF).toLong() shl 24)
                        else ((b[i * 4].toInt() and 0xFF).toLong() shl 24) or ((b[i * 4 + 1].toInt() and 0xFF).toLong() shl 16) or
                        ((b[i * 4 + 2].toInt() and 0xFF).toLong() shl 8) or (b[i * 4 + 3].toInt() and 0xFF).toLong()
                    1, 7 -> (b[i].toInt() and 0xFF).toLong()
                    else -> 0
                }
            }
            return out
        }

        fun IfdEntry.asciiValue(): String {
            val b = r.bytes(valueOffset, (typeSize(type) * count).toInt().coerceAtMost(256))
            val end = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
            return String(b, 0, end, Charsets.US_ASCII).trim()
        }

        fun walkIfd(offset: Long, depth: Int) {
            if (offset <= 0 || offset >= input.size || !visited.add(offset) || depth > 8) return
            val entries = readIfdEntries(offset)
            val byTag = entries.associateBy { it.tag }

            val compression = byTag[TAG_COMPRESSION]?.uintValues()?.firstOrNull()
            val jpegOff = byTag[TAG_JPEG_OFFSET]?.uintValues()?.firstOrNull()
            val jpegLen = byTag[TAG_JPEG_LENGTH]?.uintValues()?.firstOrNull()
            if ((compression == 6L || compression == 7L) && jpegOff != null && jpegLen != null && jpegLen > 0) {
                val w = byTag[TAG_WIDTH]?.uintValues()?.firstOrNull()?.toInt() ?: 0
                val h = byTag[TAG_HEIGHT]?.uintValues()?.firstOrNull()?.toInt() ?: 0
                if (jpegOff + jpegLen <= input.size) previews += PreviewRef(jpegOff, jpegLen, w, h)
            } else if (compression == 6L || compression == 7L) {
                // 兜底：JPEG 放在 StripOffsets/StripByteCounts
                val so = byTag[TAG_STRIP_OFFSETS]?.uintValues()
                val sc = byTag[TAG_STRIP_COUNTS]?.uintValues()
                if (so != null && sc != null && so.isNotEmpty() && sc.isNotEmpty() && sc[0] > 0) {
                    val w = byTag[TAG_WIDTH]?.uintValues()?.firstOrNull()?.toInt() ?: 0
                    val h = byTag[TAG_HEIGHT]?.uintValues()?.firstOrNull()?.toInt() ?: 0
                    if (so[0] + sc[0] <= input.size) previews += PreviewRef(so[0], sc[0], w, h)
                }
            }

            if (depth == 0) {
                orientation = byTag[TAG_ORIENTATION]?.uintValues()?.firstOrNull()?.toInt() ?: 1
                make = byTag[TAG_MAKE]?.asciiValue()
                model = byTag[TAG_MODEL]?.asciiValue()
                if (dateStr == null) dateStr = byTag[TAG_DATETIME]?.asciiValue()
            }

            byTag[TAG_EXIF_IFD]?.uintValues()?.firstOrNull()?.let { exifOff ->
                if (visited.add(exifOff)) {
                    val exifEntries = readIfdEntries(exifOff)
                    exifEntries.firstOrNull { it.tag == TAG_DATETIME_ORIGINAL }?.let { dateStr = it.asciiValue() }
                }
            }

            byTag[TAG_SUBIFDS]?.uintValues()?.forEach { walkIfd(it, depth + 1) }

            val nextOffsetPos = offset + 2 + entries.size * 12
            if (nextOffsetPos + 4 <= input.size) walkIfd(r.u32(nextOffsetPos), depth)
        }

        walkIfd(r.u32(4), 0)

        if (previews.isEmpty()) throw NefParseException("未找到内嵌 JPEG 预览")

        // IFD 里没写尺寸的，读 JPEG SOF 标记探测
        val probed = previews.map { p ->
            if (p.width > 0 && p.height > 0) p else {
                val head = r.bytes(p.offset, minOf(65536L, p.length).toInt())
                val dims = jpegDimensions(head)
                if (dims != null) p.copy(width = dims.first, height = dims.second) else p
            }
        }

        return NefInfo(
            orientation = orientation,
            dateTakenMs = parseDate(dateStr),
            make = make,
            model = model,
            previews = probed.sortedByDescending { it.pixels },
        )
    }

    fun readPreviewBytes(input: SeekableInput, ref: PreviewRef): ByteArray {
        if (ref.length > Int.MAX_VALUE) throw NefParseException("预览过大")
        val b = ByteArray(ref.length.toInt())
        val n = input.readAt(ref.offset, b)
        if (n < b.size) throw NefParseException("预览读取不完整")
        return b
    }

    /** 扫描 JPEG 标记到 SOF0/1/2 取尺寸；head 需包含文件开头 */
    fun jpegDimensions(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 4 || (head[0].toInt() and 0xFF) != 0xFF || (head[1].toInt() and 0xFF) != 0xD8) return null
        var p = 2
        while (p + 9 < head.size) {
            if (head[p].toInt() and 0xFF != 0xFF) { p++; continue }
            val marker = head[p + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { p += 2; continue }
            val len = ((head[p + 2].toInt() and 0xFF) shl 8) or (head[p + 3].toInt() and 0xFF)
            if (marker in listOf(0xC0, 0xC1, 0xC2)) {
                val h = ((head[p + 5].toInt() and 0xFF) shl 8) or (head[p + 6].toInt() and 0xFF)
                val w = ((head[p + 7].toInt() and 0xFF) shl 8) or (head[p + 8].toInt() and 0xFF)
                return w to h
            }
            if (len < 2) return null
            p += 2 + len
        }
        return null
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val f = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getDefault()
            f.parse(s.trim())?.time
        } catch (_: Exception) { null }
    }

    /** EXIF orientation 5-8 需要交换宽高；返回归一化后的 (宽, 高) */
    fun normalizedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (orientation in 5..8) height to width else width to height
}
