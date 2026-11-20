@file:OptIn(ExperimentalMaterial3Api::class)

package de.marween.filmabend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.CatalogItem
import de.marween.filmabend.People
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Alle Filme und Serien einer Schauspielerin / eines Schauspielers. */
@Composable
fun PersonScreen(vm: AppViewModel, name: String, id: Int, back: () -> Unit, openDetail: (String, Int) -> Unit) {
    var movies by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(name, id) {
        loading = true
        coroutineScope {
            val jw = async { runCatching { de.marween.filmabend.JW.personWorks(name) } }
            val s = async { runCatching { People.series(id) } }
            val jr = jw.await(); val sr = s.await()
            val all = jr.getOrDefault(emptyList())
            val mr = if (all.isEmpty()) runCatching { People.movies(name) } else Result.success(all.filter { it.type == "movie" })
            movies = mr.getOrDefault(emptyList())
            val jwShows = all.filter { it.type == "tv" }
            val names = jwShows.map { de.marween.filmabend.normName(it.title) }.toSet()
            series = jwShows + sr.getOrDefault(emptyList()).filter { de.marween.filmabend.normName(it.title) !in names }
            if (mr.isFailure && sr.isFailure) error = mr.exceptionOrNull()?.localizedMessage
        }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ChipRow(
                listOf("Alle (${movies.size + series.size})", "Filme (${movies.size})", "Serien (${series.size})"),
                filter, { filter = it }
            )
            val list = when (filter) {
                1 -> movies
                2 -> series
                else -> (movies + series).sortedByDescending { it.year }
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> EmptyHint("Hoppla", error ?: "")
                list.isEmpty() -> EmptyHint(
                    "Nichts gefunden",
                    if (filter == 2 && id == 0) "Für Serien bitte die Person aus der Trefferliste antippen." else "Zu „$name“ wurden hier keine Titel gefunden."
                )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Text(
                            "Daten: JustWatch & TVmaze",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    items(list, key = { it.key }) { item -> ItemRow(vm, item) { openDetail(item.type, item.id) } }
                }
            }
        }
    }
}
