package app.cardam.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleColor
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.cardam.log

/**
 * Reads every signal the warning rules need: the Pleos Vehicle SDK for warning lights, brake, steering, odometer,
 * remaining range and VIN, and the AAOS Car API for gear, speed and outside temperature (as Pleos recommends for
 * standard data).
 */
class VehicleMonitor(
    private val context: Context,
    private val state: VehicleState,
    private val listener: VehicleListener,
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedRegistered = false

    fun start() {
        startPleos()
        startCarApi()
    }

    fun stop() {
        properties?.unregisterCallback(propertyCallback)
        uxManager?.unregisterListener()
        car?.disconnect()
        if (::vehicle.isInitialized) vehicle.release()
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK
    private fun startPleos() {
        vehicle = Vehicle(context)
        vehicle.initialize()
        // Registering reports the lights lit right now first, then every change (only lit lights are listed).
        vehicle.getSafety().registerTelltales(telltaleListener)
        vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                state.brakePressed = status == true
            }

            override fun onFailed(e: Exception) = log("brake pedal unavailable: $e")
        })
        vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                state.steeringDeg = angle ?: return
            }

            override fun onFailed(e: Exception) = log("steering angle unavailable: $e")
        })
        val odometer = vehicle.getOdometer()
        odometer.registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) {
                state.odometerKm = km
            }

            override fun onFailed(e: Exception) = log("odometer unavailable: $e")
        })
        odometer.registerRangeRemainingListener(object : RangeRemainingListener {
            // Reported in meters (RANGE_REMAINING): 412 km on the Virtual Console arrives as 412000.
            override fun onRangeRemainingChanged(meters: Float?) {
                state.rangeKm = meters?.div(1000f)
            }

            override fun onFailed(e: Exception) = log("remaining range unavailable: $e")
        })
        vehicle.getCarInfo().getVINInfo(
            // The emulator answers "\"invalid\"", which is not a VIN.
            onComplete = { vin -> state.vin = vin?.trim('"', ' ')?.takeIf { it.length == VIN_LENGTH } },
            onFailed = { log("VIN unavailable: $it") }
        )
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            val lights = telltales?.telltales.orEmpty()
                .filter { it.state != TelltaleState.OFF }
                .map { Light(it.type.name, it.state == TelltaleState.BLINK, colorName(it.color)) }
            state.lights = lights.associateBy { it.type }
            main.post { listener.onLights(lights) }
        }

        override fun onFailed(e: Exception) = log("warning lights unavailable: $e")
    }

    /**
     * TelltaleData carries the color as an int. The SDK's own lookup is internal; in Vehicle 2.3.2 the ids equal the
     * enum order (WHITE 0, GREEN 1, BLUE 2, YELLOW 3, RED 4, GRAY 5), confirmed on the emulator (yellow 3, red 4).
     */
    private fun colorName(id: Int) = TelltaleColor.entries.getOrNull(id)?.name ?: "UNKNOWN"

    // ---------------------------------------------------------------- AAOS Car API
    private fun startCarApi() {
        val connected = Car.createCar(context)
        car = connected
        val props = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        properties = props
        register(props, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        register(props, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_NORMAL)
        registerSpeed()

        val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxManager = ux
        state.drivingRestricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
        ux.registerListener { restrictions ->
            state.drivingRestricted = restrictions.isRequiresDistractionOptimization
            log("ux restricted=${state.drivingRestricted}")
        }
    }

    /** Speed needs the runtime CAR_SPEED permission; MainActivity calls this again after it is granted. */
    fun registerSpeed() {
        if (speedRegistered) return
        val props = properties ?: return
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            log("CAR_SPEED not granted yet")
            return
        }
        speedRegistered = register(props, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    private fun register(props: CarPropertyManager, id: Int, rate: Float): Boolean = try {
        props.registerCallback(propertyCallback, id, rate).also { log("register ${VehiclePropertyIds.toString(id)} ok=$it") }
    } catch (e: Exception) {
        log("register ${VehiclePropertyIds.toString(id)} failed: $e")
        false
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val now = SystemClock.elapsedRealtime()
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val kmh = (value.value as Float) * 3.6f
                    state.speedKmh = kmh
                    main.post { listener.onSpeed(kmh, now) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = gearOf(value.value as Int)
                    if (gear != state.gear) {
                        state.gear = gear
                        log("gear=$gear")
                        main.post { listener.onGearChanged(gear) }
                    }
                }
                VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> state.outsideTempC = value.value as Float
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) =
            log("property error ${VehiclePropertyIds.toString(propertyId)} area=$areaId")
    }

    private fun gearOf(value: Int) = when (value) {
        GEAR_PARK -> Gear.PARK
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        GEAR_DRIVE -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private companion object {
        const val VIN_LENGTH = 17

        // VehicleGear values used by GEAR_SELECTION.
        const val GEAR_NEUTRAL = 1
        const val GEAR_REVERSE = 2
        const val GEAR_PARK = 4
        const val GEAR_DRIVE = 8
    }
}
