package app.cardam.data

import android.content.Context
import org.json.JSONObject

/** [kind] is "블루핸즈", "오토큐" or "정비소". */
data class Shop(val name: String, val kind: String, val address: String, val lat: Double, val lng: Double) {
    /** The owner's manual sends drivers to Hyundai's own service centers or Bluehands shops. */
    val official: Boolean get() = kind == "블루핸즈" || name.contains("하이테크센터")
}

/**
 * Named car repair shops in Korea from OpenStreetMap (shop=car_repair, © OpenStreetMap contributors, ODbL),
 * bundled as an asset by `tools/shops/build_shops.py` in the research repo.
 */
class ShopRepository(private val context: Context) {

    val items: List<Shop> by lazy {
        val json = runCatching { context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrNull() ?: return@lazy emptyList()
        val array = JSONObject(json).getJSONArray("items")
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Shop(o.getString("name"), o.getString("kind"), o.getString("address"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }

    /**
     * The shop to suggest: the nearest Bluehands or Hyundai service center if it is not much farther than the
     * nearest shop of any kind (the manual's advice), otherwise the nearest shop.
     */
    fun nearest(lat: Double, lng: Double): Pair<Shop, Double>? {
        val ranked = items.map { it to distanceKm(lat, lng, it.lat, it.lng) }
        val any = ranked.minByOrNull { it.second } ?: return null
        val official = ranked.filter { it.first.official }.minByOrNull { it.second }
        return if (official != null && official.second <= any.second + OFFICIAL_DETOUR_KM) official else any
    }

    private companion object {
        const val ASSET = "shops/repair_shops.json"

        /** How much farther an official shop may be than the nearest one and still be preferred (prototype default). */
        const val OFFICIAL_DETOUR_KM = 3.0
    }
}
