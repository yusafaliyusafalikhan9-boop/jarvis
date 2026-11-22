package com.yesmin.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(),
    TextToSpeech.OnInitListener, VoiceEngine.Callbacks, Assistant.Ui {

    private lateinit var assistant: Assistant
    private lateinit var voice: VoiceEngine
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private lateinit var hud: HudView
    private lateinit var statusText: TextView
    private lateinit var youText: TextView
    private lateinit var jarvisText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var settingsPanel: LinearLayout
    private lateinit var gapButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingText: String? = null
    private var busy = false          // Jarvis सोच या बोल रहा है
    private var autoListen = true     // बातचीत में अपने-आप दोबारा सुनना
    private var resumed = false

    private val cyan = Color.parseColor("#00E5FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- जीवन-चक्र ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = Assistant(this, this)
        voice = VoiceEngine(this, this)
        tts = TextToSpeech(this, this)
        buildUi()

        val wanted = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 300)

        jarvisText.text = "नमस्ते! बस बोलो, जैसे \"Open WhatsApp\", \"यूट्यूब खोलो\", " +
            "\"सुबह 6 बजे अलार्म लगाओ\" या \"राहुल को कॉल करो\""
        setStatus("तैयार")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        busy = false
        JarvisService.instance?.pauseListening() // माइक एक ही के पास रहे
        if (autoListen && hasMic()) voice.start()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacksAndMessages(null)
        voice.stop()
        if (ttsReady) tts.stop()
        hud.state = HudView.State.IDLE
        JarvisService.instance?.resumeListening()
        super.onPause()
    }

    override fun onDestroy() {
        voice.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(s: String) {
        statusText.text = s
    }

    // ---------- स्क्रीन ----------

    private fun buildUi() {
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
        top.addView(smallButton("⚙") {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        top.addView(smallButton("API KEY") { showKeyDialog() })

        settingsPanel = buildSettingsPanel()

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
        root.addView(settingsPanel)
        root.addView(hud, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f))
        root.addView(statusText)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.8f))
        root.addView(bottom)
        setContentView(root)
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(cyan)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun styledSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = on
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        lateinit var bgSwitch: Switch
        bgSwitch = styledSwitch(
            "बैकग्राउंड में \"Hey Jarvis\" सुनो",
            Prefs.bgListen(this) && JarvisService.running
        ) { on ->
            if (on) {
                if (!hasMic()) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
                    bgSwitch.isChecked = false
                } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 302)
                    }
                    Prefs.setBgListen(this, true)
                    try {
                        JarvisService.start(this)
                        setStatus("बैकग्राउंड सुनना चालू। ऐप से बाहर जाकर \"Hey Jarvis\" बोलो")
                    } catch (e: Exception) {
                        Prefs.setBgListen(this, false)
                        bgSwitch.isChecked = false
                        setStatus("सर्विस शुरू नहीं हो पाई: ${e.message}")
                    }
                }
            } else {
                Prefs.setBgListen(this, false)
                JarvisService.stop(this)
            }
        }
        panel.addView(bgSwitch)

        panel.addView(styledSwitch("स्क्रीन ऑन होने पर ग्रीटिंग", Prefs.greeting(this)) {
            Prefs.setGreeting(this, it)
        })
        panel.addView(styledSwitch("तैरता HUD दिखाओ", Prefs.floatingHud(this)) {
            Prefs.setFloatingHud(this, it)
        })

        gapButton = smallButton("") {
            val options = listOf(5, 10, 30, 60)
            val cur = options.indexOf(Prefs.greetGapMin(this))
            Prefs.setGreetGapMin(this, options[(cur + 1) % options.size])
            refreshGapLabel()
        }
        panel.addView(gapButton)
        refreshGapLabel()

        panel.addView(smallButton("दूसरे ऐप के ऊपर दिखाने की अनुमति (HUD/बैकग्राउंड के लिए)") {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        panel.addView(smallButton("बैटरी सेटिंग खोलो (Jarvis को रोके नहीं)") {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        return panel
    }

    private fun refreshGapLabel() {
        gapButton.text = "ग्रीटिंग अंतराल: ${Prefs.greetGapMin(this)} मिनट (बदलने को दबाओ)"
    }

    private fun showKeyDialog() {
        val et = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setApiKey(this, et.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- सुनना (VoiceEngine.Callbacks) ----------

    override fun onListening() {
        hud.state = HudView.State.LISTENING
        val lang = if (voice.currentLanguage().startsWith("hi")) "हिंदी" else "English"
        setStatus("सुन रहा हूँ… ($lang)")
    }

    override fun onLevel(level: Float) {
        hud.level = level
    }

    override fun onPartial(text: String) {
        youText.text = "तुम: $text…"
    }

    override fun onFinalResults(candidates: List<String>) {
        voice.stop()
        val text = WakeWord.strip(candidates.first()) // "Jarvis" कहा हो तो हटा दो
        if (text.isBlank()) {
            if (autoListen) voice.restart(300)
            return
        }
        startCommand(text)
    }

    override fun onGaveUp(reason: String, fatal: Boolean) {
        hud.state = HudView.State.IDLE
        autoListen = false
        setStatus(reason)
        if (fatal && !hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
    }

    private fun toggleListening() {
        if (autoListen) {
            autoListen = false
            voice.stop()
            hud.state = HudView.State.IDLE
            setStatus("रुक गया। गोले को दबाओ")
        } else {
            autoListen = true
            if (!busy) voice.start()
        }
    }

    // ---------- करना (Assistant.Ui) ----------

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        input.setText("")
        if (t.isNotEmpty()) {
            voice.stop()
            startCommand(t)
        }
    }

    private fun startCommand(text: String) {
        busy = true
        youText.text = "तुम: $text"
        jarvisText.text = ""
        assistant.process(text)
    }

    override fun onThinking() {
        hud.state = HudView.State.THINKING
        setStatus("सोच रहा हूँ…")
    }

    override fun onNeedContacts(pending: String) {
        pendingText = pending
        onReply("नाम से कॉल के लिए कॉन्टैक्ट की अनुमति दो।")
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 200)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            200 -> {
                val t = pendingText
                pendingText = null
                if (granted && t != null) {
                    assistant.resume(t)
                } else {
                    onReply("अनुमति के बिना नाम से कॉल नहीं हो सकता। नंबर बोलकर कॉल कर सकते हो।")
                }
            }
            300, 301 -> {
                if (hasMic() && resumed && autoListen && !busy) voice.start()
            }
        }
    }

    // ---------- बोलना ----------

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

    override fun onReply(msg: String) {
        busy = true
        jarvisText.text = msg
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        hud.state = HudView.State.SPEAKING
        setStatus("बोल रहा हूँ")
        voice.stop()
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
        if (autoListen && resumed) voice.restart(300)
    }
}
