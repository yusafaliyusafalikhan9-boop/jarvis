package com.yesmin.jarvis

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** लोकल कमांड: बिना इंटरनेट और बिना API के चलते हैं। कमांड न मिले तो null लौटाता है। */
class CommandRouter(private val ctx: Context) {

    private val openTriggers = setOf(
        "open", "kholo", "khol", "launch", "start",
        "खोलो", "खोल", "खोलें", "ओपन", "चालू"
    )

    private val stopTokens = setOf(
        "open", "kholo", "khol", "launch", "start", "do", "kar", "karo", "kardo",
        "please", "plz", "app", "the", "my", "ko", "mera", "meri", "zara",
        "खोलो", "खोल", "खोलें", "ओपन", "चालू", "दो", "करो", "कर", "कीजिए",
        "ऐप", "एप", "एप्प", "को", "मेरा", "मेरी", "प्लीज", "जरा"
    )

    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "वाट्सएप" to "whatsapp", "वॉट्सएप" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "insta" to "instagram",
        "यूट्यूब" to "youtube", "यूटयूब" to "youtube",
        "क्रोम" to "chrome", "कैमरा" to "camera",
        "सेटिंग" to "settings", "सेटिंग्स" to "settings", "सेटिंग्ज" to "settings",
        "गैलरी" to "gallery", "फोन" to "phone", "मैप्स" to "maps", "मैप" to "maps",
        "जीमेल" to "gmail", "स्नैपचैट" to "snapchat", "फेसबुक" to "facebook",
        "टेलीग्राम" to "telegram", "स्पॉटिफाई" to "spotify",
        "कैलकुलेटर" to "calculator", "घड़ी" to "clock", "म्यूजिक" to "music"
    )

    private val ytWords = setOf("youtube", "यूट्यूब", "यूटयूब")
    private val waWords = setOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप")
    private val msgWords = setOf("message", "msg", "मैसेज", "संदेश")
    private val callWords = setOf("call", "कॉल", "dial", "लगाओ", "lagao")
    private val searchWords = setOf(
        "search", "सर्च", "chalao", "चलाओ", "play", "बजाओ", "bajao",
        "dhundo", "ढूंढो", "खोजो", "khojo"
    )
    private val fillers = setOf("par", "पर", "pe", "पे", "me", "में", "mein", "on", "in")
    private val numRegex = Regex("^\\+?\\d{7,15}$")

    fun handle(raw: String): String? {
        val tokens = raw.lowercase(Locale.ROOT)
            .replace(Regex("[?!,.।]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // समय
        if (tokens.size <= 4 && tokens.any { it in setOf("time", "samay", "समय", "टाइम") }) {
            return "अभी समय है " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        }

        // YouTube पर खोजना/चलाना
        if (tokens.any { it in ytWords } && tokens.any { it in searchWords }) {
            val q = tokens.filter {
                it !in ytWords && it !in searchWords && it !in stopTokens && it !in fillers
            }.joinToString(" ")
            if (q.isNotEmpty()) {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                return startSafely(Intent(Intent.ACTION_VIEW, uri), "YouTube पर '$q' खोल रहा हूँ")
            }
        }

        val num = tokens.firstOrNull { numRegex.matches(it) }

        // WhatsApp मैसेज: "whatsapp message 919876543210 hello kaise ho"
        if (num != null && tokens.any { it in waWords } && tokens.any { it in msgWords }) {
            val text = tokens.drop(tokens.indexOf(num) + 1).joinToString(" ")
            val digits = num.removePrefix("+")
            val link = "https://wa.me/$digits" + if (text.isNotEmpty()) "?text=" + Uri.encode(text) else ""
            return startSafely(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)),
                "WhatsApp चैट खोल रहा हूँ। भेजने के लिए send दबाओ"
            )
        }

        // कॉल: "call 9876543210"
        if (num != null && tokens.any { it in callWords }) {
            return startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")), "डायलर खोल रहा हूँ: $num")
        }

        // ऐप खोलना
        if (tokens.any { it in openTriggers }) {
            val name = tokens.filter { it !in stopTokens }.joinToString(" ")
            if (name.isEmpty()) return "कौन सा ऐप खोलूँ?"
            return openApp(aliases[name] ?: name)
        }

        // वेब सर्च
        if (tokens[0] in setOf("search", "सर्च", "google", "गूगल") && tokens.size > 1) {
            val q = tokens.drop(1).joinToString(" ")
            return startSafely(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                "'$q' सर्च कर रहा हूँ"
            )
        }

        return null
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)

        fun label(r: ResolveInfo) = r.loadLabel(pm).toString().lowercase(Locale.ROOT)

        val hit = apps.firstOrNull { label(it) == name }
            ?: apps.firstOrNull { label(it).contains(name) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase(Locale.ROOT).contains(name) }
            ?: return "'$name' नाम का ऐप नहीं मिला"

        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "${hit.loadLabel(pm)} को खोल नहीं पाया"
        return startSafely(intent, "${hit.loadLabel(pm)} खोल रहा हूँ")
    }

    private fun startSafely(intent: Intent, okMessage: String): String =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okMessage
        } catch (e: Exception) {
            "यह काम नहीं हो पाया: ${e.message}"
        }
}
