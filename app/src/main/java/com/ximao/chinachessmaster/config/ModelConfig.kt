package com.ximao.chinachessmaster.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class ModelConfigData(
    @SerializedName("current_model") val currentModel: String,
    @SerializedName("models") val models: Map<String, ModelDetail>
)

data class ModelDetail(
    @SerializedName("name") val name: String,
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("model_id") val modelId: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("temperature") val temperature: Double,
    @SerializedName("supports_vision") val supportsVision: Boolean = false
)

object ModelConfig {

    private var configData: ModelConfigData? = null

    fun load(context: Context): ModelConfigData {
        configData?.let { return it }
        val json = context.assets.open("model_config.json")
            .bufferedReader().use { it.readText() }
        val data = Gson().fromJson(json, ModelConfigData::class.java)
        configData = data
        return data
    }

    fun getActiveModel(context: Context): ModelDetail {
        val config = load(context)
        return config.models[config.currentModel]
            ?: throw IllegalStateException("Model '${config.currentModel}' not found in config")
    }

    fun getActiveModelId(context: Context): String {
        return load(context).currentModel
    }

    fun getApiKey(context: Context): String {
        val config = load(context)
        val model = config.models[config.currentModel]
        return model?.apiKey ?: ""
    }

    fun reload(context: Context): ModelConfigData {
        configData = null
        return load(context)
    }
}
