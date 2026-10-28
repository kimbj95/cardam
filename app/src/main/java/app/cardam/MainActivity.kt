package app.cardam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.car.Car
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.cardam.data.Level
import app.cardam.warning.Patterns
import app.cardam.warning.Report
import app.cardam.warning.Sample
import app.cardam.warning.WarningEvent
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The parked-only screen, in the look of the Pleos vehicle-control app (square tiles, 1px dividers, Asta Sans, day
 * and night colors that follow the system): the warning-light record on the left, and for the chosen light what it
 * means and what to do (from the owner's manual), the car's state the moment it came on, the pattern over the last
 * 30 days, and a report for the repair shop. The demo panel opens after holding the title for 3 seconds.
 * The app does not declare Distraction Optimization, so the system blocks this screen while driving. As a second
 * guard the screen also covers itself whenever UX restrictions are on or the gear is not P.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var colors: Palette
    private lateinit var regular: Typeface
    private lateinit var bold: Typeface
    private lateinit var list: LinearLayout
    private lateinit var countText: TextView
    private lateinit var detail: LinearLayout
    private lateinit var report: FrameLayout
    private lateinit var demoPanel: LinearLayout
    private lateinit var demoText: TextView
    private lateinit var drivingCover: TextView
    private var selectedId: Long? = null
    private var newestSeen: Long? = null
    private var shownSignature = ""

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        colors = if (night) NIGHT else DAY
        regular = Typeface.Builder(assets, FONT).setFontVariationSettings("'wght' 400").build()
        bold = Typeface.Builder(assets, FONT).setFontVariationSettings("'wght' 700").build()
        setContentView(buildScreen())
        requestPermissions(
            arrayOf(Car.PERMISSION_SPEED, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_PERMISSIONS,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CardamService.instance?.monitor?.registerSpeed()
    }

    override fun onResume() {
        super.onResume()
        // Also when the screen comes back to the front after the service was stopped (e.g. during Suspend).
        CardamService.start(this)
        shownSignature = ""
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    // ---------------------------------------------------------------- layout
    private fun buildScreen(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(48), dp(34), dp(48), dp(28))
        }
        header.addView(text("카담", 44f, bold = true).also(::holdForDemo))
        header.addView(text(getString(R.string.tagline), 26f, bold = true, color = colors.accent).apply { setPadding(dp(20), 0, 0, dp(4)) })
        header.addView(text("정차 중(P단)에만 보이는 화면이에요", 24f, color = colors.sub).apply { setPadding(dp(28), 0, 0, dp(4)) })
        column.addView(header)
        column.addView(divider())

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        countText = label("경고등 기록").apply { setPadding(dp(48), 0, dp(48), dp(16)) }
        left.addView(countText)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(left, LinearLayout.LayoutParams(dp(560), LinearLayout.LayoutParams.MATCH_PARENT))
        body.addView(verticalDivider())
        detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(34), dp(56), dp(40))
        }
        body.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        column.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        report = FrameLayout(this).apply {
            setBackgroundColor(colors.bg)
            visibility = View.GONE
            isClickable = true
        }
        demoPanel = buildDemoPanel()
        // Covers the whole screen while the car restricts driver distraction, so nothing on it needs a look.
        drivingCover = text("주행 중에는 화면 대신 목소리로 알려 드려요.\n정차하면(P단) 다시 보여요.", 30f, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colors.bg)
            visibility = View.GONE
            isClickable = true
        }
        return FrameLayout(this).apply {
            addView(column)
            addView(report, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(demoPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(drivingCover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** Hidden panel for the demo video and tests. */
    private fun buildDemoPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(colors.tile)
        addView(divider())
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(28))
        }
        body.addView(label("시연 (영상·시험용)"))
        demoText = text("", 22f, color = colors.sub)
        body.addView(demoText)
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.line)
            setPadding(1, 1, 1, 1)
        }
        listOf(
            "지난 기록 넣기" to { demo("seed:TPMS") },
            "바깥 -3℃" to { toggleOutside() },
            "기록 지우기" to { demo("clear") },
            "닫기" to { visibility = View.GONE },
        ).forEachIndexed { i, (name, action) ->
            row.addView(text(name, 24f).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(colors.bg)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { if (i > 0) marginStart = 1 })
        }
        body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(body)
    }

    /** Holding the title for [DEMO_HOLD_MS] opens the demo panel, so it stays out of the way in normal use. */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdForDemo(view: View) {
        val open = Runnable { demoPanel.visibility = View.VISIBLE }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(open, DEMO_HOLD_MS)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(open)
            }
            true
        }
    }

    // ---------------------------------------------------------------- state
    private fun render() {
        val service = CardamService.instance ?: return
        val state = service.state
        drivingCover.visibility = if (state.drivingRestricted || !state.parked()) View.VISIBLE else View.GONE
        val outside = service.engine.demo.fakeOutsideTempC
        demoText.text = "바깥 기온 시연 ${if (outside.isNaN()) "꺼짐" else "${outside.toInt()}℃"} · 기록 ${service.store.events.size}개"

        val events = service.store.events.reversed()
        // A light that just came on is shown first; otherwise the choice stays.
        val newest = events.firstOrNull()?.id
        if (newest != newestSeen || selectedId == null || events.none { it.id == selectedId }) {
            newestSeen = newest
            selectedId = (events.firstOrNull { it.open } ?: events.firstOrNull())?.id
        }
        // Rebuild only when something shown changed (a new light, one going off, another choice).
        val signature = events.joinToString { "${it.id}:${it.open}:${it.samples.size / 20}" } + "|$selectedId"
        if (signature == shownSignature) return
        shownSignature = signature

        val lit = events.count { it.open }
        countText.text = if (lit > 0) "경고등 기록 · 지금 ${lit}개 켜져 있어요" else "경고등 기록"
        list.removeAllViews()
        if (events.isEmpty()) {
            list.addView(text("아직 기록이 없어요", 26f, color = colors.sub).apply { setPadding(dp(48), dp(8), dp(48), 0) })
        }
        events.forEach { list.addView(row(it, it.id == selectedId)) }
        renderDetail(service, events.firstOrNull { it.id == selectedId })
    }

    private fun row(event: WarningEvent, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(if (selected) colors.tile else colors.bg)
        setOnClickListener {
            selectedId = event.id
            render()
        }
        addView(View(this@MainActivity).apply { setBackgroundColor(levelColor(event.level)) }, LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT))
        val texts = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(22), dp(40), dp(22))
        }
        texts.addView(text(event.name, 28f, bold = true))
        val status = when {
            event.open -> "지금 켜져 있음"
            event.offUnseen -> "꺼짐"
            else -> "${minutesText(event)} 꺼짐"
        }
        texts.addView(text("${dateTime(event.onAt)} · $status", 22f, color = if (event.open) levelColor(event.level) else colors.sub))
        addView(texts)
    }

    private fun renderDetail(service: CardamService, event: WarningEvent?) {
        detail.removeAllViews()
        if (event == null) {
            detail.addView(text("경고등이 켜지면\n그 순간 목소리로 알려 드려요", 44f, bold = true))
            detail.addView(text("무슨 뜻인지, 계속 가도 되는지 먼저 말하고, 켜진 순간의 차 상태를 여기에 남겨요. 정비소에서 이 기록을 그대로 보여 줄 수 있어요.", 26f, color = colors.sub).apply { setPadding(0, dp(24), 0, 0) })
            return
        }
        val book = service.engine.book
        val telltale = book.of(event.type, event.color)

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(text(event.name, 46f, bold = true))
        title.addView(text(if (event.level == Level.STOP) "바로 세우기" else "곧 점검", 24f, bold = true, color = colors.inverseText).apply {
            setBackgroundColor(levelColor(event.level))
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
        detail.addView(title)
        val state = when {
            event.open -> "지금 켜져 있어요"
            event.offUnseen -> "앱이 꺼져 있던 동안 꺼졌어요"
            else -> "${minutesText(event)} 꺼졌어요"
        }
        detail.addView(text("${dateTime(event.onAt)}에 켜짐${if (event.missed) "(앱이 꺼져 있던 동안)" else ""} · $state", 24f, color = colors.sub).apply { setPadding(0, dp(8), 0, 0) })

        detail.addView(label("무슨 뜻인가요").apply { setPadding(0, dp(30), 0, dp(8)) })
        detail.addView(text(telltale.meaning, 28f))
        detail.addView(label("어떻게 하나요").apply { setPadding(0, dp(24), 0, dp(8)) })
        detail.addView(text(telltale.action, 28f))
        if (telltale.pages.isNotEmpty()) {
            detail.addView(text("그랜저(GN7) 2026 취급설명서 ${telltale.pages}쪽을 요약했어요", 20f, color = colors.sub).apply { setPadding(0, dp(12), 0, 0) })
        }

        detail.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(30) })
        detail.addView(label("켜진 순간").apply { setPadding(0, dp(26), 0, dp(10)) })
        val facts = SpannableStringBuilder()
            .fact("시속", "${event.speedKmh.roundToInt()}km")
            .fact("기어", gearName(event.gear))
            .fact("바깥", event.outsideTempC?.let { "${it.roundToInt()}℃" } ?: "-")
            .fact("출발 뒤", event.minutesDriven?.let { "${it}분" } ?: "-")
        event.odometerKm?.takeIf { it > 0 }?.let { facts.fact("주행거리", "%,dkm".format(it.roundToInt())) }
        if (event.others.isNotEmpty()) facts.fact("함께 켜짐", event.others.joinToString(", "))
        detail.addView(text("", 26f, color = colors.sub).apply { text = facts })
        if (event.samples.isNotEmpty()) {
            detail.addView(MomentChart(this).also { it.update(event.samples, levelColor(event.level), colors, regular) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(18) })
            detail.addView(text("켜지기 30초 전부터 30초 뒤까지의 속도 · 아래 막대는 브레이크를 밟은 때", 20f, color = colors.sub).apply { setPadding(0, dp(6), 0, 0) })
        }

        val history = service.store.history(event.type, System.currentTimeMillis() - Patterns.WINDOW_DAYS * 24 * 60 * 60 * 1000L)
        Patterns.of(history)?.let { pattern ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colors.tile)
                setPadding(dp(32), dp(26), dp(32), dp(26))
            }
            box.addView(label("반복해서 켜져요"))
            box.addView(text(pattern.sentence(), 28f, bold = true))
            if (pattern.cold && telltale.cold != null) box.addView(text(telltale.cold, 24f, color = colors.sub).apply { setPadding(0, dp(10), 0, 0) })
            detail.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
        }

        detail.addView(text("정비소에 보여 주기", 30f, bold = true, color = colors.inverseText).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colors.inverseBg)
            setOnClickListener { showReport(Report.text(telltale, history, service.state.vin, book.source)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = dp(30) })
    }

    /** Full-screen report with a QR code a phone camera reads as plain text (no server, no network). */
    private fun showReport(content: String) {
        report.removeAllViews()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(56), dp(72), dp(56))
        }
        row.addView(ImageView(this).apply { setImageBitmap(qr(content, dp(520))) }, LinearLayout.LayoutParams(dp(520), dp(520)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), 0, 0, 0)
        }
        texts.addView(text("정비소에 보여 주세요", 44f, bold = true))
        texts.addView(text("휴대폰 카메라로 QR 코드를 찍으면 이 기록이 글자로 보여요. 인터넷 연결은 필요 없어요.", 24f, color = colors.sub).apply { setPadding(0, dp(12), 0, dp(24)) })
        texts.addView(text(content, 24f))
        texts.addView(text("닫기", 28f, bold = true, color = colors.inverseText).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colors.inverseBg)
            setOnClickListener { report.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(240), dp(84)).apply { topMargin = dp(32) })
        row.addView(ScrollView(this).apply { addView(texts) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        report.addView(row)
        report.visibility = View.VISIBLE
    }

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- helpers
    private fun demo(command: String) {
        CardamService.instance?.engine?.onDemoCommand(command)
        shownSignature = ""
        render()
    }

    private fun toggleOutside() {
        val engine = CardamService.instance?.engine ?: return
        demo(if (engine.demo.fakeOutsideTempC.isNaN()) "outside:-3" else "outside:off")
    }

    /** "시속 58km" with the value in the text color and bold, then a gap before the next fact. */
    private fun SpannableStringBuilder.fact(name: String, value: String): SpannableStringBuilder {
        if (isNotEmpty()) append("     ")
        append("$name ")
        val start = length
        append(value)
        setSpan(ForegroundColorSpan(colors.text), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(bold), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    /** "14분 뒤", or "1분 안에" for a light that went off within a minute. */
    private fun minutesText(event: WarningEvent): String {
        val minutes = event.durationMinutes() ?: 0
        return if (minutes < 1) "1분 안에" else "${minutes}분 뒤"
    }

    private fun gearName(gear: String) = when (gear) {
        "PARK" -> "P"
        "REVERSE" -> "R"
        "NEUTRAL" -> "N"
        "DRIVE" -> "D"
        else -> "-"
    }

    private fun dateTime(at: Long): String = DATE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    private fun levelColor(level: Level) = if (level == Level.STOP) colors.stop else colors.check

    private fun label(value: String) = text(value, 26f, color = colors.sub).apply { setPadding(0, 0, 0, dp(10)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(colors.line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun verticalDivider() = View(this).apply {
        setBackgroundColor(colors.line)
        layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = colors.text) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) this@MainActivity.bold else regular
        setLineSpacing(0f, 1.15f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Colors sampled from the Pleos vehicle-control app in day and night mode, plus the warning-light colors. */
    class Palette(
        val bg: Int, val tile: Int, val line: Int, val text: Int, val sub: Int,
        val inverseBg: Int, val inverseText: Int, val accent: Int, val stop: Int, val check: Int,
    )

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val FONT = "fonts/AstaSans.ttf"
        const val DEMO_HOLD_MS = 3_000L
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

        val DAY = Palette(
            bg = Color.parseColor("#FFFFFF"), tile = Color.parseColor("#F0F0F0"), line = Color.parseColor("#A6A6A6"),
            text = Color.parseColor("#000000"), sub = Color.parseColor("#7C7C7C"), inverseBg = Color.parseColor("#000000"),
            inverseText = Color.parseColor("#FFFFFF"), accent = Color.parseColor("#1262F5"),
            stop = Color.parseColor("#D93A2B"), check = Color.parseColor("#E08A00"),
        )
        val NIGHT = Palette(
            bg = Color.parseColor("#151515"), tile = Color.parseColor("#222222"), line = Color.parseColor("#333333"),
            text = Color.parseColor("#DDDDDD"), sub = Color.parseColor("#9A9A9A"), inverseBg = Color.parseColor("#DDDDDD"),
            inverseText = Color.parseColor("#151515"), accent = Color.parseColor("#4D8BFF"),
            stop = Color.parseColor("#FF5A4A"), check = Color.parseColor("#FFAA1F"),
        )
    }
}

/**
 * Speed in the 30 seconds before and after a light came on: a line for the speed, a mark at the moment it came on,
 * and bars along the bottom while the brake was pressed.
 */
private class MomentChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var samples: List<Sample> = emptyList()
    private var mark = Color.RED
    private var colors: MainActivity.Palette? = null
    private var font: Typeface? = null

    fun update(samples: List<Sample>, mark: Int, colors: MainActivity.Palette, font: Typeface) {
        this.samples = samples
        this.mark = mark
        this.colors = colors
        this.font = font
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val d = resources.displayMetrics.density
        val left = 0f
        val right = width.toFloat()
        val top = 30 * d
        val brakeBand = 14 * d
        val base = height - 34 * d - brakeBand
        val maxSpeed = maxOf(60f, (samples.maxOfOrNull { it.speedKmh } ?: 0f) * 1.15f)
        fun x(t: Int) = left + (t + SPAN_MS) / (2f * SPAN_MS) * (right - left)
        fun y(kmh: Float) = base - kmh / maxSpeed * (base - top)

        paint.style = Paint.Style.FILL
        paint.color = c.line
        canvas.drawRect(left, base, right, base + 1, paint)

        // Brake: one bar per half-second sample while pressed.
        paint.color = c.sub
        samples.filter { it.brake }.forEach { s ->
            canvas.drawRect(x(s.t), base + 6 * d, x(s.t + SAMPLE_MS), base + 6 * d + brakeBand, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * d
        paint.color = c.text
        val sorted = samples.sortedBy { it.t }
        for (i in 1 until sorted.size) {
            canvas.drawLine(x(sorted[i - 1].t), y(sorted[i - 1].speedKmh), x(sorted[i].t), y(sorted[i].speedKmh), paint)
        }

        paint.color = mark
        paint.strokeWidth = 3 * d
        canvas.drawLine(x(0), top - 8 * d, x(0), base, paint)

        paint.style = Paint.Style.FILL
        paint.typeface = font
        paint.textSize = 18 * d
        paint.color = c.sub
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("30초 전", left, height - 6 * d, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("30초 뒤", right, height - 6 * d, paint)
        canvas.drawText("시속 ${maxSpeed.roundToInt()}km", right, top - 10 * d, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.color = mark
        canvas.drawText("켜진 순간", x(0), top - 10 * d, paint)
    }

    private companion object {
        const val SPAN_MS = 30_000
        const val SAMPLE_MS = 500
    }
}
