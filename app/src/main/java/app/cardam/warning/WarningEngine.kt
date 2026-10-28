package app.cardam.warning

import ai.pleos.playground.navi.constants.NavigationStatus
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.cardam.data.Level
import app.cardam.data.Next
import app.cardam.data.ShopRepository
import app.cardam.data.TelltaleBook
import app.cardam.data.objectParticle
import app.cardam.data.subjectParticle
import app.cardam.log
import app.cardam.navi.NaviAgent
import app.cardam.navi.StopResult
import app.cardam.vehicle.Gear
import app.cardam.vehicle.Light
import app.cardam.vehicle.VehicleListener
import app.cardam.vehicle.VehicleState
import app.cardam.voice.Answer
import app.cardam.voice.VoiceAgent
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The warning-light rules. A light that stays on for [CONFIRM_MS] becomes an event: the car's state at the moment it
 * came on is recorded (with the 30 seconds before and after), the app says what it means in one or two sentences,
 * and offers the next step (the nearest repair shop) for a light that needs a check. A light that means "stop now"
 * gets no question while driving; the app explains again once the car is in P. Everything runs on the main thread,
 * driven by vehicle events and a half-second tick.
 */
class WarningEngine(
    private val context: Context,
    private val state: VehicleState,
    private val voice: VoiceAgent,
    private val navi: NaviAgent,
    private val store: EventStore,
    val book: TelltaleBook,
    private val shops: ShopRepository,
    val demo: DemoSettings,
    /** Shows a one-line status in the service notification, e.g. "타이어 공기압 경고등 켜짐". */
    private val onStatus: (String) -> Unit = {},
) : VehicleListener {

    private class Reading(val at: Long, val speedKmh: Float, val brake: Boolean, val steeringDeg: Float)

    /** A light seen on but not yet confirmed; its event is recorded from the moment it was seen. */
    private class Pending(val seenAt: Long, val event: WarningEvent)

    private val main = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<Reading>()
    private val pending = mutableMapOf<String, Pending>()

    /** Events still collecting the 30 seconds after they came on, with the elapsed time they came on. */
    private val recording = mutableMapOf<WarningEvent, Long>()
    private var firstReportSeen = false

    /** A "stop now" light announced while driving, explained again once the car is in P. */
    private var explainWhenParked: WarningEvent? = null
    private var lastStatus = ""

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, SAMPLE_MS)
        }
    }

    fun start() {
        main.post(ticker)
        log("warning engine started, ${store.events.size} events on record")
    }

    fun stop() {
        main.removeCallbacks(ticker)
        store.save()
    }

    // ---------------------------------------------------------------- tick
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        recent.addLast(Reading(now, state.speedKmh, state.brakePressed, state.steeringDeg))
        while (recent.isNotEmpty() && now - recent.first().at > WINDOW_MS) recent.removeFirst()

        pending.values.filter { now - it.seenAt >= CONFIRM_MS }.forEach(::confirm)

        val done = mutableListOf<WarningEvent>()
        for ((event, onset) in recording) {
            val t = now - onset
            event.samples.add(Sample(t.toInt(), state.speedKmh, state.brakePressed, state.steeringDeg))
            if (t >= WINDOW_MS) done.add(event)
        }
        if (done.isNotEmpty()) {
            done.forEach { recording.remove(it) }
            store.save()
        }
        publishStatus()
    }

    private fun publishStatus() {
        val lit = store.openEvents()
        val status = when (lit.size) {
            0 -> "켜진 경고등 없음"
            1 -> "${lit[0].name} 켜짐"
            else -> "경고등 ${lit.size}개 켜짐"
        }
        if (status == lastStatus) return
        lastStatus = status
        onStatus(status)
    }

    // ---------------------------------------------------------------- lights
    override fun onLights(lights: List<Light>) {
        val moving = state.moving()
        val all = lights.associateBy { it.type }
        val warnings = lights.filter { book.levelOf(it, moving) != Level.INFO }
        if (!firstReportSeen) {
            firstReportSeen = true
            onFirstReport(all, warnings)
            return
        }
        // Off: the light is gone from the list (a warning that only turned into a plain indicator stays open).
        store.openEvents().filter { it.type !in all }.forEach(::close)
        // A flash shorter than CONFIRM_MS is not a warning: at ignition every light is lit for 3 to 6 seconds.
        pending.keys.filter { it !in all }.forEach { type ->
            log("ignored a short flash of $type")
            pending.remove(type)?.let { recording.remove(it.event) }
        }
        warnings.filter { store.openEvent(it.type) == null && it.type !in pending }.forEach { begin(it, missed = false) }
    }

    /**
     * The first report after the service starts is what the app found, not a change. Lights on record as lit that
     * are off now went off while the app was not running; warnings lit now with no record came on while it was not.
     */
    private fun onFirstReport(all: Map<String, Light>, warnings: List<Light>) {
        for (event in store.openEvents().filter { it.type !in all }) {
            event.offAt = System.currentTimeMillis()
            event.offUnseen = true
            log("${event.type} went off while the app was not running")
        }
        store.save()
        warnings.filter { store.openEvent(it.type) == null }.forEach { begin(it, missed = true) }
        log("first report: ${all.keys}, warnings ${warnings.map { it.type }}")
    }

    private fun begin(light: Light, missed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val telltale = book.of(light.type, light.color)
        val location = navi.lastLocation
        val event = WarningEvent(
            id = System.currentTimeMillis(),
            type = light.type,
            name = telltale.name,
            level = book.levelOf(light, state.moving()),
            color = light.color,
            blinking = light.blinking,
            onAt = System.currentTimeMillis(),
            missed = missed,
            speedKmh = state.speedKmh,
            gear = state.gear.name,
            outsideTempC = outsideTempC(),
            odometerKm = state.odometerKm,
            rangeKm = state.rangeKm,
            minutesDriven = minutesDriven(),
            lat = location?.first,
            lng = location?.second,
            others = state.lights.values.filter { it.type != light.type && book.levelOf(it, state.moving()) != Level.INFO }
                .map { book.of(it.type, it.color).name },
            // Readings from before the app knew about the light would not show the moment it came on.
            samples = if (missed) mutableListOf() else recent.map { Sample((it.at - now).toInt(), it.speedKmh, it.brake, it.steeringDeg) }.toMutableList(),
        )
        pending[light.type] = Pending(now, event)
        if (!missed) recording[event] = now
        log("light on ${light.type} ${light.color}${if (light.blinking) " blinking" else ""} level=${event.level} missed=$missed")
    }

    private fun confirm(p: Pending) {
        pending.remove(p.event.type)
        store.add(p.event)
        log("warning confirmed: ${p.event.type} at ${p.event.speedKmh.roundToInt()} km/h, ${p.event.outsideTempC}℃")
        announce(p.event)
    }

    private fun close(event: WarningEvent) {
        event.offAt = System.currentTimeMillis()
        event.offOdometerKm = state.odometerKm
        store.save()
        val minutes = event.durationMinutes() ?: 0
        log("light off ${event.type} after $minutes min")
        if (!state.moving()) return
        val name = event.name + subjectParticle(event.name)
        voice.say(if (minutes >= 1) "$name 꺼졌어요. ${minutes}분 동안 켜져 있었고, 기록해 뒀어요." else "$name 꺼졌어요. 기록해 뒀어요.")
    }

    // ---------------------------------------------------------------- speaking
    private fun announce(event: WarningEvent) {
        val telltale = book.of(event.type, event.color)
        val times = store.history(event.type, System.currentTimeMillis() - Patterns.WINDOW_DAYS * DAY_MS).size
        val repeat = if (times >= 2) " 이 경고등은 최근 ${Patterns.WINDOW_DAYS}일 동안 ${ordinal(times)}예요." else ""
        val name = telltale.name + subjectParticle(telltale.name)
        if (state.moving()) {
            val head = if (event.missed) "앱이 꺼져 있던 동안 켜진 경고등이 있어요. " else ""
            voice.say(head + telltale.drive + repeat, urgent = true)
            when {
                event.level == Level.STOP -> explainWhenParked = event
                telltale.next == Next.SHOP -> offerShop()
            }
        } else {
            voice.say("$name 켜져 있어요. 정차 화면에서 뜻과 대처 방법을 볼 수 있어요.$repeat")
            if (telltale.next == Next.SHOP) offerShop()
        }
    }

    /** Offers the nearest repair shop: a waypoint while the navigation is guiding, otherwise the destination. */
    private fun offerShop() {
        val guiding = navi.status == NavigationStatus.ROUTE_DRIVE
        voice.askYesNo(if (guiding) "가까운 정비소를 경유지로 넣을까요?" else "가까운 정비소로 길안내할까요?") { answer ->
            log("shop answer $answer")
            when (answer) {
                Answer.YES -> addNearestShop()
                Answer.NO -> voice.say("알겠어요. 정차하면 화면에서 다시 볼 수 있어요.")
                Answer.UNKNOWN -> voice.say("정차하면 화면에서 다시 볼 수 있어요.")
            }
        }
    }

    fun addNearestShop() {
        navi.requestLocation { location ->
            if (location == null) {
                voice.say("지금 위치를 알 수 없어서 정비소를 찾지 못했어요.")
                return@requestLocation
            }
            val found = shops.nearest(location.first, location.second)
            if (found == null) {
                voice.say("가까운 정비소를 찾지 못했어요.")
                return@requestLocation
            }
            val (shop, km) = found
            log("nearest shop ${shop.name} ${"%.1f".format(km)}km (${shop.address})")
            navi.addStop(shop.name, shop.lat, shop.lng) { result ->
                log("add stop $result")
                val target = shop.name + objectParticle(shop.name)
                val distance = if (km < 1) "1킬로미터가 안 되는" else "약 ${km.roundToInt()}킬로미터"
                voice.say(
                    when (result) {
                        StopResult.ADDED_AS_WAYPOINT -> "$target 경유지로 넣었어요. $distance 거리예요."
                        StopResult.ROUTED_AS_DESTINATION -> "$target 안내할게요. $distance 거리예요."
                        StopResult.WAYPOINTS_FULL -> "경유지가 이미 두 곳이라 더 넣을 수 없어요. 정차한 뒤 내비에서 바꿔 주세요."
                        StopResult.NOT_CONNECTED -> "내비게이션과 연결되지 않아 정비소를 넣지 못했어요."
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------- driving
    override fun onGearChanged(gear: Gear) {
        if (gear == Gear.PARK) {
            store.driveStartedAt = 0
            explainWhenParked?.let { event ->
                explainWhenParked = null
                main.postDelayed({
                    if (state.parked()) voice.say("차를 세웠어요. ${event.name} 대처 방법과 켜진 순간의 기록은 화면에서 볼 수 있어요.")
                }, PARKED_SETTLE_MS)
            }
        } else if (store.driveStartedAt == 0L) {
            store.driveStartedAt = System.currentTimeMillis()
        }
        // Some lights are warnings only while moving (the brake light also shows the parking brake in P).
        if (firstReportSeen) onLights(state.lights.values.toList())
    }

    private fun minutesDriven(): Int? =
        store.driveStartedAt.takeIf { it > 0 && state.moving() }?.let { ((System.currentTimeMillis() - it) / 60_000).toInt() }

    fun outsideTempC(): Float? = demo.fakeOutsideTempC.takeUnless { it.isNaN() } ?: state.outsideTempC

    /** "두 번째", "세 번째", ... */
    private fun ordinal(n: Int) = when (n) {
        2 -> "두 번째"
        3 -> "세 번째"
        4 -> "네 번째"
        5 -> "다섯 번째"
        else -> "${n}번째"
    }

    // ---------------------------------------------------------------- demo and tests
    /** Commands from the demo panel or adb: `seed:TPMS`, `outside:-3`, `answer:ne`, `drive:6`, `clear`, `say:...`, ... */
    fun onDemoCommand(command: String) {
        log("demo command: $command")
        val name = command.substringBefore(':')
        val arg = command.substringAfter(':', "")
        when (name) {
            "outside" -> demo.fakeOutsideTempC = arg.toFloatOrNull() ?: Float.NaN
            "answer" -> voice.injectedAnswer = arg.takeIf { it.isNotEmpty() && it != "off" }?.let(::answerAudio)
            // Two earlier cold-morning events of one light, so the record shows a pattern (the video says so).
            "seed" -> seed(arg.ifEmpty { "TPMS" })
            "clear" -> {
                store.clear()
                pending.clear()
                recording.clear()
                firstReportSeen = false
                onLights(state.lights.values.toList())
            }
            // Pretends the car left P this many minutes ago, for "출발 6분" without driving six minutes.
            "drive" -> arg.toLongOrNull()?.let { store.driveStartedAt = System.currentTimeMillis() - it * 60_000 }
            "shop" -> addNearestShop()
            "route" -> arg.split(",").takeIf { it.size >= 3 }?.let { p ->
                navi.addStop(p[2], p[0].toDouble(), p[1].toDouble()) { log("demo route $it") }
            }
            "cancelroute" -> navi.cancelRoute()
            // Says one line in the app's voice: the demo video records its lines again while the car is quiet.
            "say" -> voice.say(arg)
            "status" -> log(statusLine())
        }
        store.save()
    }

    private fun seed(type: String) {
        val telltale = book.of(type)
        val odometer = state.odometerKm?.takeIf { it > 0 } ?: 0f
        val now = System.currentTimeMillis()
        listOf(Triple(9, -3f, 14), Triple(4, -1f, 11)).forEachIndexed { i, (daysAgo, temp, minutes) ->
            val onAt = morning(now - daysAgo * DAY_MS, 7, 40 + i * 12)
            store.add(
                WarningEvent(
                    id = onAt, type = type, name = telltale.name, level = telltale.level, color = "YELLOW", blinking = false,
                    onAt = onAt, missed = false, speedKmh = 54f + i * 7, gear = "DRIVE", outsideTempC = temp,
                    odometerKm = (odometer - (daysAgo * 38)).takeIf { it > 0 }, rangeKm = null, minutesDriven = 5 + i * 2,
                    lat = null, lng = null, others = emptyList(), offAt = onAt + minutes * 60_000L, demo = true,
                )
            )
        }
        log("seeded two earlier $type events")
    }

    /** [day]'s date at [hour]:[minute] local time. */
    private fun morning(day: Long, hour: Int, minute: Int): Long =
        Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault())
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant().toEpochMilli()

    private fun answerAudio(name: String): ByteArray? = runCatching {
        context.assets.open("demo/answer_$name.pcm").use { it.readBytes() }
    }.onFailure { log("no demo answer '$name'") }.getOrNull()

    /** One line for tests and the demo video's vehicle panel; lists have no spaces so `key=value` splits cleanly. */
    fun statusLine() =
        "gear=${state.gear} speed=${"%.0f".format(state.speedKmh)} lights=${state.lights.keys.joinToString(",")} " +
            "pending=${pending.keys.joinToString(",")} open=${store.openEvents().joinToString(",") { it.type }} " +
            "events=${store.events.size} outside=${outsideTempC()} driven=${minutesDriven() ?: -1} " +
            "odometer=${state.odometerKm} range=${state.rangeKm} vin=${state.vin} navi=${navi.status}"

    private companion object {
        const val SAMPLE_MS = 500L

        /** Readings kept before, and recorded after, the moment a light came on. */
        const val WINDOW_MS = 30_000L

        /**
         * A light must stay on this long to count. At ignition the warning lights come on for a bulb check and go
         * off after about 3 to 6 seconds (owner's manual, 4장 '경고등 및 표시등').
         */
        const val CONFIRM_MS = 7_000L
        const val PARKED_SETTLE_MS = 2_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
