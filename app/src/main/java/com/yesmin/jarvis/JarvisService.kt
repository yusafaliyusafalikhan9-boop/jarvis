package com.yesmin.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Locale

/** हमेशा सुनने वाली सर्विस: "जार्विस ..." बोलने पर बिना ऐप खोले काम करती है */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL = "jarvis_listen"
        @Volatile
        var running = false
    }

    private lateinit var assistant: Assistant
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restart = Runnable { startListening() }
    private var speaking = false
    private var stopped = false
    private var awaitingUntil = 0L
    private var errorCount = 0
    private var overlay: View? = null

    private val wakeStarts = listOf(
        "jarvi", "jarwi", "jarvs", "javis", "jervis", "जार्वि", "जारवि", "जरवि", "जर्वि", "जार्व", "जारव"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        assistant = Assistant(this)
        tts = TextToSpeech(this, this)
        val ch = NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        running = true
        stopped = false
        addOverlay()
        restartLater(500)
        return START_STICKY
    }

    private fun startAsForeground() {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "बंद करो", stopIntent
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis सुन रहा है")
            .setContentText("\"जार्विस\" बोलकर कमांड दो")
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    /** 1 पिक्सल की अदृश्य विंडो: बैकग्राउंड से ऐप/कॉल खोलने की रोक हटाने में मदद करती है */
    private fun addOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            // चलता रहे
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return
        tts.setLanguage(Locale("hi", "IN"))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                finishSpeaking()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishSpeaking()
            }
        })
    }

    private fun finishSpeaking() {
        handler.post {
            speaking = false
            restartLater(300)
        }
    }

    private fun restartLater(ms: Long) {
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (stopped) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf()
                return
            }
            errorCount++
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.destroy()
                recognizer = null
                restartLater(1500)
            } else {
                restartLater(if (errorCount > 5) 2000 else 400)
            }
        }

        override fun onResults(results: Bundle?) {
            errorCount = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isNotEmpty()) handleHeard(list)
            if (!speaking) restartLater(200)
        }
    }

    private fun startListening() {
        if (stopped || speaking) return
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stopSelf()
                return
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            restartLater(1500)
        }
    }

    private fun isWake(t: String): Boolean = wakeStarts.any { t.startsWith(it) }

    private fun handleHeard(list: List<String>) {
        val now = System.currentTimeMillis()
        for (heard in list) {
            val tokens = heard.lowercase(Locale.ROOT)
                .replace(Regex("[,.!?।\"']"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            val idx = tokens.indexOfFirst { isWake(it) }
            if (idx >= 0) {
                val cmd = tokens.drop(idx + 1).joinToString(" ")
                if (cmd.isBlank()) {
                    awaitingUntil = now + 10_000
                    say("जी, बोलिए")
                } else {
                    runCommand(cmd)
                }
                return
            }
        }
        if (now < awaitingUntil) {
            awaitingUntil = 0
            runCommand(list.first())
        }
    }

    private fun runCommand(cmd: String) {
        assistant.process(cmd) { reply, _ ->
            if (reply == CommandRouter.NEED_CONTACTS) {
                say("नाम से कॉल के लिए Jarvis ऐप खोलकर कॉन्टैक्ट की अनुमति दो")
            } else {
                say(reply)
            }
        }
    }

    private fun say(text: String) {
        if (!ttsReady) {
            restartLater(300)
            return
        }
        speaking = true
        recognizer?.cancel()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onDestroy() {
        stopped = true
        running = false
        handler.removeCallbacks(restart)
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        overlay?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
                // ठीक है
            }
        }
        overlay = null
        super.onDestroy()
    }
}
