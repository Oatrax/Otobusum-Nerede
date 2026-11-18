package com.onur.otobusum.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SearchResult(
    val kind: Kind,
    val code: String,   // stop code (dkod) or line code (hkod)
    val title: String,  // line code for lines, stop name for stops
    val subtitle: String,
) {
    enum class Kind { STOP, LINE }
}

data class Arrival(val line: String, val destination: String, val eta: String)

data class StopArrivals(val arrivals: List<Arrival>, val notes: List<String>)

data class LineStop(val code: String, val order: Int, val name: String, val district: String)

data class LineDirection(val title: String, val stops: List<LineStop>)

data class Vehicle(
    val id: String, val lat: Double, val lon: Double, val routeCode: String,
    val dir: Char, val heading: String, val time: String, val nearStopCode: String,
)

data class GeoStop(val code: String, val order: Int, val name: String, val lat: Double, val lon: Double)

data class StopInfo(val name: String, val district: String, val direction: String, val lat: Double?, val lon: Double?)

/**
 * Talks to the same public endpoints used by iett.istanbul's station/route pages,
 * plus the open İBB SOAP service for stop metadata.
 */
object IettApi {
    private const val BASE = "https://iett.istanbul"
    private val tr = Locale("tr", "TR")

    private const val MAX_BODY_BYTES = 8L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false) // never follow an https -> http redirect
        .build()

    /** Reads a response body, refusing anything unreasonably large. */
    private fun okhttp3.Response.bodyCapped(): String {
        val b = body ?: return ""
        if (b.contentLength() > MAX_BODY_BYTES) throw IOException("Yanıt çok büyük")
        val src = b.source()
        if (src.request(MAX_BODY_BYTES + 1)) throw IOException("Yanıt çok büyük")
        return src.buffer.readString(b.contentType()?.charset() ?: Charsets.UTF_8)
    }

    private suspend fun get(path: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val url = "$BASE$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Otobusum")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.bodyCapped()
        }
    }

    // ---- Search -------------------------------------------------------------

    private fun normalize(s: String): String =
        s.trim().uppercase(tr).replace(Regex("[^A-Z0-9ÇĞİÖŞÜ\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    suspend fun search(query: String): List<SearchResult> {
        val key = normalize(query)
        if (key.isEmpty()) return emptyList()
        val compact = key.replace(" ", "")
        // Mirrors the website: server matches on the first word; "29M1"-style codes drop the last char.
        val requestKey = if (Regex("^\\d{1,3}[A-ZÇĞİÖŞÜ]{1,2}\\d$").matches(compact)) compact.dropLast(1)
        else key.split(" ").first()

        val json = JSONObject(get("/tr/RouteStation/GetSearchItems", mapOf("key" to requestKey, "langid" to "1")))
        val list = json.optJSONArray("list") ?: return emptyList()
        val words = key.split(" ")
        val out = ArrayList<SearchResult>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val path = o.optString("Path")
            val name = o.optString("Name")
            val stationCode = o.optLong("Stationcode", 0)
            val item = if (stationCode > 0 || path.startsWith("/StationDetail")) {
                val code = if (stationCode > 0) stationCode.toString()
                else Regex("dkod=(\\d+)").find(path)?.groupValues?.get(1) ?: continue
                // Name looks like "KADIKÖY - Kadıköy - RIHTIM1 PERON Yönü"
                val parts = name.split(" - ")
                val title = if (parts.size >= 3) parts.dropLast(2).joinToString(" - ") else parts.first()
                val sub = if (parts.size >= 3) parts.takeLast(2).joinToString(" • ") else parts.drop(1).joinToString(" • ")
                SearchResult(SearchResult.Kind.STOP, code, title, "$sub  #$code")
            } else {
                val code = o.optString("Code")
                SearchResult(SearchResult.Kind.LINE, code, code, name)
            }
            val hay = normalize(item.title + " " + item.subtitle + " " + name)
            if (words.all { hay.contains(it) }) out += item
        }
        // Exact line-code match first; then lines if the query looks like a line code ("500T", "E-3"), else stops.
        val lineish = !key.contains(' ') && compact.length <= 6 && compact.any { it.isDigit() }
        return out.sortedWith(compareBy(
            { !(it.kind == SearchResult.Kind.LINE && normalize(it.code).replace(" ", "") == compact) },
            { (it.kind == SearchResult.Kind.LINE) != lineish },
        ))
    }

    // ---- Stop arrivals -------------------------------------------------------

    suspend fun arrivals(stopCode: String): StopArrivals {
        val html = get("/tr/RouteStation/GetStationInfo", mapOf("dcode" to stopCode, "langid" to "1"))
        val doc = Jsoup.parse(html)
        val notes = doc.select(".departure-times-header p").map { it.text() }.filter { it.isNotBlank() }
        val arrivals = doc.select(".line-item .content").filterNot { it.hasClass("content-header") }.mapNotNull { c ->
            val line = c.selectFirst("span")?.text()?.trim().orEmpty()
            val p = c.selectFirst("p") ?: return@mapNotNull null
            val eta = p.select("b").text().trim()
            val dest = p.clone().also { it.select("b, small").remove() }.text().trim()
            if (line.isEmpty() && dest.isEmpty()) null else Arrival(line, dest, eta)
        }
        return StopArrivals(arrivals, notes)
    }

    // ---- Lines through a stop -----------------------------------------------

    suspend fun linesAtStop(stopCode: String): List<SearchResult> {
        val json = JSONObject(get("/tr/RouteStationAgent/GetRouteByStation",
            mapOf("dcode" to stopCode, "langid" to "1", "debug" to "0")))
        val doc = Jsoup.parse(json.optString("html"))
        return doc.select(".line-item a").mapNotNull { a ->
            val code = a.selectFirst("span")?.text()?.trim().orEmpty()
            if (code.isEmpty()) return@mapNotNull null
            SearchResult(SearchResult.Kind.LINE, code, code, a.selectFirst("p")?.text().orEmpty())
        }.distinctBy { it.code }
    }

    // ---- Line stops ----------------------------------------------------------

    suspend fun lineStops(lineCode: String): List<LineDirection> {
        val html = get("/tr/RouteStation/GetStationForRoute", mapOf("hatkod" to lineCode, "langid" to "1"))
        val doc = Jsoup.parse(html)
        val headers = doc.select(".line-pass-header")
        return headers.map { h ->
            val body = h.nextElementSibling()
            val stops = body?.select(".line-pass-item a")?.mapNotNull { a ->
                val code = Regex("dkod=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val district = a.selectFirst("p span")?.text()?.removePrefix("-")?.trim()?.trim('-', ' ').orEmpty()
                val pText = a.selectFirst("p")?.clone()?.also { it.select("span").remove() }?.text()?.trim().orEmpty()
                val m = Regex("^(\\d+)\\.\\s*(.*)$").find(pText)
                LineStop(code, m?.groupValues?.get(1)?.toIntOrNull() ?: 0, m?.groupValues?.get(2) ?: pText, district)
            }.orEmpty()
            LineDirection(h.text().trim(), stops)
        }.filter { it.stops.isNotEmpty() }
    }

    // ---- Open İBB SOAP services ---------------------------------------------------

    private suspend fun soap(url: String, method: String, args: String): String = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><$method xmlns="http://tempuri.org/">$args</$method></soap:Body></soap:Envelope>"""
        val req = Request.Builder()
            .url(url)
            .header("SOAPAction", "\"http://tempuri.org/$method\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.bodyCapped()
        }
    }

    private fun soapJsonResult(xml: String, method: String): org.json.JSONArray {
        val text = Regex("<${method}Result>(.*)</${method}Result>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return org.json.JSONArray()
        return org.json.JSONArray(org.jsoup.parser.Parser.unescapeEntities(text, false))
    }

    private fun safeLine(line: String) = line.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    /** Live bus positions for a line (İETT updates these every few seconds). */
    suspend fun vehicles(line: String): List<Vehicle> {
        val xml = soap("https://api.ibb.gov.tr/iett/FiloDurum/SeferGerceklesme.asmx", "GetHatOtoKonum_json",
            "<HatKodu>${safeLine(line)}</HatKodu>")
        val arr = soapJsonResult(xml, "GetHatOtoKonum_json")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val lat = o.optString("enlem").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("boylam").toDoubleOrNull() ?: return@mapNotNull null
            val route = o.optString("guzergahkodu")
            Vehicle(
                id = o.optString("kapino"), lat = lat, lon = lon, routeCode = route,
                dir = Regex("_([GD])_").find(route)?.groupValues?.get(1)?.first() ?: '?',
                heading = o.optString("yon"), time = o.optString("son_konum_zamani"),
                nearStopCode = o.optString("yakinDurakKodu"),
            )
        }
    }

    /** Stops of a line with coordinates, keyed by direction ('G' = gidiş, 'D' = dönüş). */
    suspend fun lineStopsGeo(line: String): Map<Char, List<GeoStop>> {
        val xml = soap("https://api.ibb.gov.tr/iett/ibb/ibb.asmx", "DurakDetay_GYY",
            "<hat_kodu>${safeLine(line)}</hat_kodu>")
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        return doc.select("Table").mapNotNull { t ->
            fun f(n: String) = t.selectFirst(n)?.text().orEmpty()
            val dir = f("YON").firstOrNull() ?: return@mapNotNull null
            val lat = f("YKOORDINATI").toDoubleOrNull() ?: return@mapNotNull null
            val lon = f("XKOORDINATI").toDoubleOrNull() ?: return@mapNotNull null
            dir to GeoStop(f("DURAKKODU"), f("SIRANO").toIntOrNull() ?: 0, f("DURAKADI"), lat, lon)
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sortedBy { it.order } }
    }

    /** Route shape for a route code like "500T_G_D0", as polylines of (lat, lon). */
    suspend fun routeShape(routeCode: String): List<List<Pair<Double, Double>>> {
        val arr = org.json.JSONArray(get("/tr/RouteStation/GetRoutePinV2", mapOf("q" to routeCode)))
        val out = ArrayList<List<Pair<Double, Double>>>()
        for (i in 0 until arr.length()) {
            arr.getJSONObject(i).optString("line").split("|").forEach { ls ->
                val inner = ls.substringAfter("(", "").substringBeforeLast(")", "")
                val pts = inner.split(",").mapNotNull { p ->
                    val xy = p.trim().split(" ")
                    val x = xy.getOrNull(0)?.toDoubleOrNull(); val y = xy.getOrNull(1)?.toDoubleOrNull()
                    if (x != null && y != null) y to x else null
                }
                if (pts.size >= 2) out += pts
            }
        }
        return out
    }

    // ---- Stop metadata (open İBB SOAP service) ----------------------------------

    suspend fun stopInfo(stopCode: String): StopInfo? = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><GetDurak_json xmlns="http://tempuri.org/"><DurakKodu>${stopCode.filter { it.isDigit() }}</DurakKodu></GetDurak_json></soap:Body></soap:Envelope>"""
        val req = Request.Builder()
            .url("https://api.ibb.gov.tr/iett/UlasimAnaVeri/HatDurakGuzergah.asmx")
            .header("SOAPAction", "\"http://tempuri.org/GetDurak_json\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val xml = resp.bodyCapped()
                val jsonText = Regex("<GetDurak_jsonResult>(.*)</GetDurak_jsonResult>", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1) ?: return@use null
                val arr = org.json.JSONArray(Jsoup.parse(jsonText).text())
                if (arr.length() == 0) return@use null
                val o = arr.getJSONObject(0)
                val pt = Regex("POINT \\(([-\\d.]+) ([-\\d.]+)\\)").find(o.optString("KOORDINAT"))
                StopInfo(
                    name = o.optString("SDURAKADI"),
                    district = o.optString("ILCEADI"),
                    direction = o.optString("SYON"),
                    lat = pt?.groupValues?.get(2)?.toDoubleOrNull(),
                    lon = pt?.groupValues?.get(1)?.toDoubleOrNull(),
                )
            }
        }.getOrNull()
    }
}
