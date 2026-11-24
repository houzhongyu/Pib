package com.houxinzhe.pib.tools

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.b
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.bool
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AmapException(message: String) : Exception(message)

data class Place(val name: String, val location: String, val address: String = "", val adcode: String = "", val city: String = "")

/** Thin client for 高德开放平台 Web 服务 API. Uses the user's own key (BYOK). */
class AmapClient(private val key: () -> String, private val http: OkHttpClient = defaultHttp) {
    private val json = Json { ignoreUnknownKeys = true }
    val hasKey: Boolean get() = key().trim().isNotEmpty()

    suspend fun get(path: String, params: Map<String, String?>): JsonObject = withContext(Dispatchers.IO) {
        val k = key().trim()
        if (k.isEmpty()) throw AmapException("还没有填写高德 Web 服务 Key，请到「设置」里填写。")
        val url = "https://restapi.amap.com$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("key", k)
            params.forEach { (n, v) -> if (!v.isNullOrBlank()) addQueryParameter(n, v) }
        }.build()
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw AmapException("高德接口 HTTP ${r.code}")
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            if (o.str("status") != "1") throw AmapException("高德返回错误：${o.str("info") ?: "未知"}（${o.str("infocode") ?: "-"}）")
            o
        }
    }

    suspend fun searchPlaces(keywords: String, city: String?, around: String? = null, limit: Int = 5): List<Place> {
        val o = if (around != null) get("/v5/place/around", mapOf("keywords" to keywords, "location" to around, "radius" to "3000", "sortrule" to "distance", "page_size" to "$limit"))
        else get("/v5/place/text", mapOf("keywords" to keywords, "region" to city, "page_size" to "$limit"))
        return o["pois"].arr().map { p ->
            val po = p.jsonObject
            Place(po.str("name").orEmpty(), po.str("location").orEmpty(), po.str("address").orEmpty(), po.str("adcode").orEmpty(), po.str("cityname").orEmpty())
        }.filter { it.location.contains(',') }
    }

    suspend fun geocode(address: String, city: String?): Place? {
        val g = get("/v3/geocode/geo", mapOf("address" to address, "city" to city))["geocodes"].arr().firstOrNull()?.jsonObject ?: return null
        return Place(address, g.str("location").orEmpty(), g.str("formatted_address").orEmpty(), g.str("adcode").orEmpty(), g.str("city").orEmpty())
    }

    suspend fun regeo(location: String): Place {
        val r = get("/v3/geocode/regeo", mapOf("location" to location))["regeocode"]!!.jsonObject
        val comp = r["addressComponent"]?.jsonObject
        return Place("当前位置", location, r.str("formatted_address").orEmpty(), comp?.str("adcode").orEmpty(), comp?.str("city") ?: comp?.str("province").orEmpty())
    }

    suspend fun gpsToGcj(lng: Double, lat: Double): String =
        get("/v3/assistant/coordinate/convert", mapOf("locations" to "%.6f,%.6f".format(lng, lat), "coordsys" to "gps")).str("locations")!!.split(';').first()

    /** Resolve free text ("我的位置" / POI name / address) to a place. */
    suspend fun resolve(text: String, city: String?, here: (suspend () -> String?)?): Place {
        if (text.trim() in HERE_WORDS) {
            val loc = here?.invoke() ?: throw AmapException("拿不到当前位置，请打开定位或直接告诉我出发地。")
            return regeo(loc)
        }
        searchPlaces(text, city, limit = 1).firstOrNull()?.let { return it }
        geocode(text, city)?.takeIf { it.location.isNotBlank() }?.let { return it }
        throw AmapException("在高德上没找到「$text」，换个说法试试。")
    }

    companion object {
        val HERE_WORDS = setOf("我的位置", "当前位置", "这里", "我这", "我这里", "现在的位置", "当前定位")
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "[]" }
internal fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

/** Device location → 高德 GCJ-02 "lng,lat". */
object DeviceLocation {
    @SuppressLint("MissingPermission")
    suspend fun gcj(ctx: ToolContext, amap: AmapClient): String? {
        val lm = ctx.app.getSystemService(LocationManager::class.java) ?: return null
        val best: Location = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return amap.gpsToGcj(best.longitude, best.latitude)
    }
}

internal val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun fmtDistance(m: Int) = if (m >= 1000) "%.1f 公里".format(m / 1000.0) else "$m 米"
private fun fmtDuration(s: Int): String { val min = (s + 59) / 60; return if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分钟" }

class PlaceSearchTool(private val amap: AmapClient) : Tool {
    override val name = "place_search"
    override val title = "高德搜地点"
    override val risk = Risk.READ
    override val description = "用高德地图搜索地点、商户、车站等，返回名称、地址和坐标。near_me=true 时搜附近（需要定位）。"
    override val parameters = schema(
        str("keywords", "关键词，例如：北京南站、附近的咖啡", required = true),
        str("city", "城市名，可省略"),
        bool("near_me", "是否只搜我附近"),
    )
    override fun permissionsFor(args: JsonObject) = if (amap.hasKey && args.b("near_me") == true) LOCATION else emptyList()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val kw = args.s("keywords") ?: return ToolResult.fail("缺少关键词")
            if (ctx.settings.amapKey.isBlank()) {
                return ToolResult.fail("尚未配置高德 Web 服务 Key，无法在对话内读取地点列表；你可以改调 app_handoff(target=\"amap_search\", query=\"$kw\") 直接打开高德地图搜索，或让用户去设置填写高德 Key。")
            }
            val around = if (args.b("near_me") == true) DeviceLocation.gcj(ctx, amap) ?: return ToolResult.fail("拿不到当前位置，请开启定位权限") else null
            val list = amap.searchPlaces(kw, args.s("city") ?: ctx.settings.homeCity.ifBlank { null }, around)
            if (list.isEmpty()) ToolResult.ok("没有找到「$kw」相关地点。")
            else ToolResult.ok(list.joinToString("\n") { "• ${it.name}｜${it.city}${it.address}｜坐标 ${it.location}" })
        } catch (e: AmapException) { ToolResult.fail(e.message ?: "高德查询失败") }
    }
}

class RoutePlanTool(private val amap: AmapClient) : Tool {
    override val name = "route_plan"
    override val title = "高德查路线"
    override val risk = Risk.READ
    override fun permissionsFor(args: JsonObject) =
        if (amap.hasKey && listOf(args.s("origin") ?: "我的位置", args.s("destination").orEmpty()).any { it in AmapClient.HERE_WORDS }) LOCATION else emptyList()
    override val description = "用高德查两地之间的路线、距离、耗时。driving 会附带打车费估算；transit 是公交地铁方案。起点可以写「我的位置」。"
    override val parameters = schema(
        str("origin", "起点：地点名、地址或「我的位置」", required = true),
        str("destination", "终点：地点名或地址", required = true),
        str("mode", "出行方式", enum = listOf("driving", "transit", "walking")),
        str("city", "所在城市，公交方案需要"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val dst = args.s("destination") ?: return ToolResult.fail("缺少终点")
            if (!amap.hasKey) {
                return ToolResult.fail("尚未配置高德 Web 服务 Key，无法在对话内计算距离与打车价；你可以改调 app_handoff(target=\"amap_route\", destination=\"$dst\") 直接打开高德地图查看路线与打车估价，或让用户去设置填写高德 Key。")
            }
            val city = args.s("city") ?: ctx.settings.homeCity.ifBlank { null }
            val here: suspend () -> String? = { DeviceLocation.gcj(ctx, amap) }
            val o = amap.resolve(args.s("origin") ?: "我的位置", city, here)
            val d = amap.resolve(dst, city, here)
            val head = "从「${o.name}」到「${d.name}」（${d.address}）"
            when (args.s("mode") ?: "driving") {
                "transit" -> {
                    val r = amap.get("/v3/direction/transit/integrated", mapOf("origin" to o.location, "destination" to d.location, "city" to (o.city.ifBlank { city ?: "" }), "cityd" to d.city.ifBlank { null }))["route"]!!.jsonObject
                    val plans = r["transits"].arr().take(3).mapIndexed { i, t ->
                        val to = t.jsonObject
                        val lines = to["segments"].arr().mapNotNull { seg ->
                            val so = seg.jsonObject
                            so["bus"]?.jsonObject?.get("buslines").arr().firstOrNull()?.jsonObject?.str("name")?.substringBefore('(')
                                ?: so["railway"]?.jsonObject?.str("name")
                        }
                        "${i + 1}. ${lines.joinToString(" → ").ifBlank { "步行" }}，约 ${fmtDuration(to.str("duration")?.toIntOrNull() ?: 0)}，步行 ${fmtDistance(to.str("walking_distance")?.toIntOrNull() ?: 0)}" +
                            (to.str("cost")?.let { "，票价约 $it 元" } ?: "")
                    }
                    ToolResult.ok(if (plans.isEmpty()) "$head 没有找到公交方案。" else "$head 公交方案：\n" + plans.joinToString("\n"))
                }
                "walking" -> {
                    val p = amap.get("/v3/direction/walking", mapOf("origin" to o.location, "destination" to d.location))["route"]!!.jsonObject["paths"].arr().first().jsonObject
                    ToolResult.ok("$head 步行 ${fmtDistance(p.str("distance")!!.toInt())}，约 ${fmtDuration(p.str("duration")!!.toInt())}。")
                }
                else -> {
                    val r = amap.get("/v3/direction/driving", mapOf("origin" to o.location, "destination" to d.location, "extensions" to "base", "strategy" to "0"))["route"]!!.jsonObject
                    val p = r["paths"].arr().first().jsonObject
                    val taxi = r.str("taxi_cost")?.toDoubleOrNull()?.let { "，打车约 %.0f 元（高德估算）".format(it) } ?: ""
                    ToolResult.ok("$head 驾车 ${fmtDistance(p.str("distance")!!.toInt())}，约 ${fmtDuration(p.str("duration")!!.toInt())}$taxi。终点坐标 ${d.location}。")
                }
            }
        } catch (e: AmapException) { ToolResult.fail(e.message ?: "高德查询失败") }
    }

    override fun preview(args: JsonObject) = "查路线 ${args.s("origin")} → ${args.s("destination")}"
}

class WeatherTool(private val amap: AmapClient, private val http: OkHttpClient = AmapClient.defaultHttp) : Tool {
    override val name = "weather"
    override val title = "查天气"
    override val risk = Risk.READ
    override val description = "查某个城市或区县的实时天气和未来几天预报。填了高德 Key 时优先用高德，未填时自动用免 Key 公开气象源。"
    override val parameters = schema(str("city", "城市或区县名，例如：杭州、西湖区。省略则用常住城市"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val cityName = args.s("city") ?: ctx.settings.homeCity.ifBlank { null } ?: return ToolResult.fail("不知道查哪个城市，请告诉我城市名")
        if (amap.hasKey) {
            try {
                val adcode = amap.geocode(cityName, null)?.adcode?.takeIf { it.isNotBlank() }
                if (adcode != null) {
                    val live = amap.get("/v3/weather/weatherInfo", mapOf("city" to adcode, "extensions" to "base"))["lives"].arr().firstOrNull()?.jsonObject
                    val fc = amap.get("/v3/weather/weatherInfo", mapOf("city" to adcode, "extensions" to "all"))["forecasts"].arr().firstOrNull()?.jsonObject
                    val now = live?.let { "${it.str("city")} 现在 ${it.str("weather")}，${it.str("temperature")}°C，${it.str("winddirection")}风 ${it.str("windpower")} 级，湿度 ${it.str("humidity")}%（高德 ${it.str("reporttime")} 发布）" }
                    val days = fc?.get("casts").arr().joinToString("\n") {
                        val c = it.jsonObject
                        "• ${c.str("date")} 白天${c.str("dayweather")} 夜间${c.str("nightweather")} ${c.str("nighttemp")}~${c.str("daytemp")}°C"
                    }
                    return ToolResult.ok(listOfNotNull(now, days.ifBlank { null }).joinToString("\n"))
                }
            } catch (_: AmapException) {
                // Fall through to keyless weather source so the user still gets an answer.
            }
        }
        return OpenMeteoWeather.lookup(cityName, http)
    }
}

/** Keyless weather fallback (Open-Meteo, directly reachable in mainland China) when Amap key is unset. */
object OpenMeteoWeather {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(cityName: String, http: OkHttpClient = AmapClient.defaultHttp): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val clean = cityName.trim().removeSuffix("市").removeSuffix("区").removeSuffix("县").ifBlank { cityName.trim() }
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", clean)
                .addQueryParameter("count", "3")
                .addQueryParameter("language", "zh")
                .build()
            val hit = http.newCall(Request.Builder().url(geoUrl).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext ToolResult.fail("气象地理编码 HTTP ${r.code}")
                json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["results"].arr().firstOrNull()?.jsonObject
            } ?: return@withContext ToolResult.fail("没有找到城市「$cityName」，请换个常见城市名或填写高德 Key。")
            val lat = hit["latitude"]!!.jsonPrimitive.content
            val lon = hit["longitude"]!!.jsonPrimitive.content
            val resolvedName = listOfNotNull(hit.str("admin1"), hit.str("name")).distinct().joinToString("")
            val wxUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", lat)
                .addQueryParameter("longitude", lon)
                .addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
                .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
                .addQueryParameter("timezone", "Asia/Shanghai")
                .addQueryParameter("forecast_days", "4")
                .build()
            http.newCall(Request.Builder().url(wxUrl).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext ToolResult.fail("气象接口 HTTP ${r.code}")
                formatForecast(resolvedName.ifBlank { cityName }, json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject)
            }
        }.getOrElse { ToolResult.fail("天气查询失败：${it.message}") }
    }

    internal fun formatForecast(city: String, root: JsonObject): ToolResult {
        val cur = root["current"]?.jsonObject
        val nowLine = cur?.let {
            val code = it["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val temp = it["temperature_2m"]?.jsonPrimitive?.contentOrNull ?: "-"
            val hum = it["relative_humidity_2m"]?.jsonPrimitive?.contentOrNull ?: "-"
            val wind = it["wind_speed_10m"]?.jsonPrimitive?.contentOrNull ?: "-"
            "$city 现在 ${wmoText(code)}，${temp}°C，湿度 ${hum}%，风速 ${wind} km/h（公开气象源）"
        }
        val daily = root["daily"]?.jsonObject
        val dates = daily?.get("time").arr()
        val codes = daily?.get("weather_code").arr()
        val maxs = daily?.get("temperature_2m_max").arr()
        val mins = daily?.get("temperature_2m_min").arr()
        val days = (0 until dates.size).joinToString("\n") { i ->
            val d = dates[i].jsonPrimitive.content
            val w = wmoText(codes.getOrNull(i)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
            "• $d $w ${mins.getOrNull(i)?.jsonPrimitive?.contentOrNull ?: "-"}~${maxs.getOrNull(i)?.jsonPrimitive?.contentOrNull ?: "-"}°C"
        }
        return ToolResult.ok(listOfNotNull(nowLine, days.ifBlank { null }).joinToString("\n"))
    }

    fun wmoText(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63 -> "小到中雨"
        65, 66, 67 -> "大雨"
        71, 73 -> "小到中雪"
        75, 77 -> "大雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷阵雨"
        else -> "多云"
    }
}
