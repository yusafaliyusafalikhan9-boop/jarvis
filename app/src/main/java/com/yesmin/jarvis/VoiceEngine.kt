package com.yesmin.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * SpeechRecognizer को सँभालता है: हिंदी और Indian English के बीच बदलता है,
 * ERROR_RECOGNIZER_BUSY से बचता है, और गड़बड़ी पर सही बर्ताव करता है।
 * नतीजा आने के बाद यह खुद दोबारा नहीं सुनता: मालिक (Activity/Service) restart() बुलाता है।
 */
class VoiceEngine(private val ctx: Context, private val cb: Callbacks) {

    interface Callbacks {
        fun onListening()
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinalResults(candidates: List<String>)
        fun onGaveUp(reason: String, fatal: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busyErrors = 0
    private var langErrors = 0
    private var clientErrors = 0
    private var silentRounds = 0
    private var langIndex = 0
    private var useDetection = true

    private val languages = listOf("hi-IN", "en-IN")

    /** लगातार इतनी बार चुप्पी रहे तो रुक जाओ (बैटरी बचाने के लिए) */
    var maxSilentRounds = 6

    var active = false
        private set

    fun currentLanguage(): String = languages[langIndex % languages.size]

    fun start() {
        active = true
        silentRounds = 0
        schedule(300)
    }

    /** नतीजा सँभालने के बाद दोबारा सुनना शुरू करो */
    fun restart(delayMs: Long = 400) {
        active = true
        schedule(delayMs)
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // कोई बात नहीं
        }
        listening = false
    }

    fun destroy() {
        stop()
        recreate()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startNow() }, delayMs)
    }

    private fun recreate() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // कोई बात नहीं
        }
        recognizer = null
    }

    private fun giveUp(reason: String, fatal: Boolean) {
        active = false
        listening = false
        cb.onLevel(0f)
        cb.onGaveUp(reason, fatal)
    }

    private fun startNow() {
        if (!active || listening) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            giveUp("माइक की अनुमति चाहिए", true)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            giveUp("इस फ़ोन में आवाज़ पहचान नहीं मिली", true)
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (useDetection) {
                // नए Android पर अपने-आप भाषा पहचान/बदलाव (बेस्ट-एफ़र्ट: पुराने फ़ोन इसे अनदेखा करते हैं)
                val allowed = arrayListOf("hi-IN", "en-IN", "en-US")
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", allowed)
                putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced")
                putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", allowed)
            }
        }
        listening = true
        cb.onListening()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            recreate()
            clientErrors++
            if (clientErrors > 3) giveUp("आवाज़ पहचान शुरू नहीं हो पाई", false) else schedule(800)
        }
    }

    private fun silent() {
        silentRounds++
        langIndex++ // अगली बार दूसरी भाषा आज़माओ
        if (silentRounds > maxSilentRounds) {
            giveUp("आराम कर रहा हूँ। गोले को दबाकर जगाओ", false)
        } else {
            schedule(500)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            cb.onLevel(((rmsdB + 2f) / 10f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) cb.onPartial(p)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            cb.onLevel(0f)
            if (!active) return
            busyErrors = 0
            langErrors = 0
            clientErrors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (list.isEmpty()) {
                silent()
            } else {
                silentRounds = 0
                cb.onFinalResults(list)
            }
        }

        override fun onError(error: Int) {
            listening = false
            cb.onLevel(0f)
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> silent()

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    busyErrors++
                    recreate()
                    if (busyErrors > 3) {
                        giveUp("आवाज़ पहचान व्यस्त है। थोड़ी देर बाद फिर कोशिश करो", false)
                    } else {
                        schedule(1000L * busyErrors)
                    }
                }

                12, 13 -> { // भाषा समर्थित/उपलब्ध नहीं
                    langErrors++
                    useDetection = false
                    langIndex++
                    if (langErrors > 4) {
                        giveUp("यह भाषा इस फ़ोन पर उपलब्ध नहीं है", false)
                    } else {
                        recreate()
                        schedule(500)
                    }
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    clientErrors++
                    recreate()
                    if (clientErrors > 3) giveUp("आवाज़ पहचान में दिक्कत आई", false) else schedule(800)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    giveUp("माइक की अनुमति चाहिए", true)

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    giveUp("आवाज़ पहचान के लिए इंटरनेट चाहिए", false)

                else -> giveUp("आवाज़ पहचान नहीं चली (कोड $error)", false)
            }
        }
    }
}
