package com.yesmin.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    fun ask(apiKey: String, history: List<Pair<String, String>>, prompt: String): String {
        val contents = JSONArray()
        for ((role, text) in history) contents.put(msg(role, text))
        contents.put(msg("user", prompt))

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            )
            .put("contents", contents)

        var lastError = "कोई मॉडल नहीं मिला"
        for (model in MODELS) {
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
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code == 404) {
                    lastError = "HTTP 404 ($model): ${text.take(160)}"
                    continue
                }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(200)}")

                val parts = JSONObject(text)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                return sb.toString().trim().ifEmpty { "कोई जवाब नहीं मिला" }
            } finally {
                conn.disconnect()
            }
        }
        throw RuntimeException(lastError)
    }
}
