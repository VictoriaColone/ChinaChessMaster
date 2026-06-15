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

        // 2. 裁剪棋盘区域（粗裁剪，基于背景色）
        val boardWidth = boardRegion.right - boardRegion.left
        val boardHeight = boardRegion.bottom - boardRegion.top
        val boardBitmap = Bitmap.createBitmap(
            screenshot, boardRegion.left, boardRegion.top, boardWidth, boardHeight
        )

        // 2.1 精确定位棋盘格线边界，消除背景色边缘的误差
        val preciseRegion = findPreciseBoardBoundary(boardBitmap)
        val gridOffsetX: Int
        val gridOffsetY: Int
        val gridWidth: Int
        val gridHeight: Int
        if (preciseRegion != null) {
            gridOffsetX = preciseRegion.left
            gridOffsetY = preciseRegion.top
            gridWidth = preciseRegion.right - preciseRegion.left
            gridHeight = preciseRegion.bottom - preciseRegion.top
            Log.d(TAG, "Precise grid boundary: offset=($gridOffsetX,$gridOffsetY) size=${gridWidth}x${gridHeight}")
        } else {
            gridOffsetX = 0
            gridOffsetY = 0
            gridWidth = boardWidth
            gridHeight = boardHeight
            Log.d(TAG, "Failed to find precise board boundary, using original crop")
        }

        // 3. YOLOX 检测棋子（在粗裁剪的 bitmap 上检测）
        val detections = detector.detect(boardBitmap)
        Log.d(TAG, "YOLOX detected ${detections.size} pieces")

        if (detections.isEmpty()) {
            boardBitmap.recycle()
            Log.d(TAG, "No chess pieces detected")
            return BoardAnalysisResult(found = false)
        }

        // 4. 将检测结果映射到棋盘坐标 + 颜色分析
        // 使用精确格线边界计算格子大小，确保坐标映射准确
        val cellWidth = gridWidth / 8.0
        val cellHeight = gridHeight / 9.0
        val pieces = mutableListOf<RecognizedPiece>()

        for (det in detections) {
            // 棋子中心点（在粗裁剪坐标系中）
            val rawCenterX = ((det.bbox.left + det.bbox.right) / 2f).toInt()
            val rawCenterY = ((det.bbox.top + det.bbox.bottom) / 2f).toInt()

            // 减去格线偏移，转换到以格线左下角为原点的坐标系
            val gridX = rawCenterX - gridOffsetX
            val gridY = rawCenterY - gridOffsetY

            // 使用四舍五入映射到最近的格点，比截断更准确
            val col = Math.round(gridX / cellWidth).toInt().coerceIn(0, 8)
            val row = 9 - Math.round(gridY / cellHeight).toInt().coerceIn(0, 9)

            // 颜色分析区分红黑方
            val clampedX = rawCenterX.coerceIn(0, boardBitmap.width - 1)
            val clampedY = rawCenterY.coerceIn(0, boardBitmap.height - 1)
            val color = detectPieceColor(boardBitmap, clampedX, clampedY)

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

        // 6. 生成 FEN（供 Pikafish 引擎使用）
        val fen = generateFen(pieces)
        Log.d(TAG, "FEN: $fen")

        return BoardAnalysisResult(
            found = true,
            description = description,
            fen = fen,
            recognizedPieces = pieces,
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
     * 将识别到的棋子列表转换为象棋 FEN 字符串
     *
     * FEN 坐标系：行 0（最顶行/黑方底线） → 行 9（最底行/红方底线）
     * 我们的坐标系：row=0 是红方底线，row=9 是黑方底线
     * 因此 FEN 行索引 = 9 - row
     *
     * FEN 棋子字母：大写=红方，小写=黑方
     *   K=帅  A=仕  B=相  N=馬  R=車  C=炮  P=兵
     *   k=将  a=士  b=象  n=馬  r=車  c=砲  p=卒
     */
    private fun generateFen(pieces: List<RecognizedPiece>): String {
        // 建立 10行×9列 的棋盘格，null 表示空格
        val board = Array(10) { arrayOfNulls<String>(9) }
        for (piece in pieces) {
            val fenRow = 9 - piece.row  // 转换到 FEN 行索引
            val col = piece.col
            if (fenRow in 0..9 && col in 0..8) {
                board[fenRow][col] = pieceToFenChar(piece.name, piece.color)
            }
        }

        // 将棋盘序列化为 FEN rank 字符串
        val ranks = board.map { row ->
            val sb = StringBuilder()
            var emptyCount = 0
            for (cell in row) {
                if (cell == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(cell)
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            sb.toString()
        }

        // 根据将帅位置判断谁先走：
        // 帅（K）在屏幕下方（内部 row=0，即 FEN 第9行）→ 红方先手（w）
        // 将（k）在屏幕下方（内部 row=0，即 FEN 第9行）→ 黑方先手（b）
        val redKingAtBottom = pieces.any { it.name == "帅" && it.row <= 2 }
        val sideToMove = if (redKingAtBottom) "w" else "b"
        return ranks.joinToString("/") + " $sideToMove - - 0 1"
    }

    /**
     * 将棋子名称和颜色转换为 FEN 字符（大写=红方，小写=黑方）
     */
    private fun pieceToFenChar(name: String, color: String): String {
        val isRed = color == "红"
        val char = when (name) {
            "帅", "将" -> "K"
            "仕", "士" -> "A"
            "相", "象" -> "B"
            "車" -> "R"
            "馬" -> "N"
            "炮", "砲" -> "C"
            "兵", "卒" -> "P"
            else -> "?"
        }
        return if (isRed) char else char.lowercase()
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

    /**
     * 在粗裁剪的棋盘 bitmap 中精确定位格线边界
     * 通过检测深色格线来找到棋盘的实际起始/结束位置
     */
    private fun findPreciseBoardBoundary(bitmap: Bitmap): BoardRegion? {
        val width = bitmap.width
        val height = bitmap.height
        val scanStep = 2

        // 从上往下扫描，找到第一条水平格线
        var topBound = -1
        for (y in 0 until height / 3) {
            var linePixelCount = 0
            val scanWidth = width * 3 / 4 - width / 4
            for (x in width / 4 until width * 3 / 4 step scanStep) {
                if (isGridLineColor(bitmap.getPixel(x, y))) {
                    linePixelCount++
                }
            }
            // 水平格线应横跨棋盘中部大部分区域
            if (linePixelCount > scanWidth / scanStep / 4) {
                topBound = y
                break
            }
        }
        if (topBound == -1) return null

        // 从下往上扫描，找到最后一条水平格线
        var bottomBound = -1
        for (y in height - 1 downTo height * 2 / 3) {
            var linePixelCount = 0
            val scanWidth = width * 3 / 4 - width / 4
            for (x in width / 4 until width * 3 / 4 step scanStep) {
                if (isGridLineColor(bitmap.getPixel(x, y))) {
                    linePixelCount++
                }
            }
            if (linePixelCount > scanWidth / scanStep / 4) {
                bottomBound = y
                break
            }
        }
        if (bottomBound == -1) return null

        // 逐行扫描找每行中第一个和最后一个深色像素的 x 坐标，取中位数作为左右边界
        val verticalScanTop = topBound + (bottomBound - topBound) / 4
        val verticalScanBottom = bottomBound - (bottomBound - topBound) / 4

        val leftCandidates = mutableListOf<Int>()
        val rightCandidates = mutableListOf<Int>()

        for (y in verticalScanTop until verticalScanBottom step scanStep) {
            // 从左往右找第一个深色像素
            for (x in 0 until width / 3) {
                if (isGridLineColor(bitmap.getPixel(x, y))) {
                    leftCandidates.add(x)
                    break
                }
            }
            // 从右往左找最后一个深色像素
            for (x in width - 1 downTo width * 2 / 3) {
                if (isGridLineColor(bitmap.getPixel(x, y))) {
                    rightCandidates.add(x)
                    break
                }
            }
        }

        if (leftCandidates.isEmpty() || rightCandidates.isEmpty()) return null

        // 取中位数，过滤掉异常值
        leftCandidates.sort()
        rightCandidates.sort()
        val leftBound = leftCandidates[leftCandidates.size / 2]
        val rightBound = rightCandidates[rightCandidates.size / 2]

        // 合理性检查：精确边界应在粗裁剪范围内且面积合理
        if (rightBound - leftBound < width / 2 || bottomBound - topBound < height / 2) {
            return null
        }

        return BoardRegion(leftBound, topBound, rightBound, bottomBound)
    }

    /**
     * 判断像素是否为棋盘格线颜色（深色线条：黑色或深棕色）
     */
    private fun isGridLineColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r < 120 && g < 120 && b < 120
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
    val fen: String = "",
    val recognizedPieces: List<RecognizedPiece> = emptyList(),
    val pieceCount: Int = 0
)

data class BoardRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
