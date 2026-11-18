package com.onur.otobusum

import com.onur.otobusum.data.IettApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hits the real İETT endpoints; run manually with ./gradlew testDebugUnitTest to check the site hasn't changed. */
class LiveApiTest {
    @Test fun endToEnd() = runBlocking {
        val s = IettApi.search("kadıköy rıhtım")
        println("search: ${s.take(5)}"); assertTrue(s.isNotEmpty())
        val l = IettApi.search("500T"); println("line search: ${l.take(3)}")
        assertTrue(l.first().code == "500T")
        val a = IettApi.arrivals("301341"); println("arrivals: $a")
        val lines = IettApi.linesAtStop("301341"); println("lines: $lines"); assertTrue(lines.isNotEmpty())
        val dirs = IettApi.lineStops("500T")
        dirs.forEach { println("dir ${it.title}: ${it.stops.size} stops, first=${it.stops.first()}, last=${it.stops.last()}") }
        assertTrue(dirs.size == 2)
        val info = IettApi.stopInfo("301341"); println("info: $info"); assertTrue(info?.lat != null)
    }
}

class LiveMapApiTest {
    @Test fun mapData() = runBlocking {
        val v = IettApi.vehicles("500T"); println("vehicles: ${v.size} ${v.take(2)}")
        val g = IettApi.lineStopsGeo("500T"); g.forEach { (k, s) -> println("geo $k: ${s.size} first=${s.first()}") }
        assertTrue(g.keys.containsAll(listOf('G', 'D')))
        val sh = IettApi.routeShape("500T_G_D0"); println("shape: ${sh.size} lines, ${sh.sumOf { it.size }} pts, first=${sh.first().first()}")
        assertTrue(sh.isNotEmpty())
    }
}
