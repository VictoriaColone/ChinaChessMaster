package com.ximao.chinachessmaster.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOX 棋子检测器：使用 ONNX Runtime 在设备端推理
 * 模型来源：nrl-ai/chessai (chessai-det-light.onnx)
 * 输入：[1, 3, 640, 640]  输出：[1, 8400, 12]
 * 7 类别：r(車), n(馬), b(象), a(士), k(将帅), c(炮), p(兵卒)
 */
class YoloxChessDetector(context: Context) {

    companion object {
        private const val TAG = "YoloxDetector"
        private const val MODEL_FILE = "chessai-det-light.onnx"
        private const val INPUT_SIZE = 640
        private const val PADDING_VALUE = 114f
        private const val CONF_THRESHOLD = 0.3f
        private const val NMS_THRESHOLD = 0.5f
        private val STRIDES = intArrayOf(8, 16, 32)
        val CLASS_NAMES = arrayOf("r", "n", "b", "a", "k", "c", "p")
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        val sessionOptions = OrtSession.SessionOptions()
        ortSession = ortEnv.createSession(modelBytes, sessionOptions)
        Log.d(TAG, "ONNX model loaded: $MODEL_FILE")
    }

    /**
     * 检测图片中的棋子
     * @return 检测结果列表（已经过 NMS 去重）
     */
    fun detect(bitmap: Bitmap): List<DetectedPiece> {
        val preprocessed = preprocess(bitmap)
        val inputTensor = preprocessed.tensor
        val ratio = preprocessed.ratio

        val inputName = ortSession.inputNames.first()
        val results = ortSession.run(mapOf(inputName to inputTensor))

        val outputTensor = results[0].value as Array<Array<FloatArray>>
        val predictions = outputTensor[0] // [8400, 12]

        inputTensor.close()
        results.close()

        return postprocess(predictions, ratio)
    }

    /**
     * 预处理：缩放 + 灰色填充到 640x640
     */
    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val ratio = min(
            INPUT_SIZE.toFloat() / originalHeight,
            INPUT_SIZE.toFloat() / originalWidth
        )

        val resizedWidth = (originalWidth * ratio).toInt()
        val resizedHeight = (originalHeight * ratio).toInt()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)

        val floatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)

        // 填充顺序：CHW（Channel, Height, Width）
        for (channel in 0 until 3) {
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    if (y < resizedHeight && x < resizedWidth) {
                        val pixel = resizedBitmap.getPixel(x, y)
                        val value = when (channel) {
                            0 -> ((pixel shr 16) and 0xFF).toFloat() // R
                            1 -> ((pixel shr 8) and 0xFF).toFloat()  // G
                            2 -> (pixel and 0xFF).toFloat()           // B
                            else -> 0f
                        }
                        floatBuffer.put(value)
                    } else {
                        floatBuffer.put(PADDING_VALUE)
                    }
                }
            }
        }

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        floatBuffer.rewind()
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        return PreprocessResult(tensor, ratio)
    }

    /**
     * YOLOX 后处理：grid 解码 + NMS
     */
    private fun postprocess(predictions: Array<FloatArray>, ratio: Float): List<DetectedPiece> {
        // 1. 生成 grids 和 expanded_strides
        val grids = mutableListOf<FloatArray>()    // [x, y] pairs
        val expandedStrides = mutableListOf<Float>()

        for (stride in STRIDES) {
            val hSize = INPUT_SIZE / stride
            val wSize = INPUT_SIZE / stride
            for (y in 0 until hSize) {
                for (x in 0 until wSize) {
                    grids.add(floatArrayOf(x.toFloat(), y.toFloat()))
                    expandedStrides.add(stride.toFloat())
                }
            }
        }

        // 2. 解码 predictions
        val numDetections = predictions.size
        val numClasses = CLASS_NAMES.size // 7

        val decodedBoxes = Array(numDetections) { FloatArray(4) }    // xyxy
        val classScores = Array(numDetections) { FloatArray(numClasses) }

        for (i in 0 until numDetections) {
            val pred = predictions[i]
            val grid = grids[i]
            val stride = expandedStrides[i]

            // 解码中心坐标和宽高
            val centerX = (pred[0] + grid[0]) * stride
            val centerY = (pred[1] + grid[1]) * stride
            val width = exp(pred[2].toDouble()).toFloat() * stride
            val height = exp(pred[3].toDouble()).toFloat() * stride

            // cxcywh → xyxy，并还原到原图尺度
            decodedBoxes[i][0] = (centerX - width / 2f) / ratio
            decodedBoxes[i][1] = (centerY - height / 2f) / ratio
            decodedBoxes[i][2] = (centerX + width / 2f) / ratio
            decodedBoxes[i][3] = (centerY + height / 2f) / ratio

            // 类别分数 = objectness * class_prob
            val objectness = pred[4]
            for (c in 0 until numClasses) {
                classScores[i][c] = objectness * pred[5 + c]
            }
        }

        // 3. Multiclass NMS
        return multiclassNms(decodedBoxes, classScores)
    }

    /**
     * 多类别 NMS
     */
    private fun multiclassNms(
        boxes: Array<FloatArray>,
        scores: Array<FloatArray>
    ): List<DetectedPiece> {
        val results = mutableListOf<DetectedPiece>()
        val numClasses = CLASS_NAMES.size

        for (classIndex in 0 until numClasses) {
            // 筛选高于阈值的检测
            val validIndices = mutableListOf<Int>()
            val validScores = mutableListOf<Float>()
            val validBoxes = mutableListOf<FloatArray>()

            for (i in boxes.indices) {
                if (scores[i][classIndex] > CONF_THRESHOLD) {
                    validIndices.add(i)
                    validScores.add(scores[i][classIndex])
                    validBoxes.add(boxes[i])
                }
            }

            if (validBoxes.isEmpty()) continue

            // NMS
            val keepIndices = nms(validBoxes, validScores)

            for (keepIdx in keepIndices) {
                val box = validBoxes[keepIdx]
                results.add(
                    DetectedPiece(
                        classIndex = classIndex,
                        className = CLASS_NAMES[classIndex],
                        confidence = validScores[keepIdx],
                        bbox = RectF(box[0], box[1], box[2], box[3])
                    )
                )
            }
        }

        return results
    }

    /**
     * 单类别 NMS
     */
    private fun nms(boxes: List<FloatArray>, scores: List<Float>): List<Int> {
        val sortedIndices = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val keep = mutableListOf<Int>()

        while (sortedIndices.isNotEmpty()) {
            val current = sortedIndices.removeAt(0)
            keep.add(current)

            val iterator = sortedIndices.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val iou = computeIoU(boxes[current], boxes[candidate])
                if (iou > NMS_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return keep
    }

    /**
     * 计算两个框的 IoU
     */
    private fun computeIoU(boxA: FloatArray, boxB: FloatArray): Float {
        val interLeft = max(boxA[0], boxB[0])
        val interTop = max(boxA[1], boxB[1])
        val interRight = min(boxA[2], boxB[2])
        val interBottom = min(boxA[3], boxB[3])

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val areaA = (boxA[2] - boxA[0]) * (boxA[3] - boxA[1])
        val areaB = (boxB[2] - boxB[0]) * (boxB[3] - boxB[1])

        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun close() {
        ortSession.close()
        ortEnv.close()
    }
}

/**
 * 预处理结果
 */
data class PreprocessResult(
    val tensor: OnnxTensor,
    val ratio: Float
)

/**
 * 检测到的棋子
 */
data class DetectedPiece(
    val classIndex: Int,
    val className: String,    // r, n, b, a, k, c, p
    val confidence: Float,
    val bbox: RectF           // 在原图坐标系中的边界框
)
