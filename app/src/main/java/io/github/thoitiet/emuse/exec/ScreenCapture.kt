package io.github.thoitiet.emuse.exec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * MediaProjection screenshot pipeline (no root needed).
 *
 * MainActivity feeds the granted MediaProjection via [setProjection] after
 * the user accepts the system screen-capture dialog. [capturePng] creates a
 * one-shot virtual display, grabs the latest image and returns PNG bytes.
 * All blocking calls run on the caller's thread (dispatcher IO thread).
 */
object ScreenCapture {
    private const val TAG = "E-Muse"

    /** Screenshots are downscaled to this max dimension before compress. */
    private const val MAX_DIM = 1080

    @Volatile
    private var projection: MediaProjection? = null

    fun setProjection(p: MediaProjection) {
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = p
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === p) projection = null
                }
            },
            Handler(Looper.getMainLooper()),
        )
        Log.i(TAG, "MediaProjection granted")
    }

    fun hasProjection(): Boolean = projection != null

    /**
     * Stop the held MediaProjection and drop all related state. Call when the
     * service is destroyed so the system token is not held after ACTION_STOP.
     */
    fun release() {
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    fun capturePng(width: Int, height: Int, densityDpi: Int): ByteArray? {
        val proj = projection ?: return null
        if (width <= 0 || height <= 0) return null
        var reader: ImageReader? = null
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            display = proj.createVirtualDisplay(
                "emuse-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            // Give the compositor a moment to produce the first frame.
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        return imageToPng(image, width, height)
                    } finally {
                        image.close()
                    }
                }
                Thread.sleep(80)
            }
            Log.w(TAG, "MediaProjection produced no frame")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection capture failed: ${e.message}")
            return null
        } finally {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun imageToPng(
        image: android.media.Image,
        width: Int,
        height: Int,
    ): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()
        // Full-resolution frames are wasteful over the tunnel: downscale to
        // 1080p max (aspect preserved) before compressing.
        val scaled = downscaleIfNeeded(cropped)
        if (scaled !== cropped) cropped.recycle()
        val out = ByteArrayOutputStream()
        try {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            scaled.recycle()
        }
        return out.toByteArray()
    }

    /**
     * Downscales [src] so its longest side is at most [maxDim] px, keeping
     * the aspect ratio. Returns [src] itself when already small enough.
     */
    private fun downscaleIfNeeded(src: Bitmap, maxDim: Int = MAX_DIM): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val dw = maxOf(1, (src.width * scale).roundToInt())
        val dh = maxOf(1, (src.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(src, dw, dh, true)
    }

    /**
     * Same 1080p downscale as [capturePng] for paths that produce PNG bytes
     * without a Bitmap (root screencap fallback). Returns the original bytes
     * when already small enough or undecodable.
     */
    fun downscalePng(png: ByteArray, maxDim: Int = MAX_DIM): ByteArray {
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(png, 0, png.size)
        }.getOrNull() ?: return png
        try {
            val scaled = downscaleIfNeeded(bitmap, maxDim)
            if (scaled === bitmap) return png
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                return out.toByteArray()
            } finally {
                scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes just the dimensions of PNG [png] without loading pixels. */
    fun pngSize(png: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            null
        }
    }
}
