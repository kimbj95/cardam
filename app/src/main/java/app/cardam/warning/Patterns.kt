package app.cardam.warning

import java.time.Instant
import java.time.ZoneId

/**
 * Finds what the times one warning light came on have in common, for a shop that cannot reproduce an intermittent
 * warning: "최근 30일 동안 3번 켜졌어요. 모두 바깥 기온 0℃ 이하, 출발 10분 안이었어요."
 * Each condition must hold for every time, and at least [MIN_TIMES] times are needed.
 */
object Patterns {
    const val WINDOW_DAYS = 30
    private const val MIN_TIMES = 2

    data class Pattern(val times: Int, val common: List<String>) {
        val cold: Boolean get() = COLD in common

        fun sentence(): String {
            val head = "최근 ${WINDOW_DAYS}일 동안 ${times}번 켜졌어요."
            if (common.isEmpty()) return head
            val list = common.joinToString(", ")
            return "$head 모두 $list${copula(list)}."
        }

        /** "이었어요" after a final consonant ("출발 10분 안이었어요"), "였어요" otherwise ("0℃ 이하였어요"). */
        private fun copula(phrase: String): String {
            val last = phrase.replace(Regex("\\([^)]*\\)"), "").trimEnd().lastOrNull { it in '가'..'힣' }
            return if (last != null && (last - '가') % 28 != 0) "이었어요" else "였어요"
        }
    }

    fun of(history: List<WarningEvent>): Pattern? {
        if (history.size < MIN_TIMES) return null
        val common = buildList {
            if (history.all { (it.outsideTempC ?: Float.NaN) <= 0f }) add(COLD)
            if (history.all { (it.outsideTempC ?: Float.NaN) >= 30f }) add("바깥 기온 30℃ 이상")
            if (history.all { (it.minutesDriven ?: Int.MAX_VALUE) <= 10 }) add("출발 10분 안")
            if (history.all { it.speedKmh >= 80f }) add("시속 80km 이상")
            if (history.all { hour(it.onAt) in 5..9 }) add("아침 시간(5~10시)")
            val closed = history.filter { !it.open && !it.offUnseen }
            if (closed.size == history.size && closed.all { (it.durationMinutes() ?: Long.MAX_VALUE) <= 20 }) add("20분 안에 저절로 꺼진 때")
        }
        return Pattern(history.size, common)
    }

    private fun hour(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour

    private const val COLD = "바깥 기온 0℃ 이하"
}
