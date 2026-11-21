package com.yesmin.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var router: CommandRouter
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val history = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = CommandRouter(this)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#0B0F1A"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 22f
            setTextColor(Color.parseColor("#4FC3F7"))
        }
        top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "API Key"
            setOnClickListener { showKeyDialog() }
        })

        chat = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll = ScrollView(this).apply { addView(chat) }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "कमांड लिखो…"
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
            text = "भेजो"
            setOnClickListener { sendFromInput() }
        })
        bottom.addView(Button(this).apply {
            text = "🎤"
            setOnClickListener { startListening() }
        })

        root.addView(top)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)

        append("Jarvis: नमस्ते! 🎤 दबाकर बोलो, जैसे \"व्हाट्सएप खोलो\" या \"यूट्यूब पर गाने चलाओ\"।")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.setLanguage(Locale("hi", "IN"))
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

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

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए…")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 100)
        } catch (e: ActivityNotFoundException) {
            reply("इस फ़ोन में वॉइस इनपुट नहीं मिला। टाइप करके कमांड दो।")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) handleText(text)
        }
    }

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        input.setText("")
        handleText(t)
    }

    private fun handleText(text: String) {
        if (text.isBlank()) return
        append("तुम: $text")
        val local = router.handle(text)
        if (local != null) { reply(local); return }
        askGemini(text)
    }

    private fun askGemini(text: String) {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isEmpty()) {
            reply("पहले ऊपर 'API Key' बटन से Gemini की free key डालो।")
            return
        }
        append("Jarvis: सोच रहा हूँ…")
        Thread {
            try {
                val ans = GeminiClient.ask(key, history.takeLast(10), text)
                runOnUiThread {
                    history.add("user" to text)
                    history.add("model" to ans)
                    reply(ans)
                }
            } catch (e: Exception) {
                runOnUiThread { reply("गड़बड़ी: ${e.message}") }
            }
        }.start()
    }

    private fun reply(msg: String) {
        append("Jarvis: $msg")
        if (ttsReady) tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun append(s: String) {
        chat.append(s + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
