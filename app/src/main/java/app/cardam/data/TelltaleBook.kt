package app.cardam.data

import android.content.Context
import app.cardam.vehicle.Light
import org.json.JSONObject

/** How urgent a lit warning light is. INFO lights (head lamps, turn signals, ...) are neither spoken nor recorded. */
enum class Level { STOP, CHECK, INFO }

/** What the app offers after explaining the light. */
enum class Next { SHOP, NONE }

/** One warning light as the owner's manual explains it (summarized), with the manual pages it came from. */
data class Telltale(
    val type: String,
    val name: String,
    val level: Level,
    val next: Next,
    val drive: String,
    val meaning: String,
    val action: String,
    val cold: String?,
    val pages: String,
)

/**
 * The warning-light dictionary in `assets/telltales.json`, written from the 그랜저(GN7) 2026 owner's manual.
 * A light the dictionary does not explain falls back to its color: red means stop, yellow means check soon.
 */
class TelltaleBook(context: Context) {
    private class Entry(
        val telltale: Telltale,
        val byColor: Boolean,
        val colors: Set<String>?,
        val blinkIsInfo: Boolean,
        val movingOnly: Boolean,
    )

    private val entries: Map<String, Entry>
    val source: String

    init {
        val json = JSONObject(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
        source = json.getString("source")
        val array = json.getJSONArray("items")
        entries = List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val level = o.getString("level")
            val name = o.getString("name")
            val colors = o.optJSONArray("colors")?.let { a -> List(a.length()) { a.getString(it) }.toSet() }
            Entry(
                telltale = Telltale(
                    type = o.getString("type"),
                    name = name,
                    level = if (level == "COLOR") Level.CHECK else Level.valueOf(level),
                    next = Next.valueOf(o.optString("next", "NONE")),
                    drive = o.optString("drive").ifEmpty { "${name}${subjectParticle(name)} 켜졌어요." },
                    meaning = o.optString("meaning").ifEmpty { "이 차종 설명서에는 이 경고등 설명이 없어요. 차량 설명서를 확인하세요." },
                    action = o.optString("action").ifEmpty { "정차한 뒤 설명서를 확인하고, 계속 켜져 있으면 정비소에서 점검받으세요." },
                    cold = o.optString("cold").ifEmpty { null },
                    pages = o.optString("pages"),
                ),
                byColor = level == "COLOR",
                colors = colors,
                blinkIsInfo = o.optBoolean("blinkIsInfo"),
                movingOnly = o.optBoolean("movingOnly"),
            )
        }.associateBy { it.telltale.type }
    }

    /** The dictionary entry, or a color-based stand-in for a light the manual does not explain. */
    fun of(type: String, color: String = ""): Telltale = entries[type]?.telltale ?: Telltale(
        type = type,
        name = if (color == "RED") "빨간 경고등" else "노란 경고등",
        level = levelOfColor(color),
        next = Next.NONE,
        drive = if (color == "RED") "빨간 경고등이 켜졌어요. 안전한 곳에 세운 뒤 확인하세요." else "노란 경고등이 켜졌어요. 정차한 뒤 확인하세요.",
        meaning = "설명서에 없는 경고등이에요. 빨간색은 바로 확인할 것, 노란색은 점검이 필요하다는 뜻이 많아요.",
        action = "정차한 뒤 계기판 메시지와 차량 설명서를 확인하세요.",
        cold = null,
        pages = "",
    )

    /**
     * How urgent [light] is right now. Some lights are warnings only in one color (오토 홀드 yellow), are normal while
     * blinking (ESC working on a slippery road), or only while moving (the brake light also shows the parking brake).
     */
    fun levelOf(light: Light, moving: Boolean): Level {
        val entry = entries[light.type] ?: return levelOfColor(light.color)
        return when {
            entry.byColor -> levelOfColor(light.color)
            entry.colors != null && light.color !in entry.colors -> Level.INFO
            entry.blinkIsInfo && light.blinking -> Level.INFO
            entry.movingOnly && !moving -> Level.INFO
            else -> entry.telltale.level
        }
    }

    private fun levelOfColor(color: String) = when (color) {
        "RED" -> Level.STOP
        "YELLOW" -> Level.CHECK
        else -> Level.INFO
    }

    private companion object {
        const val ASSET = "telltales.json"
    }
}

/** "이" after a final consonant, "가" otherwise ("경고등이", "키가"). */
fun subjectParticle(word: String): String = if (hasFinalConsonant(word)) "이" else "가"

/** "을" after a final consonant, "를" otherwise ("정비소를", "센터를"). */
fun objectParticle(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

private fun hasFinalConsonant(word: String): Boolean {
    val last = word.trimEnd().lastOrNull() ?: return false
    if (last !in '가'..'힣') return false
    return (last - '가') % 28 != 0
}
