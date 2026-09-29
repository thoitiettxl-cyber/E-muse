package io.github.thoitiet.emuse.exec

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

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
        val out = ByteArrayOutputStream()
        try {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            cropped.recycle()
        }
        return out.toByteArray()
    }
}
