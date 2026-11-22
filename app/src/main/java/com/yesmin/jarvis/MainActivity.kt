package com.yesmin.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var router: CommandRouter
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private lateinit var hud: HudView
    private lateinit var statusText: TextView
    private lateinit var youText: TextView
    private lateinit var jarvisText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private val history = mutableListOf<Pair<String, String>>()
    private var pendingText: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busy = false          // Jarvis सोच या बोल रहा है
    private var autoListen = true     // अपने-आप दोबारा सुनना
    private var resumed = false
    private var silentRounds = 0

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val cyan = Color.parseColor("#00E5FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = CommandRouter(this)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#02060D"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            TextView(this).apply {
                text = "J.A.R.V.I.S"
                textSize = 18f
                letterSpacing = 0.25f
                setTextColor(cyan)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(TextView(this).apply {
            text = "API KEY"
            textSize = 13f
            setTextColor(cyan)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showKeyDialog() }
        })

        hud = HudView(this)
        hud.setOnClickListener { toggleListening() }

        statusText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(0, dp(6), 0, dp(6))
        }

        youText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        jarvisText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(youText)
            addView(jarvisText)
        }
        scroll = ScrollView(this).apply { addView(textBox) }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "या यहाँ लिखो…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false
            }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(Button(this).apply {
            text = "➤"
            setOnClickListener { sendFromInput() }
        })
        bottom.addView(Button(this).apply {
            text = "🎤"
            setOnClickListener { toggleListening() }
        })

        root.addView(top)
        root.addView(hud, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f))
        root.addView(statusText)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.8f))
        root.addView(bottom)
        setContentView(root)

        val wanted = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 300)

        jarvisText.text = "नमस्ते! बस बोलो, जैसे \"व्हाट्सएप खोलो\", \"सुबह 6 बजे अलार्म लगाओ\" या \"कॉल करो…\""
        setStatus("तैयार")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.setLanguage(Locale("hi", "IN"))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { afterSpeak() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { afterSpeak() }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        busy = false
        if (autoListen && hasMic()) handler.postDelayed({ startListening() }, 400)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        listening = false
        if (ttsReady) tts.stop()
        hud.state = HudView.State.IDLE
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(s: String) { statusText.text = s }

    // ---------- सुनना ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            hud.level = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) youText.text = "तुम: $p…"
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                scheduleRelisten()
                return
            }
            silentRounds = 0
            handleText(text)
        }

        override fun onError(error: Int) {
            listening = false
            hud.level = 0f
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRelisten()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    autoListen = false
                    hud.state = HudView.State.IDLE
                    setStatus("माइक की अनुमति चाहिए")
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
                }
                else -> {
                    autoListen = false
                    hud.state = HudView.State.IDLE
                    setStatus("आवाज़ पहचान नहीं चली (इंटरनेट देखो)। 🎤 दबाओ")
                }
            }
        }
    }

    private fun startListening() {
        if (listening || busy || !resumed) return
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("इस फ़ोन में आवाज़ पहचान नहीं मिली। नीचे लिखकर बोलो")
            autoListen = false
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        hud.state = HudView.State.LISTENING
        setStatus("सुन रहा हूँ…")
        recognizer?.startListening(i)
    }

    /** कुछ सुनाई न दे तो थोड़ी देर बाद फिर सुनो, पर बैटरी बचाने को 6 बार के बाद रुक जाओ */
    private fun scheduleRelisten() {
        silentRounds++
        if (silentRounds > 6) {
            autoListen = false
            hud.state = HudView.State.IDLE
            setStatus("आराम कर रहा हूँ। जगाने के लिए बीच के गोले को दबाओ")
            return
        }
        handler.postDelayed({
            if (autoListen && resumed && !listening && !busy) startListening()
        }, 500)
    }

    private fun toggleListening() {
        if (listening || (autoListen && !busy)) {
            autoListen = false
            recognizer?.cancel()
            listening = false
            hud.state = HudView.State.IDLE
            setStatus("रुक गया। गोले को दबाओ")
        } else {
            autoListen = true
            silentRounds = 0
            startListening()
        }
    }

    // ---------- समझना और करना ----------

    private fun showKeyDialog() {
        val et = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("key", et.text.toString().trim()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            val t = pendingText
            pendingText = null
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && t != null) {
                reply(execute(t) ?: "यह कमांड समझ नहीं आया")
            } else {
                reply("अनुमति के बिना नाम से कॉल नहीं हो सकता। नंबर बोलकर कॉल कर सकते हो।")
            }
        } else if (requestCode == 300 || requestCode == 301) {
            if (hasMic() && resumed) {
                autoListen = true
                handler.postDelayed({ startListening() }, 300)
            }
        }
    }

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        input.setText("")
        handleText(t)
    }

    /** सादा कमांड हो तो router.handle, Gemini का JSON एक्शन हो तो router.runAction */
    private fun execute(text: String): String? =
        if (text.trimStart().startsWith("{")) {
            try {
                router.runAction(JSONObject(text))
            } catch (e: Exception) {
                "यह काम समझ नहीं आया"
            }
        } else {
            router.handle(text)
        }

    /** अनुमति चाहिए तो माँगता है, वरना जवाब दिखाता है */
    private fun deliver(res: String, executable: String) {
        if (res == CommandRouter.NEED_CONTACTS) {
            pendingText = executable
            reply("नाम से कॉल के लिए कॉन्टैक्ट की अनुमति दो।")
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 200)
        } else {
            reply(res)
        }
    }

    private fun handleText(text: String) {
        if (text.isBlank()) return
        recognizer?.cancel()
        listening = false
        busy = true
        youText.text = "तुम: $text"
        jarvisText.text = ""
        val local = router.handle(text)
        if (local != null) {
            deliver(local, text)
            return
        }
        askGemini(text)
    }

    /** Gemini का जवाब JSON एक्शन है या नहीं */
    private fun asActionJson(ans: String): String? {
        val t = ans.trim().removePrefix("```json").removePrefix("```").trimStart()
        if (!t.startsWith("{")) return null
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (b <= a) return null
        val cand = t.substring(a, b + 1)
        return try {
            JSONObject(cand)
            cand
        } catch (e: Exception) {
            null
        }
    }

    private fun askGemini(text: String) {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isEmpty()) {
            reply("पहले ऊपर 'API KEY' दबाकर Gemini की free key डालो।")
            return
        }
        hud.state = HudView.State.THINKING
        setStatus("सोच रहा हूँ…")
        Thread {
            try {
                val ans = GeminiClient.ask(key, history.takeLast(10), text)
                val action = asActionJson(ans)
                runOnUiThread {
                    if (action != null) {
                        deliver(execute(action) ?: "यह काम समझ नहीं आया", action)
                    } else {
                        history.add("user" to text)
                        history.add("model" to ans)
                        reply(ans)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { reply("गड़बड़ी: ${e.message}") }
            }
        }.start()
    }

    // ---------- बोलना ----------

    private fun reply(msg: String) {
        busy = true
        jarvisText.text = msg
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        hud.state = HudView.State.SPEAKING
        setStatus("बोल रहा हूँ")
        if (ttsReady) {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "reply")
        } else {
            handler.postDelayed({ afterSpeak() }, 600)
        }
    }

    private fun afterSpeak() {
        busy = false
        hud.state = HudView.State.IDLE
        setStatus("तैयार")
        if (autoListen && resumed) handler.postDelayed({ startListening() }, 300)
    }
}
