package com.ximao.chinachessmaster.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：用于自动模式下模拟屏幕点击来落子
 */
class AutoPlayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoPlayA11y"
        private var instance: AutoPlayAccessibilityService? = null

        fun getInstance(): AutoPlayAccessibilityService? = instance

        fun isServiceEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /**
     * 在屏幕指定坐标执行点击
     */
    fun performClick(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatch requires API 24+")
            return
        }

        val clickPath = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Click at ($x, $y) completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Click at ($x, $y) cancelled")
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture at ($x, $y)")
        }
    }

    /**
     * 执行滑动手势（从一个位置拖到另一个位置）
     */
    fun performSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatch requires API 24+")
            return
        }

        val swipePath = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe ($fromX,$fromY)->($toX,$toY) completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe ($fromX,$fromY)->($toX,$toY) cancelled")
            }
        }, null)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
