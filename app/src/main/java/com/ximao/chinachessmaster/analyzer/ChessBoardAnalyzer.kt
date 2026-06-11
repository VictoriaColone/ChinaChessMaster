package com.ximao.chinachessmaster.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 棋盘分析器：YOLOX ONNX 检测棋子 + 颜色分析 + 网格定位
 * 输出专业象棋术语文本描述，供 DeepSeek 纯文本分析
 */
class ChessBoardAnalyzer(context: Context) {

    companion object {
        private const val TAG = "BoardAnalyzer"

        // 模型类别 → (红方名, 黑方名)
        private val PIECE_NAME_MAP = mapOf(
            "k" to Pair("帅", "将"),
            "a" to Pair("仕", "士"),
            "b" to Pair("相", "象"),
            "r" to Pair("車", "車"),
            "n" to Pair("馬", "馬"),
            "c" to Pair("炮", "砲"),
            "p" to Pair("兵", "卒")
        )
    }

    private val detector = YoloxChessDetector(context)

    /**
     * 分析截图，返回棋盘文本描述（供大模型分析）
     * @return 棋盘描述文本，found=false 表示未检测到棋盘
     */
    fun analyzeScreenshot(screenshot: Bitmap): BoardAnalysisResult {
        // 1. 检测棋盘区域
        val boardRegion = detectBoardRegion(screenshot)
        if (boardRegion == null) {
            Log.d(TAG, "No board region detected")
            return BoardAnalysisResult(found = false)
        }
        Log.d(TAG, "Board region: $boardRegion")

        // 2. 裁剪棋盘区域
        val boardWidth = boardRegion.right - boardRegion.left
        val boardHeight = boardRegion.bottom - boardRegion.top
        val boardBitmap = Bitmap.createBitmap(
            screenshot, boardRegion.left, boardRegion.top, boardWidth, boardHeight
        )

        // 3. YOLOX 检测棋子
        val detections = detector.detect(boardBitmap)
        Log.d(TAG, "YOLOX detected ${detections.size} pieces")

        if (detections.isEmpty()) {
            boardBitmap.recycle()
            Log.d(TAG, "No chess pieces detected")
            return BoardAnalysisResult(found = false)
        }

        // 4. 将检测结果映射到棋盘坐标 + 颜色分析
        val cellWidth = boardWidth / 8.0
        val cellHeight = boardHeight / 9.0
        val pieces = mutableListOf<RecognizedPiece>()

        for (det in detections) {
            val centerX = ((det.bbox.left + det.bbox.right) / 2f).toInt()
            val centerY = ((det.bbox.top + det.bbox.bottom) / 2f).toInt()

            val col = ((centerX / cellWidth).toInt()).coerceIn(0, 8)
            val row = 9 - ((centerY / cellHeight).toInt()).coerceIn(0, 9)

            // 颜色分析区分红黑方
            val color = detectPieceColor(boardBitmap, centerX, centerY)

            // 根据颜色获取对应的棋子名称
            val namesPair = PIECE_NAME_MAP[det.className]
            val pieceName = if (namesPair != null) {
                if (color == "红") namesPair.first else namesPair.second
            } else {
                det.className
            }

            pieces.add(RecognizedPiece(name = pieceName, color = color, col = col, row = row))
            Log.d(TAG, "Piece: ${color}${pieceName} at ($col,$row) conf=${det.confidence}")
        }

        boardBitmap.recycle()

        // 5. 生成专业象棋术语描述
        val description = generateBoardDescription(pieces)
        Log.d(TAG, "Board description:\n$description")

        return BoardAnalysisResult(
            found = true,
            description = description,
            pieceCount = pieces.size
        )
    }

    /**
     * 通过文字周围的像素颜色判断棋子是红方还是黑方
     */
    private fun detectPieceColor(bitmap: Bitmap, centerX: Int, centerY: Int): String {
        var redScore = 0
        var blackScore = 0
        val sampleRadius = 8

        for (dy in -sampleRadius..sampleRadius step 2) {
            for (dx in -sampleRadius..sampleRadius step 2) {
                val px = (centerX + dx).coerceIn(0, bitmap.width - 1)
                val py = (centerY + dy).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 红色文字特征
                if (r > 150 && g < 100 && b < 100) {
                    redScore++
                }
                // 黑色/深色文字特征
                if (r < 100 && g < 100 && b < 100) {
                    blackScore++
                }
            }
        }

        return if (redScore > blackScore) "红" else "黑"
    }

    /**
     * 生成专业象棋术语的棋盘描述
     */
    private fun generateBoardDescription(pieces: List<RecognizedPiece>): String {
        val sb = StringBuilder()
        sb.appendLine("当前棋盘局面：")

        val redPieces = pieces.filter { it.color == "红" }
        val blackPieces = pieces.filter { it.color == "黑" }

        sb.appendLine("红方棋子（${redPieces.size}个）：")
        for (piece in redPieces.sortedWith(compareBy({ pieceOrder(it.name) }, { it.col }))) {
            sb.appendLine("  ${piece.name} 在第${piece.col + 1}列第${piece.row + 1}行（坐标${piece.col},${piece.row}）")
        }

        sb.appendLine("黑方棋子（${blackPieces.size}个）：")
        for (piece in blackPieces.sortedWith(compareBy({ pieceOrder(it.name) }, { it.col }))) {
            sb.appendLine("  ${piece.name} 在第${piece.col + 1}列第${piece.row + 1}行（坐标${piece.col},${piece.row}）")
        }

        sb.appendLine()
        sb.appendLine("坐标系说明：列0-8从左到右，行0为红方底线，行9为黑方底线。")

        return sb.toString()
    }

    /**
     * 棋子排序权重
     */
    private fun pieceOrder(name: String): Int = when (name) {
        "帅", "将" -> 0
        "仕", "士" -> 1
        "相", "象" -> 2
        "車" -> 3
        "馬" -> 4
        "炮", "砲" -> 5
        "兵", "卒" -> 6
        else -> 7
    }

    /**
     * 检测棋盘在屏幕中的区域
     */
    fun detectBoardRegion(screenshot: Bitmap): BoardRegion? {
        val width = screenshot.width
        val height = screenshot.height

        var topBound = -1
        var bottomBound = -1
        var leftBound = -1
        var rightBound = -1

        for (y in 0 until height) {
            var boardPixelCount = 0
            for (x in width / 4 until width * 3 / 4 step 4) {
                if (isBoardColor(screenshot.getPixel(x, y))) {
                    boardPixelCount++
                }
            }
            if (boardPixelCount > (width / 4) / 8) {
                if (topBound == -1) topBound = y
                bottomBound = y
            }
        }

        if (topBound == -1 || bottomBound - topBound < height / 4) return null

        val midY = (topBound + bottomBound) / 2
        for (x in 0 until width) {
            if (isBoardColor(screenshot.getPixel(x, midY))) {
                if (leftBound == -1) leftBound = x
                rightBound = x
            }
        }

        if (leftBound == -1 || rightBound - leftBound < width / 3) return null
        return BoardRegion(leftBound, topBound, rightBound, bottomBound)
    }

    private fun isBoardColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r in 150..245 && g in 110..210 && b in 50..160 && r > g && g > b
    }

    fun close() {
        detector.close()
    }
}

data class RecognizedPiece(
    val name: String,
    val color: String,
    val col: Int,
    val row: Int
)

data class BoardAnalysisResult(
    val found: Boolean,
    val description: String = "",
    val pieceCount: Int = 0
)

data class BoardRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
