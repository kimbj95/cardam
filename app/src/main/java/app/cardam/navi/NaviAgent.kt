package app.cardam.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.cardam.log

enum class StopResult { ADDED_AS_WAYPOINT, ROUTED_AS_DESTINATION, WAYPOINTS_FULL, NOT_CONNECTED }

/**
 * Talks to the Pleos navigation app through NaviHelper. Places are sent as coordinates only: the emulator test
 * showed that a route or waypoint without a POI id works, and the navigation app fills in the address.
 */
class NaviAgent(context: Context) {
    private val helper = NaviHelper(context)
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var pendingLocation: (Pair<Double, Double>?) -> Unit = {}
    private var pendingWaypoints: ((Int) -> Unit)? = null

    @Volatile var status: NavigationStatus? = null
        private set
    @Volatile var lastLocation: Pair<Double, Double>? = null
        private set

    fun start() {
        helper.initialize(
            onConnected = {
                main.post {
                    connected = true
                    log("navi connected")
                    helper.getCurrentLocationInfo()
                }
            },
            onFailed = { log("navi initialize failed: $it") }
        )
        helper.addListener(listener)
    }

    fun release() {
        helper.removeListener(listener)
        helper.release()
    }

    /** Current position from the navigation app; falls back to the last known one after two seconds. */
    fun requestLocation(onResult: (Pair<Double, Double>?) -> Unit) {
        if (!connected) {
            onResult(lastLocation)
            return
        }
        pendingLocation = onResult
        helper.getCurrentLocationInfo()
        main.postDelayed({ deliverLocation(lastLocation) }, 2_000L)
    }

    /** Adds [name] as the next stop: a waypoint while guiding (at most two), otherwise the destination. */
    fun addStop(name: String, lat: Double, lng: Double, onResult: (StopResult) -> Unit) {
        if (!connected) {
            onResult(StopResult.NOT_CONNECTED)
            return
        }
        if (status != NavigationStatus.ROUTE_DRIVE) {
            log("navi route to $name ($lat, $lng)")
            helper.requestRoute(RouteInfo(latitude = lat, longitude = lng, poiName = name, poiId = "", address = "", poiSubId = ""))
            onResult(StopResult.ROUTED_AS_DESTINATION)
            return
        }
        pendingWaypoints = { count ->
            if (count >= MAX_WAYPOINTS) {
                onResult(StopResult.WAYPOINTS_FULL)
            } else {
                log("navi waypoint $name ($lat, $lng)")
                helper.addWaypoint(
                    RequestWaypointInfo(
                        latitude = lat, longitude = lng, poiName = name, waypointIndex = WaypointIndex.FIRST,
                        poiId = "", address = "", poiSubId = ""
                    )
                )
                onResult(StopResult.ADDED_AS_WAYPOINT)
            }
        }
        helper.getWaypointInfo()
    }

    fun cancelRoute() {
        if (connected) helper.cancelRoute()
    }

    private fun deliverLocation(location: Pair<Double, Double>?) {
        val callback = pendingLocation
        pendingLocation = {}
        callback(location)
    }

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            this@NaviAgent.status = status
            log("navi status $status")
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val location = currentLocationInfo.latitude to currentLocationInfo.longitude
            lastLocation = location
            main.post { deliverLocation(location) }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            main.post {
                val callback = pendingWaypoints ?: return@post
                pendingWaypoints = null
                callback(waypointInfoList.size)
            }
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            log("navi waypoint changed $waypointChangedInfo")

        override fun onError(errorCode: NaviErrorCode) = log("navi error $errorCode")
    }

    private companion object {
        /** NaviHelper offers WaypointIndex FIRST and SECOND only. */
        const val MAX_WAYPOINTS = 2
    }
}
