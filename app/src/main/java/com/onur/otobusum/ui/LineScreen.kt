package com.onur.otobusum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import com.onur.otobusum.data.Favorite
import com.onur.otobusum.data.Favorites
import com.onur.otobusum.data.IettApi
import com.onur.otobusum.data.LineDirection
import com.onur.otobusum.data.SearchResult
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineScreen(
    code: String,
    name: String,
    favorites: Favorites,
    back: () -> Unit,
    openStop: (String, String) -> Unit,
    openMap: (String) -> Unit,
) {
    var dirs by remember { mutableStateOf<List<LineDirection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val favs by favorites.items.collectAsState()
    val isFav = favs.any { it.kind == SearchResult.Kind.LINE && it.code == code }

    LaunchedEffect(code, trigger) {
        error = null
        try { dirs = IettApi.lineStops(code) } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "bilinmeyen hata"
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(code)
                    if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            actions = {
                IconButton(onClick = { openMap(code) }) { Icon(Icons.Default.Map, "Canlı harita") }
                IconButton(onClick = { favorites.toggle(Favorite(SearchResult.Kind.LINE, code, code, name)) }) {
                    Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                        if (isFav) "Favorilerden çıkar" else "Favorilere ekle")
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val d = dirs
            when {
                error != null -> ErrorBox(error!!) { trigger++ }
                d == null -> CenterText("Yükleniyor…")
                d.isEmpty() -> CenterText("Bu hat için durak bilgisi bulunamadı.")
                else -> {
                    val sel = tab.coerceIn(0, d.lastIndex)
                    if (d.size > 1) {
                        PrimaryTabRow(selectedTabIndex = sel) {
                            d.forEachIndexed { i, dir ->
                                Tab(selected = i == sel, onClick = { tab = i }, text = {
                                    Text("→ ${dir.stops.last().name}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                })
                            }
                        }
                    }
                    LazyColumn {
                        items(d[sel].stops, key = { "${it.order}-${it.code}" }) { s ->
                            ListItem(
                                modifier = Modifier.clickable { openStop(s.code, s.name) },
                                leadingContent = { Text("${s.order}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(32.dp)) },
                                headlineContent = { Text(s.name) },
                                supportingContent = if (s.district.isNotBlank()) ({ Text(s.district) }) else null,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
