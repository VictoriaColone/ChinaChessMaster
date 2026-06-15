package com.ximao.chinachessmaster.controller

import android.content.Context
import android.util.Log
import com.ximao.chinachessmaster.analyzer.ChessBoardAnalyzer
import com.ximao.chinachessmaster.api.LlmApiClient
import com.ximao.chinachessmaster.engine.PikafishEngine
import com.ximao.chinachessmaster.engine.PikafishMoveConverter
import com.ximao.chinachessmaster.model.ChessMove
import com.ximao.chinachessmaster.service.OverlayService
import com.ximao.chinachessmaster.service.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 核心控制器
 *
 * 分析流程：
 *   截图 → YOLOX 识别棋盘 → 优先用 Pikafish 引擎给出走法
 *                           → 引擎失败时降级到 DeepSeek 大模型
 */
class ChessMasterController(private val context: Context) {

    companion object {
        private const val TAG = "ChessMasterCtrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val analyzer = ChessBoardAnalyzer(context)
    private val llmClient = LlmApiClient(context)
    private val pikafishEngine = PikafishEngine(context)
    private var isAnalyzing = false
    private var engineInitialized = false

    init {
        // 后台异步初始化引擎（从 assets 复制文件 + UCI 握手），不阻塞 UI
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Initializing Pikafish engine...")
            engineInitialized = pikafishEngine.init()
            Log.d(TAG, "Pikafish engine ready: $engineInitialized")
        }
    }

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
                // Step 1: YOLOX 本地识别棋盘
                Log.d(TAG, "Step 1: YOLOX analyzing screenshot...")
                val boardResult = analyzer.analyzeScreenshot(screenshot)
                screenshot.recycle()

                if (!boardResult.found) {
                    finishAnalysis()
                    OverlayService.getInstance()?.showResultToast("未检测到棋盘")
                    return@launch
                }
                Log.d(TAG, "Step 1 done: found ${boardResult.pieceCount} pieces, FEN: ${boardResult.fen}")

                // Step 2: 优先使用 Pikafish 引擎
                val bestMove = if (engineInitialized && boardResult.fen.isNotEmpty()) {
                    Log.d(TAG, "Step 2: Using Pikafish engine...")
                    tryPikafishMove(boardResult.fen)
                } else {
                    Log.d(TAG, "Step 2: Engine not ready, skip to fallback")
                    null
                }

                if (bestMove != null) {
                    // 引擎成功
                    finishAnalysis()
                    Log.d(TAG, "Pikafish bestmove: ${bestMove.serialize()} - ${bestMove.description}")
                    val pikafishMove = bestMove.copy(description = "PikaFish提示：${bestMove.description}")
                    OverlayService.getInstance()?.showMoveAnimation(pikafishMove)
                    return@launch
                }

                // Step 3: 降级到 DeepSeek
                Log.d(TAG, "Step 3: Falling back to DeepSeek...")
                val llmResult = llmClient.analyzeBoard(boardResult.description)
                finishAnalysis()

                when {
                    llmResult.isGameOver -> {
                        OverlayService.getInstance()?.showResultToast("棋局已结束")
                    }
                    llmResult.bestMove != null -> {
                        val move = llmResult.bestMove
                        Log.d(TAG, "DeepSeek bestmove: ${move.serialize()} - ${move.description}")
                        val deepSeekMove = move.copy(description = "DeepSeek提示：${move.description}")
                        OverlayService.getInstance()?.showMoveAnimation(deepSeekMove)
                    }
                    else -> {
                        Log.w(TAG, "No valid move: ${llmResult.rawResponse}")
                        OverlayService.getInstance()?.showResultToast("分析失败，请重试")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                finishAnalysis()
                OverlayService.getInstance()?.showResultToast("请求失败: ${e.message?.take(30)}")
            }
        }
    }

    /**
     * 调用 Pikafish 引擎获取最佳走法
     * 直接传 FEN 字符串给 converter，从 FEN 解析棋子颜色（大写=红方），100% 准确
     * @return 转换后的 ChessMove，失败返回 null
     */
    private suspend fun tryPikafishMove(fen: String): ChessMove? {
        val iccsMove = pikafishEngine.getBestMove(fen, moveTimeMs = 3000) ?: return null
        return PikafishMoveConverter.convert(iccsMove, fen)
    }

    private fun finishAnalysis() {
        isAnalyzing = false
        OverlayService.getInstance()?.showLoading(false)
    }

    fun destroy() {
        analyzer.close()
        llmClient.shutdown()
        pikafishEngine.close()
        scope.cancel()
    }
}
