package com.ximao.chinachessmaster.controller

import android.content.Context
import android.util.Log
import com.ximao.chinachessmaster.analyzer.ChessBoardAnalyzer
import com.ximao.chinachessmaster.api.LlmApiClient
import com.ximao.chinachessmaster.service.OverlayService
import com.ximao.chinachessmaster.service.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 核心控制器（简易版）
 * 用户点击悬浮球触发：截图 → YOLOX ONNX识别棋盘 → 生成文本 → DeepSeek分析 → 展示结果
 */
class ChessMasterController(private val context: Context) {

    companion object {
        private const val TAG = "ChessMasterCtrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val analyzer = ChessBoardAnalyzer(context)
    private val llmClient = LlmApiClient(context)
    private var isAnalyzing = false

    fun analyzeOnce() {
        if (isAnalyzing) {
            Log.d(TAG, "Already analyzing, skip")
            return
        }

        val captureService = ScreenCaptureService.getInstance()
        if (captureService == null) {
            OverlayService.getInstance()?.showResultToast("截屏服务未启动")
            return
        }

        val screenshot = captureService.captureScreen()
        if (screenshot == null) {
            OverlayService.getInstance()?.showResultToast("截屏失败，请重试")
            return
        }

        isAnalyzing = true
        OverlayService.getInstance()?.showLoading(true)

        scope.launch {
            try {
                // 1. YOLOX ONNX 本地识别棋盘
                Log.d(TAG, "Step 1: YOLOX analyzing screenshot...")
                val boardResult = analyzer.analyzeScreenshot(screenshot)
                screenshot.recycle()

                if (!boardResult.found) {
                    isAnalyzing = false
                    OverlayService.getInstance()?.showLoading(false)
                    OverlayService.getInstance()?.showResultToast("未检测到棋盘")
                    return@launch
                }

                Log.d(TAG, "Step 1 done: found ${boardResult.pieceCount} pieces")

                // 2. 发送文本描述给 DeepSeek 分析
                Log.d(TAG, "Step 2: Sending to DeepSeek...")
                val result = llmClient.analyzeBoard(boardResult.description)

                isAnalyzing = false
                OverlayService.getInstance()?.showLoading(false)

                when {
                    result.isGameOver -> {
                        OverlayService.getInstance()?.showResultToast("棋局已结束")
                    }
                    result.bestMove != null -> {
                        val move = result.bestMove
                        Log.d(TAG, "Best move: ${move.serialize()} - ${move.description}")
                        OverlayService.getInstance()?.showMoveAnimation(move)
                    }
                    else -> {
                        Log.w(TAG, "No valid move: ${result.rawResponse}")
                        OverlayService.getInstance()?.showResultToast("分析失败，请重试")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                isAnalyzing = false
                OverlayService.getInstance()?.showLoading(false)
                OverlayService.getInstance()?.showResultToast("请求失败: ${e.message?.take(30)}")
            }
        }
    }

    fun destroy() {
        analyzer.close()
        llmClient.shutdown()
        scope.cancel()
    }
}
