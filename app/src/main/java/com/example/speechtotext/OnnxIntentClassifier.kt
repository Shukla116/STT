package com.example.speechtotext

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import java.util.Collections

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
    private val knownLabels: List<String>
    private val minConfidence = 0.15f // Multilingual safety matching margin

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

        knownLabels = loadLabels()
    }

    fun predict(text: String): String = predictDetailed(text).finalIntent

    fun predictDetailed(text: String): OnnxPrediction {
        try {
            // Processing token streams for both English and French text
            val inputMatrix = arrayOf(arrayOf(text.trim().lowercase()))
            val inputTensor = OnnxTensor.createTensor(environment, inputMatrix)

            val outputs = session.run(Collections.singletonMap(inputName, inputTensor))
            inputTensor.close()

            outputs.use { output ->
                val rawLabel = extractLabel(output) ?: "unknown"
                val confidence = extractConfidence(output, rawLabel) ?: 1.0f

                // Fixed: Strict case-insensitive key structural mapper
                val matchedLabel = knownLabels.firstOrNull { it.equals(rawLabel, ignoreCase = true) }

                val finalIntent = if (matchedLabel != null) {
                    matchedLabel
                } else {
                    "Default Fallback Intent"
                }

                return OnnxPrediction(
                    finalIntent = finalIntent,
                    rawLabel = rawLabel,
                    normalizedLabel = finalIntent,
                    confidence = confidence
                )
            }
        } catch (e: Exception) {
            return OnnxPrediction("Default Fallback Intent", "unknown", "unknown", 0.0f)
        }
    }

    private fun extractLabel(output: OrtSession.Result): String? {
        val value = output[labelOutputName]?.orElse(null)?.value ?: return null
        return when (value) {
            is Array<*> -> value.firstOrNull()?.toString()
            is List<*> -> value.firstOrNull()?.toString()
            else -> value.toString()
        }
    }

    private fun extractConfidence(output: OrtSession.Result, predictedLabel: String): Float? {
        val name = probabilityOutputName ?: return null
        val probabilityValue = output[name]?.orElse(null)?.value ?: return null

        val probabilityMap = when (probabilityValue) {
            is List<*> -> probabilityValue.firstOrNull() as? Map<*, *>
            is Array<*> -> probabilityValue.firstOrNull() as? Map<*, *>
            is Map<*, *> -> probabilityValue
            else -> null
        } ?: return null

        val matchedKey = probabilityMap.keys.firstOrNull { it.toString().equals(predictedLabel, ignoreCase = true) }
        val finalVal = probabilityMap[matchedKey]

        return when (finalVal) {
            is Number -> finalVal.toFloat()
            else -> null
        }
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

    override fun close() {
        session.close()
    }
}