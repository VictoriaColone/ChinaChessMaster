package com.ximao.chinachessmaster.engine

import com.ximao.chinachessmaster.model.ChessMove

/**
 * Pikafish 走法格式转换器
 *
 * Pikafish/Stockfish 象棋的 ICCS 坐标系（与 FEN 行方向一致）：
 *   列：a-i → 0-8（从左到右）
 *   行：0-9，行 0 = 红方底线（FEN 第9行），行 9 = 黑方底线（FEN 第0行）
 *
 * 我们内部坐标系：row=0 = 红方底线（屏幕下方），与 ICCS 行号完全一致，无需转换。
 *
 * 示例（FEN "...2B1KA1NR w..."，红帅在底线）：
 *   "a3a4" → 红兵从 col=0,row=3 前进一步到 col=0,row=4（兵向黑方推进 row 增大）
 */
object PikafishMoveConverter {

    /**
     * 将 Pikafish 返回的 ICCS 走法字符串转换为 ChessMove
     *
     * @param iccsMove 如 "h2e2"、"a0a1"
     * @param fen 当前局面的 FEN 字符串（直接从 FEN 解析棋子颜色，100% 准确）
     * @return ChessMove，包含坐标和标准记谱文字
     */
    fun convert(iccsMove: String, fen: String): ChessMove? {
        if (iccsMove.length < 4) return null

        val fromCol = iccsMove[0] - 'a'          // a-i → 0-8
        val fromIccsRow = iccsMove[1].digitToIntOrNull() ?: return null
        val toCol = iccsMove[2] - 'a'
        val toIccsRow = iccsMove[3].digitToIntOrNull() ?: return null

        // Pikafish ICCS 行 0 = 红方底线 = 内部 row 0，方向完全一致，无需转换
        val fromRow = fromIccsRow
        val toRow = toIccsRow

        if (fromCol !in 0..8 || fromRow !in 0..9 || toCol !in 0..8 || toRow !in 0..9) return null

        // 直接从 FEN 解析起手位置的棋子（大写=红方，小写=黑方），避免颜色识别误差
        val fenPieces = parseFenPieces(fen)
        val movingPiece = fenPieces.find { it.col == fromCol && it.row == fromRow }
        val notation = generateNotation(movingPiece, fromCol, fromRow, toCol, toRow)

        return ChessMove(
            fromCol = fromCol,
            fromRow = fromRow,
            toCol = toCol,
            toRow = toRow,
            description = notation
        )
    }

    /**
     * 解析 FEN 字符串，返回棋盘上所有棋子列表
     * FEN 行 0 = 黑方底线（内部 row=9），FEN 行 9 = 红方底线（内部 row=0）
     * 大写字母 = 红方，小写字母 = 黑方
     */
    private fun parseFenPieces(fen: String): List<FenPiece> {
        val boardFen = fen.substringBefore(' ')   // 取棋盘部分，去掉 "w - - 0 1"
        val ranks = boardFen.split('/')
        val pieces = mutableListOf<FenPiece>()

        for ((fenRowIndex, rank) in ranks.withIndex()) {
            val internalRow = 9 - fenRowIndex     // FEN 行 0 → 内部 row 9（黑方底线）
            var col = 0
            for (ch in rank) {
                if (ch.isDigit()) {
                    col += ch.digitToInt()
                } else {
                    val isRed = ch.isUpperCase()
                    val name = fenCharToPieceName(ch, isRed)
                    pieces.add(FenPiece(name = name, isRed = isRed, col = col, row = internalRow))
                    col++
                }
            }
        }
        return pieces
    }

    /**
     * FEN 字符 → 棋子中文名（大写/小写均可）
     */
    private fun fenCharToPieceName(ch: Char, isRed: Boolean): String {
        return when (ch.uppercaseChar()) {
            'K' -> if (isRed) "帅" else "将"
            'A' -> if (isRed) "仕" else "士"
            'B' -> if (isRed) "相" else "象"
            'R' -> "車"
            'N' -> "馬"
            'C' -> if (isRed) "炮" else "砲"
            'P' -> if (isRed) "兵" else "卒"
            else -> ch.toString()
        }
    }

    /**
     * 生成标准中国象棋记谱，格式：{颜色}{棋子}{起始列}{动作}{步数/目标列}
     *
     * 红方列号：一二三四五六七八九（从右到左，即 col=8→一，col=0→九）
     * 黑方列号：１２３４５６７８９（从左到右，即 col=0→１，col=8→９）
     * 动作：进（row 向对方方向减小/增大）、退（向己方方向）、平（row 不变）
     */
    private fun generateNotation(
        piece: FenPiece?,
        fromCol: Int,
        fromRow: Int,
        toCol: Int,
        toRow: Int
    ): String {
        if (piece == null) return "($fromCol,$fromRow)→($toCol,$toRow)"

        val colorLabel = if (piece.isRed) "红" else "黑"
        val pieceName = piece.name

        // 列号转换
        val fromColLabel = if (piece.isRed) {
            redColumnLabel(fromCol)   // 红方：从右到左，一二三...
        } else {
            blackColumnLabel(fromCol) // 黑方：从左到右，１２３...
        }

        // 计算动作和步数
        val (action, steps) = when {
            fromRow == toRow -> {
                // 平移
                val targetColLabel = if (piece.isRed) redColumnLabel(toCol) else blackColumnLabel(toCol)
                Pair("平", targetColLabel)
            }
            piece.isRed -> {
                // 红方在底线（row=0），向上进攻 row 增大 = 进，row 减小 = 退
                val stepCount = Math.abs(toRow - fromRow)
                if (toRow > fromRow) Pair("进", stepCount.toString())
                else Pair("退", stepCount.toString())
            }
            else -> {
                // 黑方在顶线（row=9），向下进攻 row 减小 = 进，row 增大 = 退
                val stepCount = Math.abs(toRow - fromRow)
                if (toRow < fromRow) Pair("进", stepCount.toString())
                else Pair("退", stepCount.toString())
            }
        }

        return "$colorLabel$pieceName$fromColLabel$action$steps"
    }

    private fun redColumnLabel(col: Int): String {
        // 红方视角：从右到左，col=8→一，col=0→九
        return listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")[col]
    }

    private fun blackColumnLabel(col: Int): String {
        // 黑方视角：从左到右，col=0→１，col=8→９
        return listOf("１", "２", "３", "４", "５", "６", "７", "８", "９")[col]
    }
}

/**
 * 棋盘棋子信息（供记谱生成使用）
 */
data class FenPiece(
    val name: String,
    val isRed: Boolean,
    val col: Int,
    val row: Int
)
