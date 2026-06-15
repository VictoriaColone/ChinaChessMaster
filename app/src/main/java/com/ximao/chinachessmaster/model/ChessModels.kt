package com.ximao.chinachessmaster.model

/**
 * 中国象棋棋子类型
 */
enum class PieceType(val chineseName: String, val symbol: String) {
    KING("帅/将", "K"),
    ADVISOR("仕/士", "A"),
    BISHOP("相/象", "B"),
    KNIGHT("马", "N"),
    ROOK("车", "R"),
    CANNON("炮", "C"),
    PAWN("兵/卒", "P")
}

/**
 * 棋子颜色（红方/黑方）
 */
enum class PieceColor(val label: String) {
    RED("红"),
    BLACK("黑")
}

/**
 * 棋子：类型 + 颜色 + 棋盘位置(col 0-8, row 0-9)
 */
data class ChessPiece(
    val type: PieceType,
    val color: PieceColor,
    val col: Int,
    val row: Int
)

/**
 * 落子方案：起始位置 -> 目标位置
 */
data class ChessMove(
    val fromCol: Int,
    val fromRow: Int,
    val toCol: Int,
    val toRow: Int,
    val description: String = ""
) {
    /**
     * 序列化为紧凑格式，如 "2,0->4,2"
     */
    fun serialize(): String = "$fromCol,$fromRow->$toCol,$toRow"

    companion object {
        fun deserialize(text: String): ChessMove? {
            val regex = Regex("(\\d),(\\d)->(\\d),(\\d)")
            val match = regex.find(text.trim()) ?: return null
            val (fc, fr, tc, tr) = match.destructured
            return ChessMove(fc.toInt(), fr.toInt(), tc.toInt(), tr.toInt())
        }
    }
}

/**
 * 棋盘状态
 * 10行9列，红方在下(row 0-4)，黑方在上(row 5-9)
 */
data class ChessBoard(
    val pieces: List<ChessPiece>,
    val currentTurn: PieceColor,
    val isGameOver: Boolean = false,
    val isUserTurn: Boolean = false,
    val userColor: PieceColor = PieceColor.RED
) {
    /**
     * 紧凑序列化棋盘状态，减少token消耗
     * 格式: "turn:R;user:R;pieces:R-K-4-0,R-A-3-0,..."
     */
    fun serialize(): String {
        val piecesStr = pieces.joinToString(",") { piece ->
            "${piece.color.name[0]}-${piece.type.symbol}-${piece.col}-${piece.row}"
        }
        return "turn:${currentTurn.name[0]};user:${userColor.name[0]};pieces:$piecesStr"
    }

    companion object {
        fun deserialize(text: String): ChessBoard? {
            try {
                val parts = text.split(";")
                val turnChar = parts.find { it.startsWith("turn:") }?.substringAfter("turn:") ?: return null
                val userChar = parts.find { it.startsWith("user:") }?.substringAfter("user:") ?: return null
                val piecesStr = parts.find { it.startsWith("pieces:") }?.substringAfter("pieces:") ?: return null

                val turn = if (turnChar == "R") PieceColor.RED else PieceColor.BLACK
                val userColor = if (userChar == "R") PieceColor.RED else PieceColor.BLACK
                val pieces = piecesStr.split(",").mapNotNull { token ->
                    val segments = token.split("-")
                    if (segments.size != 4) return@mapNotNull null
                    val color = if (segments[0] == "R") PieceColor.RED else PieceColor.BLACK
                    val type = PieceType.values().find { it.symbol == segments[1] } ?: return@mapNotNull null
                    val col = segments[2].toIntOrNull() ?: return@mapNotNull null
                    val row = segments[3].toIntOrNull() ?: return@mapNotNull null
                    ChessPiece(type, color, col, row)
                }
                return ChessBoard(pieces, turn, userColor = userColor)
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * 大模型分析结果
 */
data class AnalysisResult(
    val bestMove: ChessMove?,
    val isGameOver: Boolean,
    val isUserTurn: Boolean,
    val noChessBoard: Boolean = false,
    val confidence: Float = 0f,
    val rawResponse: String = ""
)

/**
 * 应用运行模式
 */
enum class AppMode {
    ASSIST,  // 辅助模式：展示动画提示，用户自行落子
    AUTO     // 自动模式：ChinaChessMaster自动落子
}
