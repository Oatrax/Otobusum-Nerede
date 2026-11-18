package com.onur.otobusum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onur.otobusum.data.Favorites
import com.onur.otobusum.data.IettApi
import com.onur.otobusum.data.SearchResult
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    favorites: Favorites,
    openStop: (String, String) -> Unit,
    openLine: (String, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val favs by favorites.items.collectAsState()

    LaunchedEffect(query) {
        error = null
        if (query.trim().length < 2) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(350) // debounce
        loading = true
        try {
            results = IettApi.search(query)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "bilinmeyen hata"
        } finally {
            loading = false
        }
    }

    fun open(kind: SearchResult.Kind, code: String, title: String, subtitle: String) =
        if (kind == SearchResult.Kind.STOP) openStop(code, title) else openLine(code, subtitle)

    Scaffold(topBar = { TopAppBar(title = { Text("Otobüsüm Nerede") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Durak adı, durak kodu veya hat") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Temizle") }
                },
                singleLine = true,
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            when {
                query.trim().length >= 2 -> when {
                    error != null -> ErrorBox(error!!) { val q = query; query = ""; query = q }
                    !loading && results.isEmpty() -> CenterText("Sonuç bulunamadı.")
                    else -> LazyColumn {
                        items(results, key = { it.kind.name + it.code }) { r ->
                            ResultRow(r.kind, r.title, r.subtitle) { open(r.kind, r.code, r.title, r.subtitle) }
                            HorizontalDivider()
                        }
                    }
                }
                favs.isEmpty() -> CenterText("Durak veya hat arayın.\nSık kullandıklarınızı ★ ile kaydedin.")
                else -> LazyColumn {
                    item {
                        Text("Favoriler", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                    }
                    items(favs, key = { it.kind.name + it.code }) { f ->
                        ResultRow(f.kind, f.title, f.subtitle) { open(f.kind, f.code, f.title, f.subtitle) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ResultRow(kind: SearchResult.Kind, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (kind == SearchResult.Kind.LINE) LineBadge(title)
            else Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = {
            Text(if (kind == SearchResult.Kind.LINE) subtitle else title,
                fontWeight = if (kind == SearchResult.Kind.STOP) FontWeight.SemiBold else null)
        },
        supportingContent = if (kind == SearchResult.Kind.STOP) ({ Text(subtitle) }) else null,
    )
}
