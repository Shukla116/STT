package com.example.speechtotext

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val logTag = "MainActivity"
    private val RECORD_AUDIO_PERMISSION_CODE = 101

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var spinnerLanguage: Spinner
    private lateinit var tvFullText: TextView
    private lateinit var btnClear: Button
    private lateinit var tvIntent: TextView
    private lateinit var progressBar: ProgressBar

    private var intentClassifier: OnnxIntentClassifier? = null
    private var isListening = false

    private var nluSchemaJson: JSONObject? = null
    private var nluEntitiesJson: JSONObject? = null
    private var activeSlotSession: SlotSession? = null

    private val languages = listOf(
        Pair("English (US)", "en-US"),
        Pair("French (France)", "fr-FR"),
        Pair("Hindi (भारत)", "hi-IN")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tv_result)
        tvStatus = findViewById(R.id.tv_status)
        btnMic = findViewById(R.id.btn_mic)
        spinnerLanguage = findViewById(R.id.spinner_language)
        tvFullText = findViewById(R.id.tv_full_text)
        btnClear = findViewById(R.id.btn_clear)
        tvIntent = findViewById(R.id.tvIntent)
        progressBar = findViewById(R.id.progress_bar)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        loadNluAssets()

        intentClassifier = OnnxIntentClassifier(this)
        textToSpeech = TextToSpeech(this, this)
        initializeSpeechRecognizer()

        btnMic.setOnClickListener {
            if (checkPermission()) {
                if (isListening) speechRecognizer.stopListening() else startListening()
            }
        }

        btnClear.setOnClickListener {
            tvFullText.text = ""
            tvIntent.text = "Intent: None"
            tvResult.text = "Fulfillment Output"
            resetConversation()
        }
    }

    private fun loadNluAssets() {
        try {
            nluSchemaJson = JSONObject(assets.open("nlu_schema.json").bufferedReader().use { it.readText() })
            nluEntitiesJson = JSONObject(assets.open("nlu_entities.json").bufferedReader().use { it.readText() })
            Log.d(logTag, "✅ NLU Assets and entities loaded completely.")
        } catch (e: Exception) {
            Log.e(logTag, "❌ Error loading schema assets.", e)
        }
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "🎙️ Listening..."
            }
            override fun onBeginningOfSpeech() {
                progressBar.visibility = View.VISIBLE
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                progressBar.visibility = View.GONE
            }

            override fun onError(error: Int) {
                progressBar.visibility = View.GONE
                Log.d(logTag, "Speech Recognizer Error Code: $error")

                // Agar active conversation chal rahi hai aur koi bhi error aaye (timeout, no match, etc.)
                if (activeSlotSession != null) {
                    Log.d(logTag, "Session active hai, timeout bypass karke mic fir se start ho raha hai...")
                    // Thoda sa gap dekar automatic listening fir se start karenge
                    btnMic.postDelayed({
                        startListening()
                    }, 400)
                } else {
                    // Agar normal mode hai toh idle par daal dein
                    isListening = false
                    btnMic.setImageResource(R.drawable.ic_mic)
                    tvStatus.text = "⏸ Idle"
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                btnMic.setImageResource(R.drawable.ic_mic)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val rawUtterance = matches[0]
                    tvFullText.text = "🗣️ Heard: \"$rawUtterance\""
                    processNluFlow(rawUtterance)
                } else {
                    // Safe handling agar results blank aayein toh session recover karein
                    if (activeSlotSession != null) startListening()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        isListening = true
        btnMic.setImageResource(R.drawable.ic_mic_active)
        val selectedIndex = spinnerLanguage.selectedItemPosition
        val currentLocaleTag = languages[selectedIndex].second

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocaleTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.startListening(intent)
    }

    private fun processNluFlow(userInput: String) {
        val cleanInput = userInput.trim()
        val currentLangCode = languages[spinnerLanguage.selectedItemPosition].second

        // ✅ If there is an active multi-turn session running
        if (activeSlotSession != null) {
            handleSlotFillingFlow(cleanInput)
            return
        }

        val prediction = intentClassifier?.predictDetailed(cleanInput)
        val predictedIntent = prediction?.finalIntent ?: "Default Fallback Intent"
        val confidence = prediction?.confidence ?: 0f

        tvIntent.text = "Intent: $predictedIntent"

        if (predictedIntent == "Default Fallback Intent" || predictedIntent == "unknown") {
            saveUnknownIntentToLocalJson(cleanInput, predictedIntent, confidence)
            val encodedQuery = URLEncoder.encode(cleanInput, "UTF-8")
            val genAiLink = "https://www.google.com/search?q=$encodedQuery"
            val fallbackMsg = if (currentLangCode.startsWith("fr")) "Désolé, commande inconnue." else "Yeh command database mein nahi hai."
            tvResult.text = fallbackMsg
            speakOutput(fallbackMsg, false)
            resetConversation()
            return
        }

        val intentsObj = nluSchemaJson?.optJSONObject("intents")
        val intentConfig = intentsObj?.optJSONObject(predictedIntent) ?: return

        val slotsArray = intentConfig.optJSONArray("slots")
        if (slotsArray != null && slotsArray.length() > 0) {
            val configsList = ArrayList<SlotConfig>()
            for (i in 0 until slotsArray.length()) {
                val sObj = slotsArray.getJSONObject(i)
                configsList.add(
                    SlotConfig(
                        name = sObj.getString("name"),
                        entity = sObj.getString("entity"),
                        required = sObj.optBoolean("required", true),
                        prompt = sObj.optString("prompt", "")
                    )
                )
            }

            // Init active multi-turn tracking schema
            activeSlotSession = SlotSession(
                intentName = predictedIntent,
                requiredSlots = configsList,
                filledSlots = HashMap(),
                currentSlotIndex = 0
            )
            // Start slot dynamic extraction pipeline
            handleSlotFillingFlow("")
        } else {
            executeIntentAction(predictedIntent)
        }
    }

    private fun handleSlotFillingFlow(userInput: String) {
        val session = activeSlotSession ?: return
        val currentLangCode = languages[spinnerLanguage.selectedItemPosition].second
        val totalSlots = session.requiredSlots.size
        var currentIndex = session.currentSlotIndex

        // 📝 1. Save spoken string if user replied to an active prompt question
        if (userInput.isNotEmpty() && currentIndex > 0 && currentIndex <= totalSlots) {
            val targetedSlot = session.requiredSlots[currentIndex - 1]
            val resolvedValue = entityResolutionLookup(targetedSlot.entity, userInput)
            session.filledSlots[targetedSlot.name] = resolvedValue
            Log.d(logTag, "Slot saved -> key: ${targetedSlot.name}, val: $resolvedValue")
        }

        // 🔍 2. Dynamic check loop to scan for next missing required slots
        var nextRequiredSlotIndex = -1
        for (i in 0 until totalSlots) {
            val slot = session.requiredSlots[i]
            if (slot.required && !session.filledSlots.containsKey(slot.name)) {
                nextRequiredSlotIndex = i
                break
            }
        }

        // 🚀 3. Evaluate dialogue branch decision tree
        if (nextRequiredSlotIndex != -1) {
            // Update current index pointer state dynamically before sending question
            session.currentSlotIndex = nextRequiredSlotIndex + 1

            val currentSlot = session.requiredSlots[nextRequiredSlotIndex]
            val basePrompt = currentSlot.prompt

            val finalPrompt = if (currentLangCode.startsWith("fr")) {
                when {
                    basePrompt.contains("name of the memory", true) -> "Quel est le nom de la mémoire ?"
                    basePrompt.contains("be reminded", true) || basePrompt.contains("want to be reminded", true) -> "Quel rappel voulez-vous configurer ?"
                    basePrompt.contains("When should I", true) || basePrompt.contains("remind you", true) -> "Pour quelle heure ?"
                    else -> basePrompt
                }
            } else {
                when {
                    basePrompt.contains("name of the memory", true) -> "Which memory do you want to change?"
                    basePrompt.contains("be reminded", true) || basePrompt.contains("want to be reminded", true) -> "What do you want to be reminded about?"
                    basePrompt.contains("When should I", true) || basePrompt.contains("remind you", true) -> "For what time should I set the reminder?"
                    else -> basePrompt
                }
            }

            tvResult.text = finalPrompt
            speakOutput(finalPrompt, true) // Speaks up and re-triggers microphone listener seamlessly
        } else {
            // All mandatory slots collected successfully, advance execution layer
            executeIntentAction(session.intentName)
        }
    }

    private fun entityResolutionLookup(entityName: String, userInput: String): String {
        val entityObj = nluEntitiesJson?.optJSONObject(entityName) ?: return userInput
        val valuesObj = entityObj.optJSONObject("values") ?: return userInput
        val cleanInput = userInput.lowercase().trim()
        val keys = valuesObj.keys()
        while (keys.hasNext()) {
            val canonicalKey = keys.next()
            val synonymsArray = valuesObj.getJSONArray(canonicalKey)
            for (i in 0 until synonymsArray.length()) {
                if (synonymsArray.getString(i).lowercase().trim() == cleanInput) {
                    return canonicalKey
                }
            }
        }
        return userInput
    }

    private fun executeIntentAction(intent: String) {
        val currentLangCode = languages[spinnerLanguage.selectedItemPosition].second
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = activeSlotSession

        when (intent) {
            "Cmd.VolumeIncrease" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                val text = if (currentLangCode.startsWith("fr")) "Volume augmenté." else "Volume badha diya hai."
                tvResult.text = text; speakOutput(text); resetConversation()
            }
            "Cmd.VolumeDecrease" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                val text = if (currentLangCode.startsWith("fr")) "Volume diminué." else "Volume kam kar diya hai."
                tvResult.text = text; speakOutput(text); resetConversation()
            }
            "Cmd.VolumeMute" -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                val text = if (currentLangCode.startsWith("fr")) "Son coupé." else "Phone mute kar diya hai."
                tvResult.text = text; speakOutput(text); resetConversation()
            }
            "Cmd.VolumeUnmute" -> {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 3, AudioManager.FLAG_SHOW_UI)
                val text = if (currentLangCode.startsWith("fr")) "Son rétabli." else "Phone unmute kar diya hai."
                tvResult.text = text; speakOutput(text); resetConversation()
            }

            "reminders.add" -> {
                var reminderTitle = "Voice Assistant Reminder"
                var rawTimeText = ""

                if (session != null) {
                    if (session.filledSlots.containsKey("name")) {
                        reminderTitle = session.filledSlots["name"] ?: reminderTitle
                    }
                    if (session.filledSlots.containsKey("date-time")) {
                        rawTimeText = (session.filledSlots["date-time"] ?: "").lowercase().trim()
                    }
                }

                // 🛑 1. CHECK: Reject past dates immediately
                if (rawTimeText.contains("yesterday") || rawTimeText.contains("hier")) {
                    val pastMsg = if (currentLangCode.startsWith("fr")) "Désolé, impossible de mettre un rappel dans le passé." else "Maaf kijiye, gujra hua samay alarm mein set nahi ho sakta."
                    tvResult.text = "Blocked: Past Date ($rawTimeText)"
                    speakOutput(pastMsg)
                    resetConversation()
                    return
                }

                // 🗓️ Bulletproof Parsing Engine
                val now = Calendar.getInstance()
                val targetCal = Calendar.getInstance()

                var extractedHour = 9
                var extractedMinute = 0

                val timePattern = "(\\d+)(?::(\\d+))?".toRegex()
                val match = timePattern.find(rawTimeText)

                if (match != null) {
                    extractedHour = match.groupValues[1].toInt()
                    if (match.groupValues[2].isNotEmpty()) {
                        extractedMinute = match.groupValues[2].toInt()
                    }
                }

                val isPm = rawTimeText.contains("pm") || rawTimeText.contains("p.m.") || rawTimeText.contains("raat")
                val isAm = rawTimeText.contains("am") || rawTimeText.contains("a.m.") || rawTimeText.contains("subah")

                if (isPm && extractedHour < 12) extractedHour += 12
                if (isAm && extractedHour == 12) extractedHour = 0

                targetCal.set(Calendar.HOUR_OF_DAY, extractedHour)
                targetCal.set(Calendar.MINUTE, extractedMinute)
                targetCal.set(Calendar.SECOND, 0)
                targetCal.set(Calendar.MILLISECOND, 0)

                // Smart timeline tracking conversions
                if (rawTimeText.contains("tomorrow") || rawTimeText.contains("demain") || rawTimeText.contains("kal")) {
                    targetCal.add(Calendar.DAY_OF_YEAR, 1)
                } else if (targetCal.before(now)) {
                    if (!isPm && !isAm && extractedHour < 12) {
                        targetCal.add(Calendar.HOUR_OF_DAY, 12)
                        if (targetCal.before(now)) {
                            targetCal.add(Calendar.HOUR_OF_DAY, -12)
                            targetCal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                    } else {
                        targetCal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                val finalHour = targetCal.get(Calendar.HOUR_OF_DAY)
                val finalMinute = targetCal.get(Calendar.MINUTE)

                try {
                    val intentAlarm = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, reminderTitle)
                        putExtra(AlarmClock.EXTRA_HOUR, finalHour)
                        putExtra(AlarmClock.EXTRA_MINUTES, finalMinute)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false) // User ko screen dikhayein taaki safe rahe
                    }


                    startActivity(intentAlarm)

                    val sdf = SimpleDateFormat("hh:mm a (dd MMM)", Locale.getDefault())
                    val printedTime = sdf.format(targetCal.time)
                    val successSpeech = if (currentLangCode.startsWith("fr")) {
                        "Rappel configuré pour $printedTime."
                    } else {
                        "Reminder set kar diya hai: $printedTime ke liye."
                    }
                    tvResult.text = "✅ System Alarm Set: $reminderTitle at $printedTime"
                    speakOutput(successSpeech)

                } catch (e: android.content.ActivityNotFoundException) {
                    // Agar sach mein phone mein koi clock app nahi mila tab yeh chalega
                    val noAppMsg = "Aapke phone mein koi default Alarm/Clock app nahi mila jo is command ko handle kar sake."
                    tvResult.text = "❌ Error: Alarm App Not Found"
                    speakOutput(noAppMsg)
                    Log.e(logTag, "Clock app missing completely", e)
                } catch (e: Exception) {
                    tvResult.text = "Error: ${e.localizedMessage}"
                    Log.e(logTag, "Alarm implementation error", e)
                }
                resetConversation()
            }

            "Cmd.MemoryChange" -> {
                var chosenMemory = "Everyday"
                if (session != null && session.filledSlots.containsKey("MemoryName")) {
                    chosenMemory = session.filledSlots["MemoryName"] ?: chosenMemory
                }
                val msg = if (currentLangCode.startsWith("fr")) "Mémoire changée à: $chosenMemory" else "Memory changed to: $chosenMemory"
                tvResult.text = msg; speakOutput(msg); resetConversation()
            }
            else -> { resetConversation() }
        }
    }

    private fun speakOutput(text: String, isSlotPrompt: Boolean = false) {
        if (!::textToSpeech.isInitialized) return
        val utteranceId = if (isSlotPrompt) "SLOT_PROMPT" else "FINAL_FULFILLMENT"
        val currentLocaleTag = languages[spinnerLanguage.selectedItemPosition].second
        textToSpeech.language = Locale.forLanguageTag(currentLocaleTag)
        val ttsParams = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, ttsParams, utteranceId)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.setPitch(1.0f)
            textToSpeech.setSpeechRate(1.0f)
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "SLOT_PROMPT") {
                        btnMic.postDelayed({ startListening() }, 500)
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun saveUnknownIntentToLocalJson(phrase: String, predicted: String, score: Float) {
        try {
            val jsonFile = File(filesDir, "unknown_intents.json")
            val jsonArray = if (jsonFile.exists() && jsonFile.length() > 0) JSONArray(jsonFile.readText()) else JSONArray()
            jsonArray.put(JSONObject().apply {
                put("phrase", phrase); put("predicted_as", predicted); put("confidence_score", score.toDouble())
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            })
            jsonFile.writeText(jsonArray.toString(4))
        } catch (_: Exception) {}
    }

    private fun resetConversation() {
        activeSlotSession = null; isListening = false
        runOnUiThread { btnMic.setImageResource(R.drawable.ic_mic) }
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            false
        } else true
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        if (::textToSpeech.isInitialized) { textToSpeech.stop(); textToSpeech.shutdown() }
        intentClassifier?.close()
    }
}