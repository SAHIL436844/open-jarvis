package com.openjarvis.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

class ScreenshotCapture(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var capturing = false

    fun startCapture(): Boolean = capturing && virtualDisplay != null

    fun initialize(projection: MediaProjection): Bitmap? {
        release()
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        handlerThread = HandlerThread("ScreenshotThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection = projection
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "OpenJarvisScreenshot", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        capturing = virtualDisplay != null
        return null
    }

    fun capture(): Bitmap? {
        if (!capturing) return null
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val paddedWidth = plane.rowStride / plane.pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            bitmap.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == image.width) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        capturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    companion object {
        @Volatile private var instance: ScreenshotCapture? = null
        fun getInstance(context: Context): ScreenshotCapture =
            instance ?: synchronized(this) {
                instance ?: ScreenshotCapture(context.applicationContext).also { instance = it }
            }
    }
}
