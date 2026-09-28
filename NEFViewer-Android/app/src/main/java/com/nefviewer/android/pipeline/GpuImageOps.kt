package com.nefviewer.android.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlin.math.roundToInt

/**
 * GPU 图像操作（Adreno / 标准 HWUI 路径，无厂商 SDK）：
 * 通过 hardware Canvas 把方向旋转 + 缩放一次 draw 完成，输出 HARDWARE bitmap
 * （GPU 显存，Compose 显示零上传）。JPEG 解码本身仍走 CPU（libjpeg-turbo NEON），
 * 这是 SDK 公开路径下能拿到的全部 GPU 加速。
 */
object GpuImageOps {

    private const val TAG = "GpuImageOps"

    @Volatile
    private var logged = false

    fun orientedDims(w: Int, h: Int, orientation: Int): Pair<Int, Int> =
        if (orientation in 5..8) h to w else w to h

    fun fitSize(w: Int, h: Int, targetMax: Int): Pair<Int, Int> {
        if (maxOf(w, h) <= targetMax) return w to h
        val r = targetMax.toFloat() / maxOf(w, h)
        return (w * r).roundToInt() to (h * r).roundToInt()
    }

    /** EXIF 方向矩阵：源像素坐标 → 旋转归一化后坐标（左上角原点） */
    fun orientationMatrix(orientation: Int, w: Int, h: Int): Matrix = Matrix().apply {
        when (orientation) {
            2 -> { setScale(-1f, 1f); postTranslate(w.toFloat(), 0f) }
            3 -> { setRotate(180f); postTranslate(w.toFloat(), h.toFloat()) }
            4 -> { setScale(1f, -1f); postTranslate(0f, h.toFloat()) }
            5 -> { setScale(-1f, 1f); postRotate(90f); postTranslate(h.toFloat(), w.toFloat()) }
            6 -> { setRotate(90f); postTranslate(h.toFloat(), 0f) }
            7 -> { setScale(-1f, 1f); postRotate(270f) }
            8 -> { setRotate(270f); postTranslate(0f, w.toFloat()) }
        }
    }

    /**
     * 把 src 按 matrix 变换进精确尺寸的输出 bitmap。
     * hardwareOut=true 时用 GPU canvas（缩放/旋转在 Adreno 上跑），输出 HARDWARE bitmap；
     * false 时用软件 canvas（磁盘落盘用，HARDWARE 不可压缩）。
     */
    fun drawTransformed(src: Bitmap, matrix: Matrix, outW: Int, outH: Int, hardwareOut: Boolean): Bitmap {
        val out = Bitmap.createBitmap(
            outW, outH,
            if (hardwareOut) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        if (hardwareOut && !logged) {
            logged = true
            Log.i(TAG, "hardware canvas accelerated = ${canvas.isHardwareAccelerated}")
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    /** 方向 + 缩放组合矩阵（先旋转归一，再缩放到目标） */
    fun combinedMatrix(orientation: Int, srcW: Int, srcH: Int, targetMax: Int): Pair<Matrix, Pair<Int, Int>> {
        val (ow, oh) = orientedDims(srcW, srcH, orientation)
        val (tw, th) = fitSize(ow, oh, targetMax)
        val m = orientationMatrix(orientation, srcW, srcH)
        val r = tw.toFloat() / ow
        m.postScale(r, r)
        return m to (tw to th)
    }
}
