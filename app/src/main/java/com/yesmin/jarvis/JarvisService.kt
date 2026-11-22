package com.yesmin.jarvis

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import java.util.Locale

/**
 * बैकग्राउंड में "Hey Jarvis" सुनने वाली फ़ोरग्राउंड सर्विस (माइक टाइप)।
 * सीमाएँ: फ़ोन पूरी तरह बंद हो तो कुछ नहीं सुनेगी। Android इसे बैटरी बचाने के लिए
 * रोक सकता है। बैकग्राउंड से ऐप खोलने/कॉल के लिए "दूसरे ऐप के ऊपर दिखाओ" की अनुमति चाहिए।
 */
class JarvisService : Service(), VoiceEngine.Callbacks, Assistant.Ui, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.yesmin.jarvis.STOP"
        private const val CHANNEL = "jarvis_listen"
        private const val NOTIF_ID = 77

        @Volatile
        var instance: JarvisService? = null
            private set

        val running: Boolean get() = instance != null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, JarvisService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var voice: VoiceEngine
    private lateinit var assistant: Assistant
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var wm: WindowManager? = null
    private var hudView: HudView? = null
    private val hideRunnable = Runnable { hideHud() }

    private var paused = false        // ऐप सामने है, इसलिए सर्विस माइक छोड़ दे
    private var busy = false          // सोच या बोल रही है
    private var awake = false         // वेक वर्ड सुन लिया, अब अगला वाक्य कमांड है
    private var awakeUntil = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) onScreenOn()
        }
    }

    // ---------- जीवन-चक्र ----------

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android ने माइक सर्विस की इजाज़त नहीं दी (अनुमति नहीं या ऐप बैकग्राउंड में)
            Prefs.setBgListen(this, false)
            stopSelf()
            return
        }
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        assistant = Assistant(this, this)
        voice = VoiceEngine(this, this)
        voice.maxSilentRounds = Int.MAX_VALUE

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        voice.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setBgListen(this, false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // रजिस्टर ही नहीं हुआ था
        }
        if (::voice.isInitialized) voice.destroy()
        if (::tts.isInitialized) tts.shutdown()
        hideHud()
        super.onDestroy()
    }

    /** ऐप सामने आए तो माइक छोड़ दो */
    fun pauseListening() {
        paused = true
        voice.stop()
        hideHud()
    }

    /** ऐप पीछे जाए तो फिर सुनना शुरू */
    fun resumeListening() {
        paused = false
        if (!busy) voice.restart(800)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis सुन रहा है", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis सुन रहा है")
            .setContentText("\"Hey Jarvis\" बोलकर कमांड दो")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "बंद करो", stop)
            .setOngoing(true)
            .build()
    }

    // ---------- आवाज़ (VoiceEngine.Callbacks) ----------

    override fun onListening() {
        if (awake && System.currentTimeMillis() > awakeUntil) {
            awake = false
            scheduleHide(300)
        }
        if (awake) showHud(HudView.State.LISTENING)
    }

    override fun onLevel(level: Float) {
        hudView?.level = level
    }

    override fun onPartial(text: String) {}

    override fun onFinalResults(candidates: List<String>) {
        voice.stop()
        val now = System.currentTimeMillis()
        val commandMode = awake && now <= awakeUntil

        if (commandMode) {
            awake = false
            val text = WakeWord.strip(candidates.first())
            if (text.isBlank()) {
                voice.restart(400)
                return
            }
            runCommand(text)
            return
        }

        val withWake = candidates.firstOrNull { WakeWord.contains(it) }
        if (withWake == null) {
            voice.restart(300) // वेक वर्ड नहीं था, चुपचाप आगे सुनो
            return
        }
        val rest = WakeWord.strip(withWake)
        if (rest.isBlank()) {
            // सिर्फ़ "Jarvis" बोला: जवाब दो और अगला वाक्य कमांड मानो
            awake = true
            awakeUntil = now + 12_000
            busy = true
            showHud(HudView.State.SPEAKING)
            speak("हाँ सर?")
            return
        }
        runCommand(rest)
    }

    override fun onGaveUp(reason: String, fatal: Boolean) {
        if (fatal) {
            Prefs.setBgListen(this, false)
            stopSelf()
        } else if (!paused) {
            // इंटरनेट वगैरह की अस्थायी दिक्कत: थोड़ी देर बाद फिर कोशिश
            handler.postDelayed({ if (!paused && !busy) voice.restart(0) }, 15_000)
        }
    }

    // ---------- कमांड (Assistant.Ui) ----------

    private fun isLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    private fun runCommand(text: String) {
        if (isLocked()) {
            busy = true
            speak("पहले फ़ोन अनलॉक करो")
            return
        }
        busy = true
        showHud(HudView.State.THINKING)
        assistant.process(text)
    }

    override fun onThinking() {
        showHud(HudView.State.THINKING)
    }

    override fun onReply(msg: String) {
        busy = true
        showHud(HudView.State.SPEAKING)
        if (assistant.hasPending()) {
            // "कॉल करूँ?" जैसे सवाल का जवाब बिना वेक वर्ड के लो
            awake = true
            awakeUntil = System.currentTimeMillis() + 15_000
        }
        speak(msg)
    }

    override fun onNeedContacts(pending: String) {
        onReply("नाम से कॉल के लिए Jarvis ऐप खोलकर कॉन्टैक्ट की अनुमति दो")
    }

    // ---------- बोलना ----------

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.setLanguage(Locale("hi", "IN"))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post { afterSpeak() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post { afterSpeak() }
                }
            })
        }
    }

    private fun speak(msg: String) {
        voice.stop()
        if (ttsReady) {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "svc")
        } else {
            handler.postDelayed({ afterSpeak() }, 600)
        }
    }

    private fun afterSpeak() {
        busy = false
        if (!awake) scheduleHide(2500)
        if (!paused) voice.restart(500)
    }

    // ---------- स्क्रीन ऑन ग्रीटिंग ----------

    private fun onScreenOn() {
        if (!Prefs.greeting(this) || busy || paused) return
        val now = System.currentTimeMillis()
        val gap = Prefs.greetGapMin(this) * 60_000L
        if (now - Prefs.lastGreet(this) < gap) return // बार-बार नहीं बोलना
        Prefs.setLastGreet(this, now)
        busy = true
        showHud(HudView.State.SPEAKING)
        speak("Yes Sir, आप कैसे हैं?")
    }

    // ---------- तैरता HUD ----------

    private fun showHud(state: HudView.State) {
        if (!Prefs.floatingHud(this) || !Prefs.canOverlay(this)) return
        handler.removeCallbacks(hideRunnable)
        if (hudView == null) {
            val d = resources.displayMetrics.density
            val size = (150 * d).toInt()
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (60 * d).toInt()
            val v = HudView(this)
            try {
                wm?.addView(v, lp)
                hudView = v
            } catch (e: Exception) {
                return
            }
        }
        hudView?.state = state
    }

    private fun hideHud() {
        val v = hudView ?: return
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
            // पहले ही हट चुका
        }
        hudView = null
    }

    private fun scheduleHide(ms: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, ms)
    }
}
