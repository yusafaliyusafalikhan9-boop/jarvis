package com.yesmin.jarvis

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** उपयोगकर्ता को दिखाने लायक संदेश वाली गड़बड़ी */
class GeminiException(val userMessage: String) : Exception(userMessage)

object GeminiClient {
    // पहला मॉडल 404 दे तो अगला आज़माता है। Google नाम बदले तो यह सूची बदल दो
    private val MODELS = listOf("gemini-3.8-flash", "gemini-flash-latest")

    private val SYSTEM = """
You are Jarvis, a warm, smart personal assistant living on the user's Android phone.
Reply in the language the user speaks (Hindi, English or Hinglish, written the way the user writes). Keep replies short and clear, at most 4 sentences unless the user asks for more.

When the user asks you to DO something on the phone, reply with ONLY one JSON object and nothing else (no code fences, no extra words). Formats:
{"action":"open_app","name":"whatsapp"}
{"action":"call","name":"contact name"}   or   {"action":"call","number":"digits only"}
{"action":"youtube","query":"search words"}
{"action":"alarm","hour":6,"minute":30}   (24-hour clock)
{"action":"timer","minutes":10}
{"action":"search","query":"search words"}
For everything else, answer normally in plain text.

When the user asks for advice, or what they should or should not do, give honest, practical suggestions with a one-line reason for each, and say clearly when something is only your opinion. For health, money, legal or safety topics, give general guidance and suggest a qualified person for serious decisions. Never encourage anything harmful or illegal.
""".trimIndent()

    private fun msg(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private class Reply(val code: Int, val text: String)

    private fun call(model: String, apiKey: String, body: JSONObject): Reply {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): String {
        try {
            val cands = JSONObject(text).optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                throw GeminiException("Gemini ने इस बात का जवाब नहीं दिया। दूसरे तरीके से पूछो")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw GeminiException("खाली जवाब आया। फिर से पूछो")
            return out
        } catch (e: JSONException) {
            throw GeminiException("Gemini का जवाब पढ़ नहीं पाया। फिर से कोशिश करो")
        }
    }

    /** जवाब लौटाता है, या GeminiException (हिंदी संदेश के साथ) फेंकता है */
    fun ask(apiKey: String, history: List<Pair<String, String>>, prompt: String): String {
        if (apiKey.isBlank()) throw GeminiException("पहले API KEY डालो")

        val contents = JSONArray()
        for ((role, text) in history) contents.put(msg(role, text))
        contents.put(msg("user", prompt))
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            )
            .put("contents", contents)

        var lastModelNote = ""
        modelLoop@ for (model in MODELS) {
            var attempt = 0
            while (true) {
                val reply: Reply = try {
                    call(model, apiKey, body)
                } catch (e: IOException) {
                    if (attempt < 2) {
                        attempt++
                        sleepBackoff(attempt)
                        continue
                    }
                    throw GeminiException("इंटरनेट की दिक्कत है। नेट चालू करके फिर कोशिश करो")
                }

                val code = reply.code
                if (code in 200..299) return parse(reply.text)
                if (code == 404) {
                    lastModelNote = model
                    continue@modelLoop
                }
                val keyProblem = code == 401 || code == 403 ||
                    (code == 400 && (reply.text.contains("API key", ignoreCase = true) ||
                        reply.text.contains("API_KEY", ignoreCase = true)))
                if (keyProblem) {
                    throw GeminiException("API key सही नहीं लगती या बंद है। ऊपर 'API KEY' से नई key डालो")
                }
                if (code == 429 || code in 500..599) {
                    if (attempt < 2) {
                        attempt++
                        sleepBackoff(attempt)
                        continue
                    }
                    if (code == 429) {
                        throw GeminiException("Gemini पर अभी बहुत ज़्यादा रिक्वेस्ट हैं। थोड़ी देर बाद फिर कोशिश करो")
                    }
                    throw GeminiException("Gemini का सर्वर अभी व्यस्त है। थोड़ी देर बाद फिर कोशिश करो")
                }
                throw GeminiException("Gemini से गड़बड़ी आई (HTTP $code)")
            }
        }
        throw GeminiException("कोई Gemini मॉडल नहीं मिला ($lastModelNote)। ऐप अपडेट करना पड़ सकता है")
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(1200L * attempt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
