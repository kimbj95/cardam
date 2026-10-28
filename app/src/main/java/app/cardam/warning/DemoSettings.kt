package app.cardam.warning

import android.content.Context

/**
 * Settings for the demo video and emulator tests, for what the Virtual Console cannot produce: the outside
 * temperature (the console has no control for it).
 */
class DemoSettings(context: Context) {
    private val prefs = context.getSharedPreferences("demo", Context.MODE_PRIVATE)

    /** Replaces the outside temperature sensor while set; NaN means use the real sensor. */
    var fakeOutsideTempC: Float = prefs.getFloat("fakeOutsideTempC", Float.NaN)
        set(value) {
            field = value
            prefs.edit().putFloat("fakeOutsideTempC", value).apply()
        }
}
