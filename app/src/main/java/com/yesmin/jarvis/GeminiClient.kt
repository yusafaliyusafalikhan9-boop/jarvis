package com.yesmin.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {
    // अगर 404 आए तो AI Studio में देखकर यह नाम बदल दो
    const val MODEL = "gemini-2.5-flash"

    private const val SYSTEM =
        "You are Jarvis, a warm and concise voice assistant. Reply in the same language " +
        "the user speaks (Hindi, English or Hinglish). Keep answers to at most 3 short " +
        "sentences unless the user asks for more."

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

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
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
}
