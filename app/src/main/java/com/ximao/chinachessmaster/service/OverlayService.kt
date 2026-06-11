package com.ximao.chinachessmaster.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ximao.chinachessmaster.R
import com.ximao.chinachessmaster.controller.ChessMasterController
import com.ximao.chinachessmaster.model.ChessMove

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1002
        private const val ANIMATION_DURATION_MS = 3000L

        private var instance: OverlayService? = null
        fun getInstance(): OverlayService? = instance
    }

    private lateinit var windowManager: WindowManager
    private var floatingBallView: View? = null
    private var animationOverlayView: View? = null
    private var controller: ChessMasterController? = null
    private var screenWidth = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels

        controller = ChessMasterController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatingBall()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showFloatingBall() {
        if (floatingBallView != null) return

        floatingBallView = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)

        val ballSize = (52 * resources.displayMetrics.density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0  // 初始吸附左侧
            y = 400
        }

        var lastTouchX = 0f
        var lastTouchY = 0f
        var initialX = 0
        var initialY = 0
        var isDragging = false

        val ballContainer = floatingBallView!!.findViewById<View>(R.id.ball_container)

        ballContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY
                    if (Math.abs(deltaX) > 8 || Math.abs(deltaY) > 8) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + deltaX.toInt()
                        layoutParams.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(floatingBallView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 松手自动吸附到最近的一侧
                        snapToEdge(layoutParams, ballSize)
                    } else {
                        // 点击 → 触发截图分析
                        controller?.analyzeOnce()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingBallView, layoutParams)
    }

    /**
     * 自动吸附到屏幕左侧或右侧
     */
    private fun snapToEdge(layoutParams: WindowManager.LayoutParams, ballSize: Int) {
        val centerX = layoutParams.x + ballSize / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - ballSize

        // 用动画平滑滑动到边缘
        val startX = layoutParams.x
        val duration = 200L
        val startTime = System.currentTimeMillis()

        val animator = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / duration).coerceAtMost(1f)
                // 缓动函数
                val easedProgress = 1 - (1 - progress) * (1 - progress)
                layoutParams.x = (startX + (targetX - startX) * easedProgress).toInt()
                try {
                    windowManager.updateViewLayout(floatingBallView, layoutParams)
                } catch (_: Exception) {
                    return
                }
                if (progress < 1f) {
                    mainHandler.postDelayed(this, 8)
                }
            }
        }
        mainHandler.post(animator)
    }

    /**
     * 显示/隐藏加载状态
     */
    fun showLoading(loading: Boolean) {
        mainHandler.post {
            val btnHint = floatingBallView?.findViewById<TextView>(R.id.btn_hint)
            val progress = floatingBallView?.findViewById<ProgressBar>(R.id.progress_loading)
            if (loading) {
                btnHint?.visibility = View.GONE
                progress?.visibility = View.VISIBLE
            } else {
                btnHint?.visibility = View.VISIBLE
                progress?.visibility = View.GONE
            }
        }
    }

    /**
     * 显示短暂的Toast提示
     */
    fun showResultToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 在屏幕底部显示落子提示动画，3秒后自动消失
     */
    @SuppressLint("InflateParams")
    fun showMoveAnimation(move: ChessMove) {
        mainHandler.post {
            removeAnimationOverlay()

            animationOverlayView = LayoutInflater.from(this)
                .inflate(R.layout.move_animation_overlay, null)

            val moveText = animationOverlayView!!.findViewById<TextView>(R.id.text_move_hint)
            moveText.text = move.description.ifEmpty {
                "(${move.fromCol},${move.fromRow}) → (${move.toCol},${move.toRow})"
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(animationOverlayView, layoutParams)

            // 入场动画：从底部滑入
            val hintContainer = animationOverlayView!!.findViewById<View>(R.id.hint_container)
            hintContainer.translationY = 200f
            hintContainer.alpha = 0f
            hintContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .start()

            // 3秒后自动消失（滑出动画）
            mainHandler.postDelayed({
                animationOverlayView?.let { overlay ->
                    val container = overlay.findViewById<View>(R.id.hint_container)
                    container?.animate()
                        ?.translationY(200f)
                        ?.alpha(0f)
                        ?.setDuration(300)
                        ?.setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                removeAnimationOverlay()
                            }
                        })
                        ?.start()
                }
            }, ANIMATION_DURATION_MS)
        }
    }

    fun removeAnimationOverlay() {
        animationOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            animationOverlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "ChinaChessMaster悬浮窗服务" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋大师")
            .setContentText("点击悬浮球获取下一步提示")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        floatingBallView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        removeAnimationOverlay()
        mainHandler.removeCallbacksAndMessages(null)
        floatingBallView = null
        instance = null
        super.onDestroy()
    }
}
