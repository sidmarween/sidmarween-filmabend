package de.marween.filmabend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Title
import de.marween.filmabend.typeLabel

@Composable
fun LibraryScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit, openSearch: () -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    val pName = if (partner != null) hh?.nameOf(partner) ?: "Partner" else "Partner"

    var who by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableIntStateOf(0) }
    val whoOptions = listOf("💑 Zusammen gucken", "Von mir gesehen", "Von $pName gesehen", "Von beiden", "Merkliste")

    val list = titles.filter { typeMatches(type, it.type) }.let { all ->
        when (who) {
            0 -> all.filter { it.together && !(it.isWatched(uid) && it.isWatched(partner)) }.sortedByDescending { it.addedAt }
            1 -> all.filter { it.isWatched(uid) }.sortedByDescending { it.watchedAt[uid] ?: 0L }
            2 -> all.filter { it.isWatched(partner) }.sortedByDescending { it.watchedAt[partner] ?: 0L }
            3 -> all.filter { it.isWatched(uid) && it.isWatched(partner) }
                .sortedByDescending { maxOf(it.watchedAt[uid] ?: 0L, it.watchedAt[partner] ?: 0L) }
            else -> all.filter { it.onWatchlist(uid) || it.onWatchlist(partner) }.sortedByDescending { it.addedAt }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChipRow(whoOptions, who, { who = it })
        ChipRow(TypeFilters, type, { type = it }, Modifier.padding(top = 2.dp))
        if (list.isEmpty()) {
            EmptyHint(
                title = if (titles.isEmpty()) "Noch nichts eingetragen" else if (who == 0) "Eure Zusammen-Liste ist leer" else "Hier ist noch nichts",
                text = if (titles.isEmpty()) "Sucht einen Film oder eine Serie, die ihr schon gesehen habt, und hakt sie ab."
                else if (who == 0) "Tippe unter „Neu“ oder bei einem Titel auf „Zusammen gucken“ – dann landet er hier, für euch beide."
                else "Wechsle den Filter oben oder trag etwas Neues ein.",
            ) { Button(onClick = openSearch) { Text("Titel suchen") } }
        } else {
            Text(
                "${list.size} ${if (list.size == 1) "Titel" else "Titel"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(list, key = { it.key }) { t ->
                    TitleCard(t, uid, partner, who == 4, hh?.nameOf(partner)) { openDetail(t.type, t.mediaId) }
                }
            }
        }
    }
}

@Composable
private fun TitleCard(t: Title, uid: String?, partner: String?, watchlistMode: Boolean, partnerName: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            Poster(t.poster, t.type, Modifier.fillMaxWidth())
            Row(
                Modifier.align(Alignment.BottomStart).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                t.rating(uid)?.let { RatingBadge("$it", ratingColor(it.toDouble()), "ICH") }
                t.rating(partner)?.let { RatingBadge("$it", ratingColor(it.toDouble()), (partnerName ?: "P").take(3).uppercase()) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = buildString {
            append(typeLabel(t.type))
            if (t.year.isNotBlank()) append(" · ${t.year}")
            if (watchlistMode) {
                val a = t.onWatchlist(uid); val b = t.onWatchlist(partner)
                append(if (a && b) " · beide" else if (a) " · ich" else " · ${partnerName ?: "Partner"}")
            }
        }
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
