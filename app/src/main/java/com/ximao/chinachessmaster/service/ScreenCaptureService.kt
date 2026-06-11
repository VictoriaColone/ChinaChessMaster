package com.ximao.chinachessmaster.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var instance: ScreenCaptureService? = null

        fun getInstance(): ScreenCaptureService? = instance
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            // Android 14+ 要求先注册 callback 再创建 VirtualDisplay
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            setupImageReader()
        }

        return START_NOT_STICKY
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("ScreenCapture", "MediaProjection stopped")
            virtualDisplay?.release()
            imageReader?.close()
            virtualDisplay = null
            imageReader = null
        }
    }

    private fun setupImageReader() {
        // 使用独立的后台线程处理截图，确保 App 在后台时也能正常工作
        imageThread = HandlerThread("ScreenCaptureThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
        )

        // 设置空的 listener 让 ImageReader 持续接收帧
        // 实际截图在 captureScreen() 中按需获取
        imageReader!!.setOnImageAvailableListener({ reader ->
            // 仅丢弃旧帧，保持 buffer 不满，让新帧持续写入
            val image = reader.acquireLatestImage()
            image?.close()
        }, imageHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessMasterCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d("ScreenCapture", "VirtualDisplay created: ${screenWidth}x${screenHeight}")
    }

    /**
     * 按需获取当前屏幕截图（可以是其他 App 的界面）。
     * 通过暂停 listener 来确保 acquireLatestImage 不会和 listener 竞争。
     * 返回的 Bitmap 由调用方负责 recycle。
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null

        // 暂停自动丢帧，让我们独占获取
        reader.setOnImageAvailableListener(null, null)

        // 等待一小段时间让新帧写入
        try {
            Thread.sleep(100)
        } catch (_: InterruptedException) {}

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e("ScreenCapture", "acquireLatestImage failed", e)
            null
        }

        // 恢复自动丢帧 listener
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            img?.close()
        }, imageHandler)

        if (image == null) {
            Log.w("ScreenCapture", "No image available")
            return null
        }

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val rawBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Failed to create bitmap from image", e)
            null
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ChinaChessMaster屏幕捕获服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋大师")
            .setContentText("正在运行中...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        instance = null
        super.onDestroy()
    }
}
