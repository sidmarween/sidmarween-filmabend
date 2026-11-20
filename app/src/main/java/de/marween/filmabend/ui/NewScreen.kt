package de.marween.filmabend.ui

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Catalog
import de.marween.filmabend.CatalogItem
import de.marween.filmabend.normName

@Composable
fun NewScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    val movies by vm.newMovies.collectAsStateWithLifecycle()
    val shows by vm.newShows.collectAsStateWithLifecycle()
    val t = if (type == 0) "movie" else "tv"
    val state = if (type == 0) movies else shows
    var playing by remember { mutableStateOf<CatalogItem?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(t) { vm.loadNew(t) }

    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Neue Filme", "Neue Serien"), type, { type = it })
        Text(
            if (type == 0) "Was gerade neu in den deutschen iTunes-Charts ist – mit deutschem Trailer."
            else "Serien, die bei Streamingdiensten gerade starten oder bald kommen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        when {
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (type == 1) Text("Programm wird durchsucht …", modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error != null -> EmptyHint("Keine Neuheiten", state.error ?: "") {
                TextButton(onClick = { vm.loadNew(t, force = true) }) { Text("Nochmal versuchen") }
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        IconButton(onClick = { vm.loadNew(t, force = true) }) { Icon(Icons.Filled.Refresh, "Neu laden") }
                    }
                }
                items(state.items, key = { it.item.key }) { rec ->
                    NewCard(
                        vm, rec.item,
                        onOpen = { openDetail(rec.item.type, rec.item.id) },
                        onTrailer = {
                            val url = rec.item.trailer
                            if (url != null) playing = rec.item
                            else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Catalog.youtubeTrailer(rec.item.title, rec.item.year))))
                        }
                    )
                }
            }
        }
    }

    playing?.let { item -> TrailerDialog(item.title, item.trailer ?: "") { playing = null } }
}

@Composable
private fun NewCard(vm: AppViewModel, item: CatalogItem, onOpen: () -> Unit, onTrailer: () -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    val t = titles.firstOrNull { it.key == item.key }
        ?: titles.firstOrNull { it.type == item.type && normName(it.title) == normName(item.title) }
    val watched = t?.isWatched(uid) == true
    val listed = t?.onWatchlist(uid) == true

    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(item.poster, item.type, Modifier.width(92.dp), corner = 8.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.extra.isNotBlank()) Text(item.extra, style = MaterialTheme.typography.labelMedium, color = Gold)
                if (item.genres.isNotEmpty()) Text(item.genres.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.overview.isNotBlank()) Text(item.overview, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onTrailer, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(if (item.trailer != null) "Trailer" else "Trailer (YouTube)")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (t != null) vm.setWatchlist(t.info, !listed) else vm.setWatchlist(item.info, true) }) {
                        Icon(if (listed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Merken", tint = if (listed) Rose else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (t != null) vm.setWatched(t.info, !watched) else vm.setWatched(item.info, true) }) {
                        Icon(if (watched) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline, "Gesehen", tint = if (watched) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (partner != null) {
                        IconButton(onClick = { vm.suggest(item.info, "") }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Vorschlagen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Spielt den Apple-Trailer direkt in der App ab. */
@Composable
fun TrailerDialog(title: String, url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().background(Color.Black).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                Text(title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Schließen", tint = Color.White) }
            }
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        val mc = MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoURI(Uri.parse(url))
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
        }
    }
}
