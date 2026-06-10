package com.example.speechtotext


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.json.JSONObject
import java.util.*
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val logTag = "MainActivity"

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
    private var fullTranscript = StringBuilder()

    // --- JSON NLU Engine Variables ---
    private var nluSchemaJson: JSONObject? = null
    private var activeSession: SlotSession? = null // Current running session memory
    private val UTTERANCE_ID = "BotUtteranceID"

    private val languages = listOf(
        Pair("Auto Detect", ""), Pair("Hindi (हिंदी)", "hi-IN"), Pair("English (US)", "en-US")
    )

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupLanguageSpinner()
        setupSpeechRecognizer()

        textToSpeech = TextToSpeech(this, this)

        checkPermission()
        intentClassifier = OnnxIntentClassifier(this)

        // Load your nlu_schema.json from assets folder
        loadNluSchema()
    }

    private fun loadNluSchema() {
        try {
            val jsonString = assets.open("nlu_schema.json").bufferedReader().use { it.readText() }
            nluSchemaJson = JSONObject(jsonString).getJSONObject("intents")
            Log.d(logTag, "nlu_schema.json loaded successfully!")
        } catch (e: Exception) {
            Log.e(logTag, "Failed to load nlu_schema.json", e)
        }
    }

    private fun initViews() {
        tvResult = findViewById(R.id.tv_result)
        tvStatus = findViewById(R.id.tv_status)
        btnMic = findViewById(R.id.btn_mic)
        spinnerLanguage = findViewById(R.id.spinner_language)
        tvFullText = findViewById(R.id.tv_full_text)
        btnClear = findViewById(R.id.btn_clear)
        progressBar = findViewById(R.id.progress_bar)
        tvIntent = findViewById(R.id.tvIntent)

        btnMic.setOnClickListener {
            if (isListening) stopListening() else startListening()
        }

        btnClear.setOnClickListener {
            fullTranscript.clear()
            tvFullText.text = getString(R.string.transcript_placeholder)
            tvResult.text = ""
            resetConversation()
        }
    }

    private fun setupLanguageSpinner() {
        val languageNames = languages.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter
        spinnerLanguage.setSelection(1) // Default Hindi
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale("hi", "IN")
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { stopListening() }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { startListening() }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun speakOutput(text: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvStatus.text = "❌ Speech Recognition is not available"
            btnMic.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                val currentStatusText = if (activeSession != null) "Slot: ${activeSession?.requiredSlots?.getOrNull(activeSession!!.currentSlotIndex)?.name}" else "IDLE"
                tvStatus.text = "🎤 Sun raha hun... ($currentStatusText)"
                progressBar.visibility = View.VISIBLE
                isListening = true
                btnMic.setImageResource(R.drawable.ic_mic_active)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { progressBar.visibility = View.GONE }
            override fun onError(error: Int) {
                isListening = false
                btnMic.setImageResource(R.drawable.ic_mic)
                tvStatus.text = "❌ Mic error or timeout. Retry."
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                btnMic.setImageResource(R.drawable.ic_mic)

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    tvResult.text = recognizedText

                    if (fullTranscript.isNotEmpty()) fullTranscript.append("\n")
                    fullTranscript.append(recognizedText)
                    tvFullText.text = fullTranscript.toString()

                    // Execute JSON NLU Processor
                    processNluFlow(recognizedText)

                } else {
                    tvStatus.text = "⚠️ Kuch recognize nahi hua"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    // --- DYNAMIC JSON NLU PROCESSOR ENGINE ---
    private fun processNluFlow(userInput: String) {

        // TURN 1: Agar active session nahi hai, toh ONNX se primary Intent pehchano
        if (activeSession == null) {
            val prediction = try { intentClassifier?.predictDetailed(userInput) } catch (e: Exception) { null }
            val predictedIntent = prediction?.finalIntent ?: "unknown"

            // ONNX predictedIntent ko hum nlu_schema.json keys ke sath dynamic map karenge
            // Jaise: ONNX ka 'reminder' -> 'reminders.add' ya aapke model labels ke according map kar sakte hain.
            // Hum yahan direct check karenge agar schema me exact ya content matched key milti hai:
            var jsonIntentKey = predictedIntent

            // Aapke labels aur schema alignment ke liye safe check
            if (predictedIntent == "reminder") jsonIntentKey = "reminders.add"
            if (predictedIntent == "memory") jsonIntentKey = "Cmd.MemoryChange"

            tvIntent.text = "Intent: $jsonIntentKey"

            if (nluSchemaJson != null && nluSchemaJson!!.has(jsonIntentKey)) {
                val intentConfig = nluSchemaJson!!.getJSONObject(jsonIntentKey)
                val slotsArray = intentConfig.getJSONArray("slots")

                val slotList = ArrayList<SlotConfig>()
                for (i in 0 until slotsArray.length()) {
                    val slotObj = slotsArray.getJSONObject(i)
                    slotList.add(SlotConfig(
                        name = slotObj.getString("name"),
                        entity = slotObj.getString("entity"),
                        required = slotObj.getBoolean("required"),
                        prompt = slotObj.optString("prompt", "")
                    ))
                }

                // Filter out only required slots
                val requiredSlots = slotList.filter { it.required }

                if (requiredSlots.isNotEmpty()) {
                    // Session initiate karein dynamically slots fill karne ke liye
                    activeSession = SlotSession(intentName = jsonIntentKey, requiredSlots = requiredSlots)
                    // Pehle slot ka prompt pucho
                    val firstPrompt = requiredSlots[0].prompt
                    speakOutput(firstPrompt)
                    tvStatus.text = "🤖 Slot Filling Started..."
                } else {
                    // Agar koi slot required nahi hai, toh direct dynamic fulfillment text bol do
                    val fulfillment = intentConfig.optString("fulfillment", "Command executed")
                    speakOutput(fulfillment)
                    resetConversation()
                }
            } else {
                speakOutput("Maaf kijiye, mujhe samajh nahi aaya.")
                resetConversation()
            }
            return
        }

        // TURN 2+ : Slot Filling Loop (Automatic dynamic questions mapping)
        activeSession?.let { session ->
            val currentSlot = session.requiredSlots[session.currentSlotIndex]

            // User ka response save karo slot name ke key par
            session.filledSlots[currentSlot.name] = userInput

            // Agla index check karo
            session.currentSlotIndex++

            if (session.currentSlotIndex < session.requiredSlots.size) {
                // Agar abhi aur required slots bache hain, toh unka prompt pucho
                val nextSlot = session.requiredSlots[session.currentSlotIndex]
                speakOutput(nextSlot.prompt)
            } else {
                // Saare slots bhar gaye! Final execution!
                if (nluSchemaJson != null && nluSchemaJson!!.has(session.intentName)) {
                    val finalConfig = nluSchemaJson!!.getJSONObject(session.intentName)
                    val fulfillmentText = finalConfig.optString("fulfillment", "Kaam ho gaya.")

                    speakOutput(fulfillmentText)

                    // Display filled slot parameters onto UI
                    val summary = StringBuilder("Intent: ${session.intentName}\n\nFilled Parameters:\n")
                    for ((key, value) in session.filledSlots) {
                        summary.append("- $key : $value\n")
                    }
                    tvFullText.text = summary.toString()
                }
                resetConversation()
            }
        }
    }

    private fun resetConversation() {
        activeSession = null
        tvStatus.text = "✅ Ready for new intent."
    }

    private fun startListening() {
        if (!checkPermission()) return
        val selectedIndex = spinnerLanguage.selectedItemPosition
        val selectedLocale = languages[selectedIndex].second

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (selectedLocale.isEmpty()) { putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault()) }
            else { putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale) }
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { speechRecognizer.startListening(intent) } catch (e: Exception) { tvStatus.text = "❌ Error: ${e.message}" }
    }

    private fun stopListening() {
        speechRecognizer.stopListening()
        isListening = false
        btnMic.setImageResource(R.drawable.ic_mic)
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            false
        } else { true }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::textToSpeech.isInitialized) { textToSpeech.stop(); textToSpeech.shutdown() }
        intentClassifier?.close()
    }
}
//
//import android.Manifest
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.speech.RecognitionListener
//import android.speech.RecognizerIntent
//import android.speech.SpeechRecognizer
//import android.util.Log
//import android.view.View
//import android.widget.*
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import java.util.*
//
//class MainActivity : AppCompatActivity() {
//
//    private val logTag = "MainActivity"
//
//    private lateinit var speechRecognizer: SpeechRecognizer
//    private lateinit var tvResult: TextView
//    private lateinit var tvStatus: TextView
//    private lateinit var btnMic: ImageButton
//    private lateinit var spinnerLanguage: Spinner
//    private lateinit var tvFullText: TextView
//    private lateinit var btnClear: Button
//    private lateinit var tvIntent: TextView
//    private lateinit var progressBar: ProgressBar
//
//    private var intentClassifier: OnnxIntentClassifier? = null
//
//    private var isListening = false
//    private var fullTranscript = StringBuilder()
//
//    // Sabhi supported languages - Name aur Locale code
//    private val languages = listOf(
//        Pair("Auto Detect", ""),
//        Pair("Hindi (हिंदी)", "hi-IN"),
//        Pair("English (US)", "en-US"),
//        Pair("English (UK)", "en-GB"),
//        Pair("English (India)", "en-IN"),
//        Pair("Bengali (বাংলা)", "bn-IN"),
//        Pair("Gujarati (ગુજરાતી)", "gu-IN"),
//        Pair("Kannada (ಕನ್ನಡ)", "kn-IN"),
//        Pair("Malayalam (മലയാളം)", "ml-IN"),
//        Pair("Marathi (मराठी)", "mr-IN"),
//        Pair("Odia (ଓଡ଼ିଆ)", "or-IN"),
//        Pair("Punjabi (ਪੰਜਾਬੀ)", "pa-IN"),
//        Pair("Tamil (தமிழ்)", "ta-IN"),
//        Pair("Telugu (తెలుగు)", "te-IN"),
//        Pair("Urdu (اردو)", "ur-IN"),
//        Pair("Arabic (العربية)", "ar-SA"),
//        Pair("Chinese Simplified (普通话)", "zh-CN"),
//        Pair("Chinese Traditional (廣東話)", "zh-TW"),
//        Pair("French (Français)", "fr-FR"),
//        Pair("German (Deutsch)", "de-DE"),
//        Pair("Indonesian (Bahasa)", "id-ID"),
//        Pair("Italian (Italiano)", "it-IT"),
//        Pair("Japanese (日本語)", "ja-JP"),
//        Pair("Korean (한국어)", "ko-KR"),
//        Pair("Malay (Bahasa Melayu)", "ms-MY"),
//        Pair("Portuguese (Português)", "pt-BR"),
//        Pair("Russian (Русский)", "ru-RU"),
//        Pair("Spanish (Español)", "es-ES"),
//        Pair("Thai (ภาษาไทย)", "th-TH"),
//        Pair("Turkish (Türkçe)", "tr-TR"),
//        Pair("Vietnamese (Tiếng Việt)", "vi-VN"),
//        Pair("Nepali (नेपाली)", "ne-NP"),
//        Pair("Sinhala (සිංහල)", "si-LK"),
//        Pair("Swahili (Kiswahili)", "sw-TZ"),
//        Pair("Afrikaans", "af-ZA"),
//        Pair("Albanian (Shqip)", "sq-AL"),
//        Pair("Armenian (Հայերեն)", "hy-AM"),
//        Pair("Azerbaijani (Azərbaycan)", "az-AZ"),
//        Pair("Basque (Euskara)", "eu-ES"),
//        Pair("Belarusian (Беларуская)", "be-BY"),
//        Pair("Bulgarian (Български)", "bg-BG"),
//        Pair("Catalan (Català)", "ca-ES"),
//        Pair("Croatian (Hrvatski)", "hr-HR"),
//        Pair("Czech (Čeština)", "cs-CZ"),
//        Pair("Danish (Dansk)", "da-DK"),
//        Pair("Dutch (Nederlands)", "nl-NL"),
//        Pair("Estonian (Eesti)", "et-EE"),
//        Pair("Filipino (Tagalog)", "fil-PH"),
//        Pair("Finnish (Suomi)", "fi-FI"),
//        Pair("Galician (Galego)", "gl-ES"),
//        Pair("Georgian (ქართული)", "ka-GE"),
//        Pair("Greek (Ελληνικά)", "el-GR"),
//        Pair("Hebrew (עברית)", "iw-IL"),
//        Pair("Hungarian (Magyar)", "hu-HU"),
//        Pair("Icelandic (Íslenska)", "is-IS"),
//        Pair("Irish (Gaeilge)", "ga-IE"),
//        Pair("Latvian (Latviešu)", "lv-LV"),
//        Pair("Lithuanian (Lietuvių)", "lt-LT"),
//        Pair("Macedonian (Македонски)", "mk-MK"),
//        Pair("Maltese (Malti)", "mt-MT"),
//        Pair("Norwegian (Norsk)", "nb-NO"),
//        Pair("Persian (فارسی)", "fa-IR"),
//        Pair("Polish (Polski)", "pl-PL"),
//        Pair("Romanian (Română)", "ro-RO"),
//        Pair("Serbian (Српски)", "sr-RS"),
//        Pair("Slovak (Slovenčina)", "sk-SK"),
//        Pair("Slovenian (Slovenščina)", "sl-SI"),
//        Pair("Swedish (Svenska)", "sv-SE"),
//        Pair("Ukrainian (Українська)", "uk-UA"),
//        Pair("Uzbek (Oʻzbek)", "uz-UZ"),
//        Pair("Welsh (Cymraeg)", "cy-GB"),
//        Pair("Zulu (isiZulu)", "zu-ZA")
//    )
//
//    companion object {
//        private const val RECORD_AUDIO_PERMISSION_CODE = 101
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        initViews()
//        setupLanguageSpinner()
//        setupSpeechRecognizer()
//        checkPermission()
//        intentClassifier = OnnxIntentClassifier(this)
//    }
//
//    private fun initViews() {
//        tvResult = findViewById(R.id.tv_result)
//        tvStatus = findViewById(R.id.tv_status)
//        btnMic = findViewById(R.id.btn_mic)
//        spinnerLanguage = findViewById(R.id.spinner_language)
//        tvFullText = findViewById(R.id.tv_full_text)
//        btnClear = findViewById(R.id.btn_clear)
//        progressBar = findViewById(R.id.progress_bar)
//        tvIntent = findViewById(R.id.tvIntent)
//
//        btnMic.setOnClickListener {
//            if (isListening) stopListening() else startListening()
//        }
//
//        btnClear.setOnClickListener {
//            fullTranscript.clear()
//            tvFullText.text = getString(R.string.transcript_placeholder)
//            tvResult.text = ""
//        }
//    }
//
//    private fun setupLanguageSpinner() {
//        val languageNames = languages.map { it.first }
//        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        spinnerLanguage.adapter = adapter
//        // Default Hindi select karo
//        spinnerLanguage.setSelection(1)
//    }
//
//    private fun setupSpeechRecognizer() {
//        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
//            tvStatus.text = "❌ Speech Recognition is not available on this device"
//            btnMic.isEnabled = false
//            return
//        }
//
//        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
//        speechRecognizer.setRecognitionListener(object : RecognitionListener {
//
//            override fun onReadyForSpeech(params: Bundle?) {
//                tvStatus.text = "🎤 Bol rahe hain... (Speaking...)"
//                progressBar.visibility = View.VISIBLE
//                isListening = true
//                btnMic.setImageResource(R.drawable.ic_mic_active)
//            }
//
//            override fun onBeginningOfSpeech() {
//                tvStatus.text = "🔊 Aawaz aa rahi hai..."
//            }
//
//            override fun onRmsChanged(rmsdB: Float) {
//                // Volume level update (optional animation ke liye use kar sakte hain)
//            }
//
//            override fun onBufferReceived(buffer: ByteArray?) {}
//
//            override fun onEndOfSpeech() {
//                tvStatus.text = "⏳ Process ho raha hai..."
//                isListening = false
//                btnMic.setImageResource(R.drawable.ic_mic)
//            }
//
//            override fun onError(error: Int) {
//                isListening = false
//                progressBar.visibility = View.GONE
//                btnMic.setImageResource(R.drawable.ic_mic)
//
//                val errorMsg = when (error) {
//                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
//                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
//                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission chahiye! Mic allow karein"
//                    SpeechRecognizer.ERROR_NETWORK -> "Network error (offline mode check karein)"
//                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
//                    SpeechRecognizer.ERROR_NO_MATCH -> "Koi match nahi mila, dobara bolein"
//                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai"
//                    SpeechRecognizer.ERROR_SERVER -> "Server error"
//                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Koi aawaz nahi ayi"
//                    else -> "Unknown error: $error"
//                }
//                tvStatus.text = "❌ Error: $errorMsg"
//            }
//
//            override fun onResults(results: Bundle?) {
//                isListening = false
//                progressBar.visibility = View.GONE
//                btnMic.setImageResource(R.drawable.ic_mic)
//
//                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//                if (!matches.isNullOrEmpty()) {
//                    val recognizedText = matches[0]
//                    tvResult.text = recognizedText
//
//                    // Full transcript mein add karo
//                    if (fullTranscript.isNotEmpty()) fullTranscript.append("\n")
//                    fullTranscript.append(recognizedText)
//                    val prediction = try {
//                        intentClassifier?.predictDetailed(fullTranscript.toString())
//                    } catch (exception: Exception) {
//                        Log.e(logTag, "Intent prediction failed", exception)
//                        null
//                    }
//                    tvFullText.text = fullTranscript.toString()
//
//                    tvIntent.text = "Intent: ${prediction?.finalIntent ?: "unknown"} (Confidence: ${prediction?.confidence ?: "N/A"})"
//
//
//                    tvStatus.text = if (prediction != null) {
//                        "✅ Recognition complete! Dobara bolne ke liye mic dabayein"
//                    } else {
//                        "⚠️ Recognition complete, but intent detection failed"
//                    }
//                } else {
//                    tvStatus.text = "⚠️ Kuch recognize nahi hua"
//                }
//            }
//
//            override fun onPartialResults(partialResults: Bundle?) {
//                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//                if (!partial.isNullOrEmpty()) {
//                    tvResult.text = "${partial[0]}..."
//                }
//            }
//
//            override fun onEvent(eventType: Int, params: Bundle?) {}
//        })
//    }
//
//    private fun startListening() {
//        if (!checkPermission()) return
//
//        val selectedIndex = spinnerLanguage.selectedItemPosition
//        val selectedLocale = languages[selectedIndex].second
//
//        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
//
//            // Language setting
//            if (selectedLocale.isEmpty()) {
//                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
//            } else {
//                putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale)
//                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLocale)
//                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
//            }
//
//            // Offline preference - yahi key hai offline ke liye!
//            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
//
//            // Partial results bhi dikhao
//            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
//
//            // Maximum results
//            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
//        }
//
//        try {
//            speechRecognizer.startListening(intent)
//            tvStatus.text = "📡 Start ho raha hai..."
//        } catch (e: Exception) {
//            tvStatus.text = "❌ Error: ${e.message}"
//        }
//    }
//
//    private fun stopListening() {
//        speechRecognizer.stopListening()
//        isListening = false
//        progressBar.visibility = View.GONE
//        btnMic.setImageResource(R.drawable.ic_mic)
//        tvStatus.text = "⏸ Ruk gaya"
//    }
//
//    private fun checkPermission(): Boolean {
//        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(
//                this,
//                arrayOf(Manifest.permission.RECORD_AUDIO),
//                RECORD_AUDIO_PERMISSION_CODE
//            )
//            false
//        } else {
//            true
//        }
//    }
//
//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<out String>,
//        grantResults: IntArray
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
//            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                tvStatus.text = "✅ Permission mili! Ab bol sakte hain"
//            } else {
//                tvStatus.text = "❌ Mic permission deny ki gayi! Settings mein jakar allow karein"
//                Toast.makeText(this, "Microphone permission required!", Toast.LENGTH_LONG).show()
//            }
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        if (::speechRecognizer.isInitialized) {
//            speechRecognizer.destroy()
//        }
//        intentClassifier?.close()
//    }
//
//}
