package com.example.speechtotext

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray

data class OnnxPrediction(
    val finalIntent: String,
    val rawLabel: String,
    val normalizedLabel: String,
    val confidence: Float?,
)

class OnnxIntentClassifier(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val labelOutputName: String
    private val probabilityOutputName: String?
    private val knownLabels: Set<String>
    private val minConfidence = 0.55f

    init {
        val modelBytes = appContext.assets.open("intent_model.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions()
        session = environment.createSession(modelBytes, options)

        inputName = session.inputNames.first()
        labelOutputName = session.outputNames.firstOrNull { it.contains("label", ignoreCase = true) }
            ?: session.outputNames.first()
        probabilityOutputName = session.outputNames.firstOrNull {
            it.contains("probability", ignoreCase = true)
        }

        knownLabels = loadLabels().map(::normalizeLabel).toSet()
    }

    fun predict(text: String): String = predictDetailed(text).finalIntent

    fun predictDetailed(text: String): OnnxPrediction {
        val cleanedText = text.trim()
        if (cleanedText.length < 3) {
            return OnnxPrediction(
                finalIntent = "unknown",
                rawLabel = "",
                normalizedLabel = "unknown",
                confidence = null
            )
        }

        OnnxTensor.createTensor(environment, arrayOf(cleanedText)).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val rawLabel = extractRawLabel(output)
                val normalizedLabel = normalizeLabel(rawLabel.orEmpty())
                val confidence = extractConfidence(output)

                val finalIntent = when {
                    normalizedLabel == "unknown" -> "unknown"
                    confidence != null && confidence < minConfidence -> "unknown"
                    knownLabels.isNotEmpty() && normalizedLabel !in knownLabels -> "unknown"
                    else -> normalizedLabel
                }

                return OnnxPrediction(
                    finalIntent = finalIntent,
                    rawLabel = rawLabel.orEmpty(),
                    normalizedLabel = normalizedLabel,
                    confidence = confidence
                )
            }
        }
    }

    private fun extractRawLabel(output: OrtSession.Result): String? {
        val labelValue = output[labelOutputName]?.orElse(null)?.getValue()
        return when (labelValue) {
            is Array<*> -> labelValue.firstOrNull()?.toString()
            is List<*> -> labelValue.firstOrNull()?.toString()
            else -> labelValue?.toString()
        }
    }

    private fun extractConfidence(output: OrtSession.Result): Float? {
        val name = probabilityOutputName ?: return null
        val probabilityValue = output[name]?.orElse(null)?.value

        val probabilityMap = when (probabilityValue) {
            is List<*> -> probabilityValue.firstOrNull() as? Map<*, *>
            is Array<*> -> probabilityValue.firstOrNull() as? Map<*, *>
            is Map<*, *> -> probabilityValue
            else -> null
        } ?: return null

        return probabilityMap.values
            .mapNotNull { (it as? Number)?.toFloat() }
            .maxOrNull()
    }

    private fun loadLabels(): List<String> {
        return try {
            val raw = appContext.assets.open("intent_labels.json")
                .bufferedReader()
                .use { it.readText() }

            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val label = array.optString(i).trim()
                    if (label.isNotEmpty()) add(label)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeLabel(label: String): String {
        if (label.isBlank()) return "unknown"
        return label.trim().lowercase().replace(" ", "_")
    }

    override fun close() {
        session.close()
    }
}