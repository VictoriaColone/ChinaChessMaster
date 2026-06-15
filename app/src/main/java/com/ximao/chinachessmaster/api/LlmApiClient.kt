package com.ximao.chinachessmaster.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ximao.chinachessmaster.config.ModelConfig
import com.ximao.chinachessmaster.model.AnalysisResult
import com.ximao.chinachessmaster.model.ChessMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 大模型 API 客户端（纯文本模式）
 * 接收本地 OCR 识别后的棋盘文本描述，发给 DeepSeek 进行分析
 */
class LlmApiClient(private val context: Context) {

    companion object {
        private const val TAG = "LlmApiClient"
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """你是中国象棋大师。用户会给你当前棋盘上所有棋子的位置信息。

坐标系：列0-8从左到右，行0为红方底线，行9为黑方底线。红方在下（行0-4），黑方在上（行5-9）。

请根据棋盘局面分析并给出最佳落子方案。

严格按以下JSON格式回复，不要用markdown包裹，不要添加任何多余文字：
- 棋局已结束: {"over":true}
- 需要落子: {"over":false,"move":"起始列,起始行->目标列,目标行","piece":"颜色+棋子名，如：红車、黑炮","notation":"标准象棋术语，如：红車二进三、黑炮８平５"}

字段说明：
- move：使用棋盘坐标数字，格式固定为 "列,行->列,行"
- piece：明确指出是哪一方的哪个棋子，格式为"红/黑+棋子名"，例如"红車"、"黑炮"、"红兵"
- notation：严格遵循中国象棋标准记谱法，格式为"{颜色}{棋子}{起始列}{动作}{步数或目标列}"
  * 列号：红方用一二三四五六七八九（从右到左），黑方用１２３４５６７８９（从左到右）
  * 动作：进（向对方方向）、退（向己方方向）、平（横向移动）
  * 步数/目标列：进退用步数，平移用目标列号
  * 示例：红車二进三、黑炮８平５、红马三进四、黑将５平４"""

    /**
     * 发送棋盘描述文本给大模型，获取最佳落子方案
     */
    suspend fun analyzeBoard(boardDescription: String): AnalysisResult = withContext(Dispatchers.IO) {
        val modelDetail = ModelConfig.getActiveModel(context)
        val apiKey = ModelConfig.getApiKey(context)

        val requestBody = JsonObject().apply {
            addProperty("model", modelDetail.modelId)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to boardDescription)
            )))
            addProperty("max_tokens", modelDetail.maxTokens)
            addProperty("temperature", modelDetail.temperature)
        }

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "Request to ${modelDetail.baseUrl}, model: ${modelDetail.modelId}")
        Log.d(TAG, "Board description:\n$boardDescription")

        val request = Request.Builder()
            .url(modelDetail.baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = httpClient.newCall(request).await()
        Log.d(TAG, "Response: $responseText")
        parseResponse(responseText)
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        continuation.resume(body)
                    } else {
                        continuation.resumeWithException(IOException("API error ${response.code}: $body"))
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun parseResponse(responseText: String): AnalysisResult {
        return try {
            val chatResponse = gson.fromJson(responseText, ChatResponse::class.java)
            val content = chatResponse.choices.firstOrNull()?.message?.content ?: ""
            Log.d(TAG, "LLM content: $content")

            val jsonStr = extractJson(content)
            val json = gson.fromJson(jsonStr, JsonObject::class.java)

            val isGameOver = json.get("over")?.asBoolean ?: false
            if (isGameOver) {
                return AnalysisResult(bestMove = null, isGameOver = true, isUserTurn = false, rawResponse = content)
            }

            val moveStr = json.get("move")?.asString ?: ""
            val piece = json.get("piece")?.asString ?: ""
            val notation = json.get("notation")?.asString ?: ""
            val move = ChessMove.deserialize(moveStr)

            // 优先用标准记谱，兜底用 piece + 坐标
            val displayText = when {
                notation.isNotBlank() -> notation
                piece.isNotBlank() -> "$piece (${move?.fromCol},${move?.fromRow})→(${move?.toCol},${move?.toRow})"
                else -> ""
            }

            AnalysisResult(
                bestMove = move?.copy(description = displayText),
                isGameOver = false,
                isUserTurn = true,
                confidence = 0.9f,
                rawResponse = content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse response failed", e)
            AnalysisResult(bestMove = null, isGameOver = false, isUserTurn = false, rawResponse = "Parse error: ${e.message}")
        }
    }

    private fun extractJson(text: String): String {
        var cleaned = text.trim()
        if (cleaned.contains("```")) {
            val startIdx = cleaned.indexOf("{", cleaned.indexOf("```"))
            val endIdx = cleaned.lastIndexOf("}")
            if (startIdx >= 0 && endIdx > startIdx) {
                cleaned = cleaned.substring(startIdx, endIdx + 1)
            }
        }
        val firstBrace = cleaned.indexOf("{")
        val lastBrace = cleaned.lastIndexOf("}")
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return cleaned.substring(firstBrace, lastBrace + 1)
        }
        return cleaned
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
    }
}

private data class ChatResponse(val choices: List<Choice>)
private data class Choice(val message: MessageContent)
private data class MessageContent(val role: String, val content: String)
