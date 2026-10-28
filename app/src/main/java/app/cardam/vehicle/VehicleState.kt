package app.cardam.vehicle

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * One lit warning light as the Vehicle SDK reports it: [type] is the SDK's TelltaleType name (e.g. "TPMS"),
 * [color] its TelltaleColor name ("RED", "YELLOW", ...), [blinking] true for BLINK.
 */
data class Light(val type: String, val blinking: Boolean, val color: String)

/** Latest vehicle state. SDK callbacks write it; the warning engine reads it on the main thread. */
class VehicleState {
    @Volatile var speedKmh = 0f
    @Volatile var gear = Gear.UNKNOWN
    @Volatile var brakePressed = false
    @Volatile var steeringDeg = 0f
    @Volatile var outsideTempC: Float? = null
    @Volatile var odometerKm: Float? = null
    @Volatile var rangeKm: Float? = null
    @Volatile var vin: String? = null

    /** Warning lights lit now, by type. Empty until the first report after start. */
    @Volatile var lights: Map<String, Light> = emptyMap()

    /** True while the system restricts screens for driving (non-DO apps are blocked). */
    @Volatile var drivingRestricted = false

    fun parked() = gear == Gear.PARK

    fun moving() = gear != Gear.PARK && gear != Gear.UNKNOWN
}

/** Events the warning engine reacts to, all delivered on the main thread. */
interface VehicleListener {
    fun onGearChanged(gear: Gear) {}
    fun onSpeed(kmh: Float, timeMs: Long) {}

    /** Every report of the lit warning lights; the first one after start is the state the app found. */
    fun onLights(lights: List<Light>) {}
}
