package com.onur.otobusum.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.onur.otobusum.data.GeoStop
import com.onur.otobusum.data.IettApi
import com.onur.otobusum.data.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

private const val VEHICLE_REFRESH_MS = 10_000L
private const val COLOR_G = 0xFFC8102E.toInt() // gidiş
private const val COLOR_D = 0xFF1565C0.toInt() // dönüş

/** dirFilter: null = both directions, otherwise 'G' or 'D'. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineMapScreen(
    line: String,
    name: String,
    focusStop: String?,
    back: () -> Unit,
    openStops: () -> Unit,
) {
    val ctx = LocalContext.current
    var stops by remember { mutableStateOf<Map<Char, List<GeoStop>>>(emptyMap()) }
    var shapes by remember { mutableStateOf<Map<Char, List<List<Pair<Double, Double>>>>>(emptyMap()) }
    var vehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // Default the filter to the direction that serves the focused stop.
    LaunchedEffect(line) {
        try {
            coroutineScope {
                val s = async { IettApi.lineStopsGeo(line) }
                val g = async { runCatching { IettApi.routeShape("${line}_G_D0") }.getOrDefault(emptyList()) }
                val d = async { runCatching { IettApi.routeShape("${line}_D_D0") }.getOrDefault(emptyList()) }
                stops = s.await()
                shapes = mapOf('G' to g.await(), 'D' to d.await())
            }
            if (focusStop != null && dirFilter == null) {
                stops.entries.firstOrNull { e -> e.value.any { it.code == focusStop } }?.let { dirFilter = it.key.toString() }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(line) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    vehicles = IettApi.vehicles(line); error = null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "bilinmeyen hata"
                } finally { loading = false }
                delay(VEHICLE_REFRESH_MS)
            }
        }
    }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.filesDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid-tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(41.015, 28.98))
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onDetach() }
    }

    // Fit the camera once, after stops arrive.
    var fitted by remember { mutableStateOf(false) }

    val dirs = listOf('G', 'D').filter { stops[it]?.isNotEmpty() == true }
    val activeDirs = dirFilter?.let { listOf(it.first()) } ?: dirs
    val shownVehicles = vehicles.filter { dirFilter == null || it.dir == dirFilter!!.first() }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("$line • Canlı")
                    Text(
                        if (loading) "Yükleniyor…" else "${shownVehicles.size} araç" +
                            (error?.let { " • yenileme başarısız" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            actions = { IconButton(onClick = openStops) { Icon(Icons.AutoMirrored.Filled.List, "Duraklar") } },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    // Remember which popup is open so the periodic refresh doesn't close it.
                    val openKey = map.overlays.filterIsInstance<Marker>().firstOrNull { it.isInfoWindowShown }?.id
                    org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map)
                    map.overlays.clear()
                    activeDirs.forEach { d ->
                        val color = if (d == 'G') COLOR_G else COLOR_D
                        shapes[d].orEmpty().forEach { seg ->
                            map.overlays += Polyline(map).apply {
                                setPoints(seg.map { GeoPoint(it.first, it.second) })
                                outlinePaint.color = color
                                outlinePaint.alpha = 170
                                outlinePaint.strokeWidth = 8f
                                infoWindow = null
                            }
                        }
                    }
                    val dot = { color: Int, big: Boolean -> dotIcon(map.context, color, big) }
                    activeDirs.forEach { d ->
                        val color = if (d == 'G') COLOR_G else COLOR_D
                        stops[d].orEmpty().forEach { s ->
                            val focused = s.code == focusStop
                            map.overlays += Marker(map).apply {
                                id = "s:${s.code}"
                                position = GeoPoint(s.lat, s.lon)
                                icon = dot(color, focused)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                title = "${s.order}. ${s.name}"
                                snippet = android.text.TextUtils.htmlEncode("Durak #${s.code}")
                            }
                        }
                    }
                    val stopNames = stops.values.flatten().associate { it.code to it.name }
                    shownVehicles.forEach { v ->
                        map.overlays += Marker(map).apply {
                            id = "v:${v.id}"
                            setPanToView(false)
                            position = GeoPoint(v.lat, v.lon)
                            icon = busIcon(map.context, v.id, if (v.dir == 'D') COLOR_D else COLOR_G)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "${v.id} → ${v.heading}"
                            // osmdroid renders the snippet with Html.fromHtml: escape server-provided text.
                            snippet = listOfNotNull(
                                stopNames[v.nearStopCode]?.let { "Yakın durak: $it" },
                                v.time.substringAfter(' ').takeIf { it.isNotBlank() }?.let { "Konum: $it" },
                            ).joinToString("<br>") { android.text.TextUtils.htmlEncode(it) }
                        }
                    }
                    openKey?.let { k -> map.overlays.filterIsInstance<Marker>().firstOrNull { it.id == k }?.showInfoWindow() }
                    if (!fitted && stops.isNotEmpty()) {
                        val focus = stops.values.flatten().firstOrNull { it.code == focusStop }
                        val pts = activeDirs.flatMap { stops[it].orEmpty() }.map { GeoPoint(it.lat, it.lon) }
                        map.post {
                            if (focus != null) {
                                map.controller.setZoom(15.0)
                                map.controller.setCenter(GeoPoint(focus.lat, focus.lon))
                            } else if (pts.size >= 2) {
                                map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.3f), false)
                            }
                        }
                        fitted = true
                    }
                    map.invalidate()
                },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))

            if (dirs.size > 1) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = dirFilter == null, onClick = { dirFilter = null }, label = { Text("Tümü") })
                        dirs.forEach { d ->
                            FilterChip(
                                selected = dirFilter == d.toString(),
                                onClick = { dirFilter = d.toString() },
                                label = { Text("→ ${stops[d]!!.last().name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            Text(
                "© OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

private fun dotIcon(ctx: Context, color: Int, big: Boolean): Drawable {
    val dp = ctx.resources.displayMetrics.density
    val r = (if (big) 9 else 5) * dp
    val size = (r * 2 + 4 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(size / 2f, size / 2f, r + 1.5f * dp, p)
    p.color = color; c.drawCircle(size / 2f, size / 2f, r, p)
    if (big) { p.color = 0xFFFFFFFF.toInt(); c.drawCircle(size / 2f, size / 2f, r / 2.5f, p) }
    return BitmapDrawable(ctx.resources, bmp)
}

private fun busIcon(ctx: Context, label: String, color: Int): Drawable {
    val dp = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt(); textSize = 9.5f * dp; typeface = Typeface.DEFAULT_BOLD
    }
    val padH = 4 * dp; val h = 15 * dp
    val w = text.measureText(label) + padH * 2
    val bmp = Bitmap.createBitmap((w + 4 * dp).toInt(), (h + 4 * dp).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF(2 * dp, 2 * dp, 2 * dp + w, 2 * dp + h)
    p.color = 0xFFFFFFFF.toInt(); c.drawRoundRect(RectF(rect).apply { inset(-1.5f * dp, -1.5f * dp) }, h / 2, h / 2, p)
    p.color = color; c.drawRoundRect(rect, h / 2, h / 2, p)
    c.drawText(label, rect.left + padH, rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    return BitmapDrawable(ctx.resources, bmp)
}
