@file:OptIn(ExperimentalMaterial3Api::class)

package de.marween.filmabend.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Catalog
import de.marween.filmabend.CatalogItem
import de.marween.filmabend.typeLabel
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(vm: AppViewModel, back: () -> Unit, openPerson: (String, Int) -> Unit = { _, _ -> }, openDetail: (String, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var people by remember { mutableStateOf<List<de.marween.filmabend.Person>>(emptyList()) }
    var results by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, mode) {
        if (query.trim().length < 2) { results = emptyList(); people = emptyList(); return@LaunchedEffect }
        delay(400)
        loading = true; error = null
        try {
            if (mode == 0) results = Catalog.search(query.trim())
            else people = de.marween.filmabend.People.search(query.trim())
        } catch (e: Exception) { error = e.localizedMessage }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Suchen") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(if (mode == 0) "Film oder Serie …" else "Name, z. B. Matthias Schweighöfer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus)
            )
            ChipRow(listOf("🎬 Filme & Serien", "🎭 Schauspieler"), mode, { mode = it }, Modifier.padding(top = 8.dp))
            if (mode == 1) {
                when {
                    loading && people.isEmpty() -> TiwiLoading(null)
                    error != null -> EmptyHint("Hoppla", error ?: "", pose = TiwiPose.Surprised)
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (query.trim().length >= 2) item {
                        PersonRow(null, "Alle Filme mit „${query.trim()}“", "Direkt nach Darstellernamen suchen") { openPerson(query.trim(), 0) }
                    }
                    items(people, key = { "p${it.id}" }) { p ->
                        val sub = listOf(p.birthday.take(4).let { if (it.isNotBlank()) "geb. $it" else "" }, p.country).filter { it.isNotBlank() }.joinToString(" · ")
                        PersonRow(p.image, p.name, sub) { openPerson(p.name, p.id) }
                    }
                }
                return@Column
            }
            when {
                loading && results.isEmpty() -> TiwiLoading(null)
                error != null -> EmptyHint("Hoppla", error ?: "", pose = TiwiPose.Surprised)
                query.length >= 2 && results.isEmpty() && !loading -> EmptyHint("Nichts gefunden", "Probier einen anderen Suchbegriff.", pose = TiwiPose.Investigating)
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results, key = { it.key }) { item ->
                    ItemRow(vm, item) { openDetail(item.type, item.id) }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(image: String?, name: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(CardBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (image == null && sub.startsWith("Direkt")) Icons.Filled.Search else Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (image != null) coil.compose.AsyncImage(
                model = image, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Zeile für einen Treffer mit Schnell-Knöpfen „Gesehen“ und „Merken“. */
@Composable
fun ItemRow(vm: AppViewModel, item: CatalogItem, extra: String? = null, trailing: (@Composable () -> Unit)? = null, quickRate: Boolean = false, onClick: () -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val t = titles.firstOrNull { it.key == item.key }
        ?: if (!item.resolved) titles.firstOrNull { it.type == "tv" && de.marween.filmabend.normName(it.title) == de.marween.filmabend.normName(item.title) } else null
    val watched = t?.isWatched(uid) == true
    val listed = t?.onWatchlist(uid) == true

    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Poster(item.poster, item.type, Modifier.width(56.dp), corner = 6.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(typeLabel(item.type), item.year, if (item.vote > 0) "★ ${de.marween.filmabend.fmt1(item.vote)}" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (extra != null) Text(extra, style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t?.rating(uid)?.let { Text("Deine Wertung: $it/10", style = MaterialTheme.typography.labelSmall, color = Gold) }
        }
        trailing?.invoke()
        IconButton(onClick = {
            if (t != null) vm.setWatchlist(t.info, !listed) else vm.withResolved(item) { vm.setWatchlist(it.info, true) }
        }) {
            Icon(if (listed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Merken", tint = if (listed) Rose else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = {
            if (t != null) vm.setWatched(t.info, !watched) else vm.withResolved(item) { vm.setWatched(it.info, true) }
        }) {
            Icon(if (watched) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline, "Gesehen", tint = if (watched) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (quickRate) {
        val mine = t?.rating(uid)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text(
                if (mine != null) "Deine Wertung: $mine/10 – nochmal tippen zum Ändern" else "Schon gesehen? Wie fandest du's?",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf(2 to "😖", 4 to "😕", 6 to "😐", 8 to "🙂", 10 to "😍").forEach { (r, e) ->
                    val sel = mine != null && kotlin.math.abs(mine - r) <= 1
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (sel) Gold.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                if (t != null) vm.setRating(t.info, if (sel) null else r)
                                else vm.withResolved(item) { vm.setRating(it.info, r) }
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 20.sp, maxLines = 1) }
                }
            }
        }
    }
    }
}
