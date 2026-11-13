package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sin

object C {
    val BG = Color.rgb(16, 18, 22)
    val PANEL = Color.rgb(26, 29, 35)
    val SOFT = Color.rgb(35, 39, 47)
    val LINE = Color.rgb(53, 58, 68)
    val WHITE = Color.rgb(247, 246, 241)
    val MUTED = Color.rgb(161, 167, 178)
    val LIME = Color.rgb(196, 245, 120)
    val A = Color.rgb(186, 165, 255)
    val B = Color.rgb(255, 185, 138)
    val REAR_LEFT = Color.rgb(119, 216, 245)
    val REAR_RIGHT = Color.rgb(255, 147, 203)
    val WARN = Color.rgb(255, 205, 110)
    fun singer(s: Singer?) = when (s) { Singer.A -> A; Singer.B -> B; Singer.C -> REAR_LEFT; Singer.D -> REAR_RIGHT; Singer.ALL -> LIME; null -> MUTED }
    fun seat(id: SeatId) = singer(Singer.valueOf(id.name))
    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

/** Decorative stage lights; no microphone meter or voice score is implied. */
class KaraokeBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var position = 0
    var running = false
    var tint = C.A
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(12, 9, 29))
        paint.shader = RadialGradient(width * .16f, height * .1f, width * .8f,
            intArrayOf(C.alpha(tint, 95), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(width * .94f, height * .55f, width * .6f,
            intArrayOf(C.alpha(C.REAR_RIGHT, 55), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
        val time = if (running) position / 2000f else 0f
        for (i in 0..42) {
            val x = ((i * 613 % 997) / 997f) * width
            val y = ((i * 197 % 631) / 631f) * height
            paint.color = C.alpha(if (i % 3 == 0) tint else Color.WHITE, (35 + 30 * (1 + sin(i + time))).toInt())
            c.drawCircle(x, y, if (i % 3 == 0) 2.8f else 1.6f, paint)
        }
        paint.strokeWidth = 1f; paint.color = C.alpha(tint, 26)
        for (i in 0..8) c.drawLine(width * .5f, height * .7f, width * (i / 8f), height.toFloat(), paint)
        for (i in 0..3) c.drawLine(0f, height * (.86f + i * .045f), width.toFloat(), height * (.86f + i * .045f), paint)
    }
}

/** Real line timestamps drive a left-to-right karaoke fill. This is lyric timing, not pitch analysis. */
class KaraokeLine(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var line = "함께 부를 준비됐나요?"
        set(value) { if (field != value) { field = value; contentDescription = value }; invalidate() }
    var fill = 0f
    var tint = C.LIME
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val density = resources.displayMetrics.density
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = minOf(height * .44f, 70 * density)
        while (paint.measureText(line) > width * .92f && paint.textSize > 18 * density) paint.textSize -= 1
        val textWidth = paint.measureText(line)
        val x = (width - textWidth) / 2
        val y = height * .5f - (paint.ascent() + paint.descent()) / 2
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 7 * density; paint.color = Color.rgb(4, 3, 14)
        c.drawText(line, x, y, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        c.drawText(line, x, y, paint)
        c.save(); c.clipRect(x - 2, 0f, x + textWidth * fill.coerceIn(0f, 1f), height.toFloat())
        paint.color = tint; paint.setShadowLayer(14 * density, 0f, 0f, C.alpha(tint, 120))
        c.drawText(line, x, y, paint); paint.clearShadowLayer(); c.restore()
    }
}

class Ui(private val context: Context) {
    val scale = (context.resources.configuration.screenWidthDp / 1600f).coerceIn(.65f, 1.6f)
    fun dp(n: Int) = (n * scale * context.resources.displayMetrics.density).toInt()
    fun bg(c: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    fun text(s: CharSequence, size: Float = 20f, color: Int = C.WHITE, bold: Boolean = false) = TextView(context).apply {
        text = s; textSize = size * scale; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }
    fun button(label: String, fill: Int = C.SOFT, fg: Int = C.WHITE, enabled: Boolean = true, action: () -> Unit) =
        text(label, 19f, fg, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(60)
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = RippleDrawable(ColorStateList.valueOf(C.alpha(C.WHITE, 35)), bg(fill, 15), null)
            isEnabled = enabled; isClickable = enabled; isFocusable = enabled; alpha = if (enabled) 1f else .35f
            if (enabled) setOnClickListener { action() }
        }
    fun chip(s: String, color: Int) = text(s, 16f, color, true).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7)); background = bg(C.alpha(color, 24), 30)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun card(fill: Int = C.PANEL, stroke: Int? = null, pad: Int = 24) = column().apply {
        background = bg(fill, 22, stroke); setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }
    fun space(w: Int = 0, h: Int = 0) = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)) }
    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply { setMargins(0, dp(top), 0, dp(bottom)) }
    fun weight(v: View, weight: Float = 1f, height: Int = -2): View { v.layoutParams = LinearLayout.LayoutParams(0, height, weight); return v }
    fun scroll(v: View) = ScrollView(context).apply { isFillViewport = true; clipToPadding = false; addView(v); isVerticalScrollBarEnabled = false }
    fun title(box: LinearLayout, tag: String, heading: String, desc: String) {
        box.addView(text(tag, 15f, C.LIME, true)); box.addView(space(h = 8))
        box.addView(text(heading, 35f, bold = true)); box.addView(space(h = 8))
        box.addView(text(desc, 18f, C.MUTED)); box.addView(space(h = 22))
    }
}

/** Decorative on-screen light only. Its beat is sampled from the actual MediaPlayer position. */
class StageArt(context: Context) : View(context) {
    var position = 0
    var active = false
    var color = C.LIME
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mid = height * .5f
        val gap = width / 52f
        for (i in 0..50) {
            val amplitude = if (active) .2f + .8f * kotlin.math.abs(sin(i * .73 + position / 430.0)).toFloat() else .18f + .14f * kotlin.math.abs(sin(i * .7)).toFloat()
            val h = height * .42f * amplitude
            paint.color = C.alpha(color, if (active) 180 else 65)
            canvas.drawRoundRect(i * gap, mid - h, i * gap + gap * .38f, mid + h, gap, gap, paint)
        }
    }
}
