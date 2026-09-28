package com.nefviewer.android.nef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直接吃 Mac 上 ~/Pictures/NEF Viewer 里的真实 Z8 NEF（870 张）。
 * 找不到样本目录时跳过（CI 等环境），不报错。
 */
class NefParserTest {

    private fun sampleFiles(): List<File> {
        val root = File(System.getProperty("user.home"), "Pictures/NEF Viewer")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension.equals("nef", true) }.toList()
    }

    @Test
    fun `解析全部真实 NEF`() {
        val files = sampleFiles()
        org.junit.Assume.assumeTrue("本机没有 NEF 样本，跳过", files.isNotEmpty())
        println("样本数: ${files.size}")

        var withDate = 0
        var vertical = 0
        val previewCountDist = mutableMapOf<Int, Int>()
        val largestDims = mutableSetOf<Pair<Int, Int>>()
        val failures = mutableListOf<String>()

        files.forEachIndexed { i, f ->
            try {
                val input = FileSeekableInput(f.absolutePath)
                val info = NefParser.parse(input)
                assertTrue("${f.name}: 至少一个预览", info.previews.isNotEmpty())
                assertTrue("${f.name}: orientation 合法 (${info.orientation})", info.orientation in 1..8)
                assertTrue(
                    "${f.name}: 最大预览尺寸异常 ${info.largestPreview.width}x${info.largestPreview.height}",
                    info.largestPreview.width >= 1000 || info.largestPreview.height >= 1000
                )
                if (info.dateTakenMs != null) withDate++
                if (info.orientation in 5..8) vertical++
                previewCountDist.merge(info.previews.size, 1, Int::plus)
                largestDims += info.largestPreview.width to info.largestPreview.height

                // 每 50 张抽 1 张校验最大预览确实是 JPEG（SOI = FF D8）
                if (i % 50 == 0) {
                    val bytes = NefParser.readPreviewBytes(input, info.largestPreview)
                    assertEquals("${f.name}: JPEG SOI", 0xFF, bytes[0].toInt() and 0xFF)
                    assertEquals("${f.name}: JPEG SOI", 0xD8, bytes[1].toInt() and 0xFF)
                    // EOI = FF D9
                    assertEquals("${f.name}: JPEG EOI", 0xFF, bytes[bytes.size - 2].toInt() and 0xFF)
                    assertEquals("${f.name}: JPEG EOI", 0xD9, bytes[bytes.size - 1].toInt() and 0xFF)
                }
                // 每张都校验最小预览（很小，便宜）
                val small = NefParser.readPreviewBytes(input, info.smallestPreview)
                assertEquals("${f.name}: 小预览 SOI", 0xFF, small[0].toInt() and 0xFF)
                assertEquals("${f.name}: 小预览 SOI", 0xD8, small[1].toInt() and 0xFF)
                input.close()
            } catch (e: Throwable) {
                failures += "${f.name}: ${e.message}"
            }
        }

        println("含拍摄时间: $withDate / ${files.size}")
        println("竖拍(orientation 5-8): $vertical")
        println("每文件预览数分布: $previewCountDist")
        println("最大预览尺寸集合: $largestDims")
        assertTrue("失败 ${failures.size} 个:\n" + failures.take(10).joinToString("\n"), failures.isEmpty())
        assertTrue("拍摄时间缺失过多: $withDate/${files.size}", withDate >= files.size * 0.95)
    }

    @Test
    fun `竖拍照片宽高归一化`() {
        val files = sampleFiles()
        org.junit.Assume.assumeTrue("本机没有 NEF 样本，跳过", files.isNotEmpty())
        val input = FileSeekableInput(files.first().absolutePath)
        assertEquals(8256 to 5504, NefParser.normalizedSize(8256, 5504, 1))
        assertEquals(5504 to 8256, NefParser.normalizedSize(8256, 5504, 6))
        assertEquals(5504 to 8256, NefParser.normalizedSize(8256, 5504, 8))
        input.close()
    }

    @Test
    fun `JPEG SOF 尺寸探测`() {
        val files = sampleFiles()
        org.junit.Assume.assumeTrue("本机没有 NEF 样本，跳过", files.isNotEmpty())
        val input = FileSeekableInput(files.first().absolutePath)
        val info = NefParser.parse(input)
        val bytes = NefParser.readPreviewBytes(input, info.largestPreview)
        val dims = NefParser.jpegDimensions(bytes.copyOfRange(0, 65536))
        assertNotNull(dims)
        assertEquals(info.largestPreview.width, dims!!.first)
        assertEquals(info.largestPreview.height, dims.second)
        input.close()
    }
}
