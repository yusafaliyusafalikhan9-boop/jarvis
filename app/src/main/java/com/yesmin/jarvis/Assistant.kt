package com.yesmin.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** ऐप और बैकग्राउंड सर्विस दोनों का साझा दिमाग: पहले लोकल कमांड, फिर Gemini */
class Assistant(private val ctx: Context) {

    private val router = CommandRouter(ctx)
    private val history = mutableListOf<Pair<String, String>>()
    private val main = Handler(Looper.getMainLooper())

    /** सादा कमांड हो तो router.handle, Gemini का JSON एक्शन हो तो router.runAction */
    fun execute(executable: String): String? =
        if (executable.trimStart().startsWith("{")) {
            try {
                router.runAction(JSONObject(executable))
            } catch (e: Exception) {
                "यह काम समझ नहीं आया"
            }
        } else {
            router.handle(executable)
        }

    /** onReply(जवाब, दोबारा चलाने लायक कमांड) हमेशा main thread पर बुलाया जाता है */
    fun process(text: String, onReply: (String, String) -> Unit) {
        val local = router.handle(text)
        if (local != null) {
            onReply(local, text)
            return
        }
        val key = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            .getString("key", "").orEmpty()
        if (key.isEmpty()) {
            onReply("पहले ऐप के KEY बटन से Gemini की free key डालो।", text)
            return
        }
        val snapshot = history.takeLast(10)
        Thread {
            try {
                val ans = GeminiClient.ask(key, snapshot, text)
                val action = actionJson(ans)
                main.post {
                    if (action != null) {
                        onReply(execute(action) ?: "यह काम समझ नहीं आया", action)
                    } else {
                        history.add("user" to text)
                        history.add("model" to ans)
                        onReply(ans, text)
                    }
                }
            } catch (e: Exception) {
                main.post { onReply("गड़बड़ी: ${e.message}", text) }
            }
        }.start()
    }

    /** Gemini का जवाब JSON एक्शन है या नहीं */
    private fun actionJson(ans: String): String? {
        val t = ans.trim().removePrefix("```json").removePrefix("```").trimStart()
        if (!t.startsWith("{")) return null
        val b = t.lastIndexOf('}')
        if (b <= 0) return null
        val cand = t.substring(0, b + 1)
        return try {
            JSONObject(cand)
            cand
        } catch (e: Exception) {
            null
        }
    }
}
