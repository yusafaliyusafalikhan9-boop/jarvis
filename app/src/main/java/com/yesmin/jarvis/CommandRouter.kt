package com.yesmin.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** लोकल कमांड: बिना इंटरनेट और बिना API के चलते हैं। कमांड न मिले तो null लौटाता है। */
class CommandRouter(private val ctx: Context) {

    companion object {
        /** MainActivity को बताता है कि कॉन्टैक्ट की अनुमति माँगनी है */
        const val NEED_CONTACTS = "__NEED_CONTACTS__"

        /** "__CONFIRM_CALL__:नाम\tनंबर" : कॉल से पहले हाँ/नहीं पूछना है */
        const val CONFIRM_PREFIX = "__CONFIRM_CALL__:"
    }

    private val phoneWords = setOf(
        "phone", "फोन", "फ़ोन", "number", "नंबर", "नम्बर", "mobile", "मोबाइल",
        "baat", "बात", "karao", "कराओ", "karwao", "करवाओ", "se", "से"
    )

    private val openTriggers = setOf(
        "open", "kholo", "khol", "launch", "start",
        "खोलो", "खोल", "खोलें", "ओपन", "चालू"
    )

    private val stopTokens = setOf(
        "open", "kholo", "khol", "launch", "start", "do", "kar", "karo", "kardo",
        "please", "plz", "app", "the", "my", "ko", "mera", "meri", "mere", "zara",
        "खोलो", "खोल", "खोलें", "ओपन", "चालू", "दो", "करो", "कर", "कीजिए",
        "ऐप", "एप", "एप्प", "को", "मेरा", "मेरी", "मेरे", "प्लीज", "जरा"
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
    private val callWords = setOf(
        "call", "कॉल", "काल", "कोल", "dial", "डायल", "लगाओ", "lagao", "मिलाओ", "milao"
    )
    private val searchWords = setOf(
        "search", "सर्च", "chalao", "चलाओ", "play", "बजाओ", "bajao",
        "dhundo", "ढूंढो", "खोजो", "khojo"
    )
    private val fillers = setOf("par", "पर", "pe", "पे", "me", "में", "mein", "on", "in")
    private val numRegex = Regex("^\\+?\\d{7,15}$")

    // अलार्म और टाइमर के शब्द
    private val alarmWords = setOf(
        "alarm", "alaram", "अलार्म", "अलारम",
        "utha", "uthana", "उठा", "उठाना", "jaga", "jagana", "जगा", "जगाना", "wake"
    )
    private val timerWords = setOf("timer", "टाइमर")
    private val minuteWords = setOf("minute", "minutes", "min", "मिनट", "मिनिट")
    private val hourWords = setOf("ghante", "ghanta", "घंटे", "घंटा", "hour", "hours")
    private val bajeWords = setOf("baje", "bje", "बजे", "बजकर")
    private val amWords = setOf("subah", "सुबह", "savere", "सवेरे", "morning")
    private val pmWords = setOf(
        "sham", "शाम", "raat", "रात", "dopahar", "दोपहर", "evening", "night", "afternoon"
    )
    private val nightWords = setOf("raat", "रात", "night")
    private val hindiNums = mapOf(
        "एक" to 1, "ek" to 1, "दो" to 2, "do" to 2, "तीन" to 3, "teen" to 3,
        "चार" to 4, "char" to 4, "पांच" to 5, "पाँच" to 5, "paanch" to 5, "panch" to 5,
        "छह" to 6, "छः" to 6, "chhe" to 6, "che" to 6, "सात" to 7, "saat" to 7,
        "आठ" to 8, "aath" to 8, "नौ" to 9, "nau" to 9, "दस" to 10, "das" to 10,
        "ग्यारह" to 11, "gyarah" to 11, "बारह" to 12, "barah" to 12
    )

    fun handle(raw: String): String? {
        val tokens = mergeDigitTokens(
            normalizeDigits(raw.lowercase(Locale.ROOT))
                .replace(Regex("[?!,.।]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
        )
        if (tokens.isEmpty()) return null

        // टाइमर: "10 मिनट का टाइमर", "5 minute baad utha dena"
        val n = tokens.firstOrNull { it.matches(Regex("^\\d{1,3}$")) }?.toInt()
        val hasUnit = tokens.any { it in minuteWords || it in hourWords }
        if (n != null && hasUnit && tokens.any { it in alarmWords || it in timerWords }) {
            val mins = if (tokens.any { it in hourWords }) n * 60 else n
            return setTimer(mins)
        }

        // अलार्म: "सुबह 6 बजे अलार्म लगाओ", "कल 7 बजे उठा देना"
        if (tokens.size <= 10 && tokens.any { it in alarmWords }) {
            val t = parseAlarmTime(tokens)
                ?: return "कितने बजे का अलार्म लगाऊँ? जैसे 'सुबह 6 बजे अलार्म लगाओ'"
            return setAlarm(t.first, t.second)
        }

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
            return callNumber(num, num)
        }

        // कॉल बोला पर नंबर नहीं: कॉन्टैक्ट के नाम से ढूँढो
        if (num == null && tokens.size <= 5 && tokens.any { it in callWords }) {
            val nameTokens = tokens.filter {
                it !in callWords && it !in stopTokens && it !in fillers && it !in phoneWords
            }
            if (nameTokens.isEmpty()) {
                return "किसे कॉल करूँ? नाम या नंबर के साथ बोलो, जैसे 'कॉल करो 9876543210'"
            }
            return callByName(nameTokens)
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

    /** Gemini (दिमाग) से आया JSON एक्शन चलाता है */
    fun runAction(a: JSONObject): String =
        when (a.optString("action")) {
            "open_app" -> {
                val nm = a.optString("name").lowercase(Locale.ROOT).trim()
                if (nm.isEmpty()) "कौन सा ऐप खोलूँ?" else openApp(aliases[nm] ?: nm)
            }
            "youtube" -> {
                val q = a.optString("query").trim()
                if (q.isEmpty()) {
                    startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")), "YouTube खोल रहा हूँ")
                } else {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                    startSafely(Intent(Intent.ACTION_VIEW, uri), "YouTube पर '$q' खोल रहा हूँ")
                }
            }
            "call" -> {
                val number = a.optString("number").replace(Regex("[^0-9+]"), "")
                if (number.length >= 7) {
                    CONFIRM_PREFIX + number + "\t" + number
                } else {
                    val nm = a.optString("name").lowercase(Locale.ROOT).trim()
                    if (nm.isEmpty()) "किसे कॉल करूँ?" else callByName(nm.split(Regex("\\s+")))
                }
            }
            "alarm" -> setAlarm(a.optInt("hour", -1), a.optInt("minute", 0))
            "timer" -> setTimer(a.optInt("minutes", 0))
            "search" -> {
                val q = a.optString("query").trim()
                if (q.isEmpty()) {
                    "क्या सर्च करूँ?"
                } else {
                    startSafely(
                        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                        "'$q' सर्च कर रहा हूँ"
                    )
                }
            }
            else -> "यह काम अभी मुझे नहीं आता"
        }

    /** अनुमति हो तो सीधे कॉल लगाता है, वरना डायलर में नंबर भरकर खोलता है */
    fun callNumber(number: String, who: String = number): String {
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(call)
                return "$who को कॉल लगा रहा हूँ"
            } catch (e: Exception) {
                // सीधा कॉल न हो सका, डायलर पर लौट जाओ
            }
        }
        return startSafely(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            "डायलर खोल रहा हूँ: $who। हरा बटन दबाओ"
        )
    }

    private fun cleanNumber(n: String) = n.replace(Regex("[^0-9+]"), "")

    /**
     * नाम से कॉल: पूरा नाम ठीक एक कॉन्टैक्ट से मिले तभी सीधे कॉल।
     * आधा-अधूरा मिलान हो तो पुष्टि माँगता है, कई मिलें तो नाम गिनाकर पूरा नाम पूछता है।
     */
    private fun callByName(nameTokens: List<String>): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return NEED_CONTACTS

        val spoken = nameTokens.joinToString(" ")
        val matches = findContacts(nameTokens)
        if (matches.isEmpty()) {
            return "कॉन्टैक्ट '$spoken' नहीं मिला। नाम वैसे ही बोलो या लिखो जैसे सेव है"
        }
        val exact = matches.filter { it.first.lowercase(Locale.ROOT) == spoken }
        if (exact.size == 1) return callNumber(cleanNumber(exact[0].second), exact[0].first)
        if (matches.size == 1) {
            return CONFIRM_PREFIX + matches[0].first + "\t" + cleanNumber(matches[0].second)
        }
        val names = matches.take(4).joinToString(", ") { it.first }
        return "कई कॉन्टैक्ट मिले: $names। पूरा नाम बताओ"
    }

    private fun setAlarm(hour: Int, minute: Int): String {
        if (hour !in 0..23 || minute !in 0..59) return "अलार्म का समय सही नहीं लगा। फिर से बोलो"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val label = String.format(Locale.US, "%d:%02d %s", h12, minute, if (hour < 12) "AM" else "PM")
        return startSafely(intent, "अलार्म सेट किया: $label। क्लॉक ऐप में एक बार देख लेना")
    }

    private fun setTimer(minutes: Int): String {
        if (minutes !in 1..1440) return "टाइमर 1 मिनट से 24 घंटे के बीच का बोलो"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return startSafely(intent, "$minutes मिनट का टाइमर शुरू किया")
    }

    private fun parseAlarmTime(tokens: List<String>): Pair<Int, Int>? {
        var hour = -1
        var minute = 0
        val colon = Regex("^(\\d{1,2}):(\\d{2})$")
        for (tok in tokens) {
            val m = colon.find(tok)
            if (m != null) {
                hour = m.groupValues[1].toInt()
                minute = m.groupValues[2].toInt()
                break
            }
        }
        if (hour < 0) {
            for (tok in tokens) {
                if (tok.matches(Regex("^\\d{3,4}$"))) {
                    val v = tok.toInt()
                    hour = v / 100
                    minute = v % 100
                    break
                }
                if (tok.matches(Regex("^\\d{1,2}$"))) {
                    hour = tok.toInt()
                    break
                }
            }
        }
        if (hour < 0) {
            for (i in 0 until tokens.size - 1) {
                val v = hindiNums[tokens[i]]
                if (v != null && tokens[i + 1] in bajeWords) {
                    hour = v
                    break
                }
            }
        }
        if (hour < 0 || hour > 24 || minute > 59) return null

        val night = tokens.any { it in nightWords }
        val pm = tokens.any { it in pmWords }
        val am = tokens.any { it in amWords }
        if (pm && hour in 1..11 && !(night && hour <= 5)) hour += 12
        if (night && hour == 12) hour = 0
        if (am && hour == 12) hour = 0
        if (hour == 24) hour = 0
        return hour to minute
    }

    /** नाम के सभी टुकड़े जिनमें मिलें, उन कॉन्टैक्ट्स की (नाम, नंबर) सूची; पूरा मिलान सबसे ऊपर */
    private fun findContacts(nameTokens: List<String>): List<Pair<String, String>> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val full = nameTokens.joinToString(" ")
        val found = LinkedHashMap<String, String>()
        ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val dn = c.getString(ni) ?: continue
                val num = c.getString(pi) ?: continue
                val low = dn.lowercase(Locale.ROOT)
                if (nameTokens.all { low.contains(it) } && !found.containsKey(dn)) found[dn] = num
            }
        }
        return found.entries
            .map { it.key to it.value }
            .sortedBy { if (it.first.lowercase(Locale.ROOT) == full) 0 else 1 }
    }

    /** हिंदी अंकों (९८७...) को 0-9 में बदलता है */
    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val d = Character.digit(ch, 10)
            if (d >= 0) sb.append('0' + d) else sb.append(ch)
        }
        return sb.toString()
    }

    /** "98765 43210" या "+91 98765-43210" जैसे टुकड़ों को एक नंबर बना देता है */
    private fun mergeDigitTokens(tokens: List<String>): List<String> {
        fun isNumPart(t: String) =
            t.any { it.isDigit() } && t.all { it.isDigit() || it == '+' || it == '-' }

        val out = mutableListOf<String>()
        for (tok in tokens) {
            if (isNumPart(tok)) {
                val clean = tok.replace("-", "")
                if (out.isNotEmpty() && isNumPart(out.last())) {
                    out[out.lastIndex] = out.last() + clean
                } else {
                    out.add(clean)
                }
            } else {
                out.add(tok)
            }
        }
        return out
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
