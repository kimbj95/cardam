package app.cardam.voice

enum class Answer { YES, NO, UNKNOWN }

/**
 * Turns a recognized short answer into a decision. Negative words are checked first ("아니요", "됐어요").
 * Every question this app asks is an offer, and "괜찮아요" to an offer means "no, thanks".
 */
object Answers {
    private val NO_WORDS = listOf("아니", "싫어", "아뇨", "됐어", "괜찮", "별로", "그만", "나중에")
    private val YES_WORDS = listOf("응", "네", "예", "그래", "좋아", "가자", "넣어")
    private val YES_EXACT = setOf("어", "웅", "엉", "콜", "오케이")

    fun yesNo(text: String): Answer {
        val t = normalize(text)
        return when {
            t.isEmpty() -> Answer.UNKNOWN
            NO_WORDS.any { t.contains(it) } -> Answer.NO
            t in YES_EXACT || YES_WORDS.any { t.contains(it) } -> Answer.YES
            else -> Answer.UNKNOWN
        }
    }

    private fun normalize(text: String) = text.replace(" ", "").trimEnd('.', '?', '!', '~')
}
