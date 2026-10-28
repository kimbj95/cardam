package app.cardam.warning

import app.cardam.data.Level
import org.json.JSONArray
import org.json.JSONObject

/** One reading around the moment a light came on: [t] is ms from that moment (negative before it). */
data class Sample(val t: Int, val speedKmh: Float, val brake: Boolean, val steeringDeg: Float)

/**
 * One warning light, from the moment it came on to the moment it went off, with the car's state at that moment:
 * what a repair shop would ask about an intermittent warning. Times are wall-clock ms.
 */
class WarningEvent(
    val id: Long,
    val type: String,
    val name: String,
    val level: Level,
    val color: String,
    val blinking: Boolean,
    val onAt: Long,
    /** Found already lit when the app (re)started: it came on while the app was not running. */
    val missed: Boolean,
    val speedKmh: Float,
    val gear: String,
    val outsideTempC: Float?,
    val odometerKm: Float?,
    val rangeKm: Float?,
    /** Minutes since the car left P on this drive; null when parked or unknown. */
    val minutesDriven: Int?,
    val lat: Double?,
    val lng: Double?,
    /** Names of the other warning lights lit at the same moment. */
    val others: List<String>,
    /** 30 seconds before and after the moment it came on, every half second. */
    val samples: MutableList<Sample> = mutableListOf(),
    var offAt: Long? = null,
    var offOdometerKm: Float? = null,
    /** It went off while the app was not running, so [offAt] is only the time the app noticed. */
    var offUnseen: Boolean = false,
    /** Made by the `seed:` demo command for the demo video, not by the car. */
    val demo: Boolean = false,
) {
    val open: Boolean get() = offAt == null

    fun durationMinutes(): Long? = offAt?.let { (it - onAt) / 60_000 }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("name", name).put("level", level.name).put("color", color)
        .put("blinking", blinking).put("onAt", onAt).put("missed", missed).put("speedKmh", speedKmh.toDouble())
        .put("gear", gear).putOpt("outsideTempC", outsideTempC?.toDouble()).putOpt("odometerKm", odometerKm?.toDouble())
        .putOpt("rangeKm", rangeKm?.toDouble()).putOpt("minutesDriven", minutesDriven).putOpt("lat", lat).putOpt("lng", lng)
        .put("others", JSONArray(others))
        .put("samples", JSONArray(samples.map { JSONArray(listOf(it.t, it.speedKmh.toDouble(), if (it.brake) 1 else 0, it.steeringDeg.toDouble())) }))
        .putOpt("offAt", offAt).putOpt("offOdometerKm", offOdometerKm?.toDouble()).put("offUnseen", offUnseen).put("demo", demo)

    companion object {
        fun fromJson(o: JSONObject): WarningEvent {
            val others = o.optJSONArray("others") ?: JSONArray()
            val samples = o.optJSONArray("samples") ?: JSONArray()
            return WarningEvent(
                id = o.getLong("id"),
                type = o.getString("type"),
                name = o.getString("name"),
                level = Level.valueOf(o.getString("level")),
                color = o.optString("color"),
                blinking = o.optBoolean("blinking"),
                onAt = o.getLong("onAt"),
                missed = o.optBoolean("missed"),
                speedKmh = o.optDouble("speedKmh", 0.0).toFloat(),
                gear = o.optString("gear"),
                outsideTempC = o.optFloat("outsideTempC"),
                odometerKm = o.optFloat("odometerKm"),
                rangeKm = o.optFloat("rangeKm"),
                minutesDriven = if (o.has("minutesDriven")) o.getInt("minutesDriven") else null,
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lng = if (o.has("lng")) o.getDouble("lng") else null,
                others = List(others.length()) { others.getString(it) },
                samples = MutableList(samples.length()) { i ->
                    val s = samples.getJSONArray(i)
                    Sample(s.getInt(0), s.getDouble(1).toFloat(), s.getInt(2) == 1, s.getDouble(3).toFloat())
                },
                offAt = if (o.has("offAt")) o.getLong("offAt") else null,
                offOdometerKm = o.optFloat("offOdometerKm"),
                offUnseen = o.optBoolean("offUnseen"),
                demo = o.optBoolean("demo"),
            )
        }

        private fun JSONObject.optFloat(key: String): Float? = if (has(key)) getDouble(key).toFloat() else null
    }
}
