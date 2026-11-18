package com.onur.otobusum.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.onur.otobusum.data.Favorite
import com.onur.otobusum.data.Favorites
import com.onur.otobusum.data.IettApi
import com.onur.otobusum.data.SearchResult
import com.onur.otobusum.data.StopArrivals
import com.onur.otobusum.data.StopInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REFRESH_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StopScreen(
    code: String,
    initialName: String,
    favorites: Favorites,
    back: () -> Unit,
    openLine: (String, String) -> Unit,
    openMap: (line: String, stop: String) -> Unit,
) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<StopArrivals?>(null) }
    var info by remember { mutableStateOf<StopInfo?>(null) }
    var lines by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var updatedAt by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }
    val favs by favorites.items.collectAsState()
    val isFav = favs.any { it.kind == SearchResult.Kind.STOP && it.code == code }

    val name = info?.name?.takeIf { it.isNotBlank() } ?: initialName.ifBlank { "Durak $code" }

    LaunchedEffect(code) {
        launch { info = IettApi.stopInfo(code) }
        launch { lines = runCatching { IettApi.linesAtStop(code) }.getOrDefault(emptyList()) }
    }

    // Auto-refresh while the screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(code, trigger) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refreshing = data != null
                try {
                    data = IettApi.arrivals(code)
                    error = null
                    updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "bilinmeyen hata"
                } finally {
                    refreshing = false
                }
                delay(REFRESH_MS)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(info?.district?.takeIf { it.isNotBlank() },
                        info?.direction?.takeIf { it.isNotBlank() }, "#$code").joinToString(" • ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            actions = {
                val i = info
                if (i?.lat != null && i.lon != null) {
                    IconButton(onClick = {
                        val uri = Uri.parse("geo:${i.lat},${i.lon}?q=${i.lat},${i.lon}(${Uri.encode(name)})")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) { Icon(Icons.Default.Map, "Haritada göster") }
                }
                IconButton(onClick = {
                    favorites.toggle(Favorite(SearchResult.Kind.STOP, code, name,
                        listOfNotNull(info?.district, info?.direction).filter { it.isNotBlank() }
                            .joinToString(" • ").let { if (it.isBlank()) "#$code" else "$it  #$code" }))
                }) {
                    Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                        if (isFav) "Favorilerden çıkar" else "Favorilere ekle")
                }
            },
        )
    }) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { trigger++ },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val d = data
            when {
                d == null && error != null -> ErrorBox(error!!) { trigger++ }
                d == null -> CenterText("Yükleniyor…")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            buildString {
                                append("Varış süreleri tahminidir • Canlı takip için otobüse dokunun")
                                updatedAt?.let { append(" • Güncellendi $it") }
                                if (error != null) append(" • Son yenileme başarısız")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp, 8.dp),
                        )
                    }
                    if (d.arrivals.isEmpty()) {
                        item { Text("Şu anda bu durağa yaklaşan otobüs yok.", Modifier.padding(16.dp)) }
                    }
                    items(d.arrivals) { a ->
                        ListItem(
                            modifier = Modifier.clickable { openMap(a.line, code) },
                            leadingContent = { LineBadge(a.line) },
                            headlineContent = { Text(a.destination) },
                            trailingContent = {
                                // eta looks like "(14:30) 7 dk"
                                val m = Regex("^\\((\\d{1,2}:\\d{2})\\)\\s*(.*)$").find(a.eta)
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(m?.groupValues?.get(2)?.ifBlank { null } ?: a.eta, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    m?.let { Text(it.groupValues[1], style = MaterialTheme.typography.labelSmall) }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                    if (lines.isNotEmpty()) {
                        item {
                            Text("Duraktan geçen hatlar", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                            FlowRow(Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                lines.forEach { l ->
                                    AssistChip(onClick = { openLine(l.code, l.subtitle) }, label = { Text(l.code) })
                                }
                            }
                        }
                    }
                    item {
                        d.notes.drop(1).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp, 8.dp))
                        }
                    }
                }
            }
        }
    }
}
