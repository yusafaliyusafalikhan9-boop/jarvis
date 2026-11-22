package com.yesmin.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Iron Man जैसा घूमता हुआ Arc Reactor / HUD */
class HudView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state = State.IDLE
    var level = 0f // 0..1, माइक की आवाज़ का स्तर

    private val cyan = Color.parseColor("#00E5FF")
    private val orange = Color.parseColor("#FFAB40")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var pulse = 0f
    private var lastNs = 0L

    private fun a(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun arcs(
        c: Canvas, cx: Float, cy: Float, radius: Float,
        count: Int, sweep: Float, start: Float, width: Float, color: Int
    ) {
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        val step = 360f / count
        for (i in 0 until count) c.drawArc(rect, start + i * step, sweep, false, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now

        val speed = when (state) {
            State.IDLE -> 0.5f
            State.LISTENING -> 1.1f
            State.THINKING -> 3.0f
            State.SPEAKING -> 1.5f
        }
        t += dt * speed
        val target = when (state) {
            State.LISTENING -> 0.25f + level * 0.75f
            State.SPEAKING -> 0.45f + 0.35f * sin(t * 6f)
            State.THINKING -> 0.5f
            State.IDLE -> 0.12f + 0.06f * sin(t * 2f)
        }
        pulse += (target - pulse) * 0.18f

        val color = if (state == State.THINKING) orange else cyan
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f

        // बीच की चमक
        val glowR = r * (0.38f + 0.22f * pulse)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, glowR, intArrayOf(a(color, 200), a(color, 0)), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, paint)
        paint.shader = null

        // कोर
        paint.color = a(Color.WHITE, 235)
        canvas.drawCircle(cx, cy, r * (0.10f + 0.05f * pulse), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.025f
        paint.color = a(color, 255)
        canvas.drawCircle(cx, cy, r * 0.17f, paint)

        // कोर के चारों ओर 10 तीलियाँ
        paint.strokeWidth = r * 0.03f
        paint.color = a(color, 220)
        for (i in 0 until 10) {
            val ang = Math.toRadians((i * 36f + t * 20f).toDouble())
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            canvas.drawLine(cx + ca * r * 0.21f, cy + sa * r * 0.21f, cx + ca * r * 0.27f, cy + sa * r * 0.27f, paint)
        }

        // घूमते छल्ले
        arcs(canvas, cx, cy, r * 0.34f, 3, 70f, t * 50f, r * 0.035f, a(color, 230))
        arcs(canvas, cx, cy, r * 0.50f, 6, 34f, -t * 70f, r * 0.02f, a(color, 200))
        arcs(canvas, cx, cy, r * 0.66f, 2, 150f, t * 30f, r * 0.012f, a(color, 170))

        // बाहरी निशान
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = r * 0.008f
        paint.color = a(color, 150)
        for (i in 0 until 72) {
            val ang = Math.toRadians((i * 5f + t * 8f).toDouble())
            val len = if (i % 6 == 0) r * 0.07f else r * 0.035f
            val r1 = r * 0.80f
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            canvas.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * (r1 + len), cy + sa * (r1 + len), paint)
        }

        // आवाज़ के साथ फैलने वाला बाहरी घेरा
        paint.strokeWidth = r * 0.006f
        paint.color = a(color, 110)
        canvas.drawCircle(cx, cy, r * (0.90f + 0.05f * pulse), paint)

        postInvalidateOnAnimation()
    }
}
