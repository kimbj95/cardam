package app.cardam.warning

import app.cardam.data.Telltale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The repair-shop report for one warning light: plain text, so a phone camera reading the QR code shows it as is,
 * with no server and no network. Kept short enough for a QR code a phone reads from the car screen.
 */
object Report {
    private val TIME = DateTimeFormatter.ofPattern("M/d HH:mm")

    fun text(telltale: Telltale, history: List<WarningEvent>, vin: String?, source: String): String = buildString {
        appendLine("[카담 정비 리포트] ${telltale.name}")
        vin?.let { appendLine("차대번호 $it") }
        Patterns.of(history)?.let { appendLine(it.sentence()) }
        history.takeLast(MAX_LINES).forEachIndexed { i, e -> appendLine("${i + 1}) ${line(e)}") }
        if (telltale.pages.isNotEmpty()) append("설명서 ${telltale.pages}쪽 (${source.substringBefore(',')})")
    }.trimEnd()

    /** "10/26 07:41 켜짐 · 14분 뒤 꺼짐 · 바깥 -2℃ · 출발 6분 · 시속 58km · 23,418km" */
    fun line(e: WarningEvent): String = listOfNotNull(
        "${time(e.onAt)} 켜짐" + if (e.missed) "(앱이 꺼져 있던 동안)" else "",
        when {
            e.open -> "지금도 켜져 있음"
            e.offUnseen -> "앱이 꺼져 있던 동안 꺼짐"
            (e.durationMinutes() ?: 0) < 1 -> "1분 안에 꺼짐"
            else -> "${e.durationMinutes()}분 뒤 꺼짐"
        },
        e.outsideTempC?.let { "바깥 ${it.roundToInt()}℃" },
        e.minutesDriven?.let { "출발 ${it}분" },
        "시속 ${e.speedKmh.roundToInt()}km",
        e.odometerKm?.takeIf { it > 0 }?.let { "%,dkm".format(it.roundToInt()) },
    ).joinToString(" · ")

    fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    private const val MAX_LINES = 5
}
