package app.cardam.warning

import android.content.Context
import app.cardam.log
import org.json.JSONArray
import java.io.File

/**
 * Keeps the warning-light record in a JSON file, so it survives a restarted process (or a car resumed from Suspend
 * after the process was killed), and the drive start time in SharedPreferences.
 */
class EventStore(context: Context) {
    private val file = File(context.filesDir, "events.json")
    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    /** Oldest first. */
    val events: MutableList<WarningEvent> = load()

    /** Wall-clock time the car last left P, or 0 while parked. */
    var driveStartedAt: Long = prefs.getLong("driveStartedAt", 0)
        set(value) {
            field = value
            prefs.edit().putLong("driveStartedAt", value).apply()
        }

    fun add(event: WarningEvent) {
        events.add(event)
        events.sortBy { it.onAt }
        while (events.size > MAX_EVENTS) events.removeAt(0)
        save()
    }

    fun openEvent(type: String): WarningEvent? = events.lastOrNull { it.type == type && it.open }

    fun openEvents(): List<WarningEvent> = events.filter { it.open }

    /** Events of [type] since [since], oldest first. */
    fun history(type: String, since: Long): List<WarningEvent> = events.filter { it.type == type && it.onAt >= since }

    fun clear() {
        events.clear()
        save()
    }

    fun save() {
        runCatching { file.writeText(JSONArray(events.map { it.toJson() }).toString()) }
            .onFailure { log("event save failed: $it") }
    }

    private fun load(): MutableList<WarningEvent> = runCatching {
        if (!file.exists()) return@runCatching mutableListOf()
        val array = JSONArray(file.readText())
        MutableList(array.length()) { WarningEvent.fromJson(array.getJSONObject(it)) }
    }.onFailure { log("event load failed: $it") }.getOrDefault(mutableListOf())

    private companion object {
        const val MAX_EVENTS = 100
    }
}
