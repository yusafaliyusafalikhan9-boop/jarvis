package com.yesmin.jarvis

import java.util.Locale

/** "Hey Jarvis", "Jarvis", "जार्विस" पहचानना और कमांड से हटाना */
object WakeWord {
    private val stems = listOf(
        "jarvis", "jarwis", "jervis", "jarviz", "service jarvis",
        "जार्विस", "जारविस", "जर्विस", "जार्विज", "जारविज", "जार्विश", "जरविस", "जार्विस्"
    )
    private val fillers = setOf(
        "hey", "hi", "hello", "ok", "okay", "हे", "हेय", "हाय", "ओके", "सुनो", "अरे"
    )

    fun contains(s: String): Boolean {
        val l = s.lowercase(Locale.ROOT)
        return stems.any { l.contains(it) }
    }

    /** वेक वर्ड (और उसके आगे के hey/ओके जैसे शब्द) हटाकर बाकी कमांड लौटाता है */
    fun strip(s: String): String {
        var out = s
        for (stem in stems) out = out.replace(stem, " ", ignoreCase = true)
        return out.split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in fillers }
            .joinToString(" ")
    }
}
