package com.yesmin.jarvis

import android.content.Context

/** ऐप की सेटिंग्स (API key सिर्फ़ फ़ोन के अंदर रहती है, कोड या GitHub में नहीं) */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = sp(ctx).getString("key", "").orEmpty()
    fun setApiKey(ctx: Context, v: String) = sp(ctx).edit().putString("key", v).apply()

    fun greeting(ctx: Context): Boolean = sp(ctx).getBoolean("greeting", true)
    fun setGreeting(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("greeting", v).apply()

    fun floatingHud(ctx: Context): Boolean = sp(ctx).getBoolean("hud", true)
    fun setFloatingHud(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hud", v).apply()

    fun bgListen(ctx: Context): Boolean = sp(ctx).getBoolean("bg", false)
    fun setBgListen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bg", v).apply()

    /** ग्रीटिंग दो बार के बीच कम से कम इतने मिनट */
    fun greetGapMin(ctx: Context): Int = sp(ctx).getInt("greet_gap", 10)
    fun setGreetGapMin(ctx: Context, v: Int) = sp(ctx).edit().putInt("greet_gap", v).apply()

    fun lastGreet(ctx: Context): Long = sp(ctx).getLong("last_greet", 0L)
    fun setLastGreet(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_greet", v).apply()

    fun canOverlay(ctx: Context): Boolean = android.provider.Settings.canDrawOverlays(ctx)
}
