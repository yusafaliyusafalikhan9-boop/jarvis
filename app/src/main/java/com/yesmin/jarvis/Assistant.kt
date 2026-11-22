package com.yesmin.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale

/**
 * Jarvis का दिमाग: कमांड समझना (पहले लोकल, फिर Gemini), कॉल की पुष्टि सँभालना।
 * ऐप (MainActivity) और बैकग्राउंड सर्विस (JarvisService) दोनों इसे इस्तेमाल करते हैं।
 */
class Assistant(private val ctx: Context, private val ui: Ui) {

    interface Ui {
        fun onThinking()
        fun onReply(msg: String)
        fun onNeedContacts(pending: String)
    }

    private val router = CommandRouter(ctx)
    private val history = mutableListOf<Pair<String, String>>()
    private val main = Handler(Looper.getMainLooper())

    /** (नाम, नंबर) जिस पर कॉल से पहले "हाँ" का इंतज़ार है */
    private var pendingCall: Pair<String, String>? = null

    private val yesWords = setOf(
        "yes", "yeah", "haan", "han", "ha", "haa", "ok", "okay", "kar", "karo", "kardo", "lagao",
        "हाँ", "हां", "हा", "जी", "ठीक", "ओके", "करो", "कर", "लगाओ"
    )
    private val noWords = setOf(
        "no", "nahi", "nahin", "na", "cancel", "stop", "mat",
        "नहीं", "नही", "मत", "ना", "रद्द", "रुको"
    )

    fun hasPending(): Boolean = pendingCall != null

    /** एक बोला/लिखा हुआ कमांड (वेक वर्ड हटाया हुआ) */
    fun process(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        val pc = pendingCall
        if (pc != null) {
            pendingCall = null
            val tokens = t.lowercase(Locale.ROOT)
                .split(Regex("[\\s,.!?।]+"))
                .filter { it.isNotEmpty() }
            if (tokens.any { it in noWords }) {
                ui.onReply("ठीक है, कॉल रद्द कर दी")
                return
            }
            if (tokens.any { it in yesWords }) {
                ui.onReply(router.callNumber(pc.second, pc.first))
                return
            }
            // कुछ और बोला: पुरानी कॉल छोड़कर नया कमांड समझो
        }

        val local = router.handle(t)
        if (local != null) {
            deliver(local, t)
            return
        }
        askGemini(t)
    }

    /** कॉन्टैक्ट की अनुमति मिलने के बाद उसी कमांड को दोबारा चलाने के लिए */
    fun resume(executable: String) {
        val r = execute(executable)
        deliver(r ?: "यह कमांड समझ नहीं आया", executable)
    }

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

    private fun deliver(res: String, executable: String) {
        if (res == CommandRouter.NEED_CONTACTS) {
            ui.onNeedContacts(executable)
        } else if (res.startsWith(CommandRouter.CONFIRM_PREFIX)) {
            val parts = res.removePrefix(CommandRouter.CONFIRM_PREFIX).split("\t", limit = 2)
            val who = parts[0]
            val number = if (parts.size > 1) parts[1] else ""
            pendingCall = who to number
            ui.onReply("$who को कॉल करूँ? हाँ या नहीं बोलो")
        } else {
            ui.onReply(res)
        }
    }

    /** Gemini का जवाब JSON एक्शन है तो वह JSON, वरना null */
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
        val key = Prefs.apiKey(ctx)
        if (key.isEmpty()) {
            ui.onReply("पहले ऊपर 'API KEY' दबाकर Gemini की free key डालो।")
            return
        }
        ui.onThinking()
        val past = history.takeLast(10)
        Thread {
            var ans: String? = null
            var err: String? = null
            try {
                ans = GeminiClient.ask(key, past, text)
            } catch (e: GeminiException) {
                err = e.userMessage
            } catch (e: Exception) {
                err = "कुछ गड़बड़ हुई। फिर कोशिश करो"
            }
            val answer = ans
            val error = err
            main.post {
                if (error != null) {
                    ui.onReply(error)
                } else {
                    handleAnswer(text, answer.orEmpty())
                }
            }
        }.start()
    }

    private fun handleAnswer(question: String, answer: String) {
        val action = asActionJson(answer)
        if (action != null) {
            val r = execute(action)
            deliver(r ?: "यह काम समझ नहीं आया", action)
        } else if (answer.trim().startsWith("{")) {
            ui.onReply("मैं यह काम ठीक से समझ नहीं पाया। दोबारा बोलो")
        } else {
            history.add("user" to question)
            history.add("model" to answer)
            ui.onReply(answer)
        }
    }
}
