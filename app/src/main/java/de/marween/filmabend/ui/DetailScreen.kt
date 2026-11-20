@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.marween.filmabend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.TitleInfo
import de.marween.filmabend.Catalog
import de.marween.filmabend.German
import de.marween.filmabend.CatalogDetail
import de.marween.filmabend.fmt1
import de.marween.filmabend.keyOf
import de.marween.filmabend.ratingWord
import de.marween.filmabend.typeLabel

@Composable
fun DetailScreen(vm: AppViewModel, type: String, id: Int, back: () -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val t = titles.firstOrNull { it.key == keyOf(type, id) }
    val partner = hh?.partnerOf(uid)
    val pName = partner?.let { hh?.nameOf(it) } ?: "Partner"

    var detail by remember { mutableStateOf<CatalogDetail?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showSuggest by remember { mutableStateOf(false) }
    var showTrailer by remember { mutableStateOf(false) }

    LaunchedEffect(type, id) {
        vm.loadTrailers()
        if (id > 0) {
            try { detail = Catalog.details(type, id) } catch (e: Exception) { loadError = e.localizedMessage }
        } else loadError = "Zu diesem Titel gibt es keine weiteren Infos."
    }

    val raw: TitleInfo? = detail?.info ?: t?.info
    var german by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    val rawOverview = raw?.overview ?: ""
    LaunchedEffect(rawOverview) {
        if (rawOverview.isNotBlank() && German.looksEnglish(rawOverview)) {
            translating = true
            german = runCatching { German.translate(rawOverview) }.getOrNull()
            translating = false
        } else german = null
    }
    // Beim Speichern gleich die deutsche Beschreibung mitnehmen
    val info: TitleInfo? = raw?.let { r -> german?.let { r.copy(overview = it) } ?: r }
    val watched = t?.isWatched(uid) == true
    val listed = t?.onWatchlist(uid) == true
    val myRating = t?.rating(uid)
    val pRating = t?.rating(partner)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(info?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
        )
    }) { pad ->
        if (info == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadError != null) EmptyHint("Konnte nicht laden", loadError ?: "") else CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Poster(info.poster, info.type, Modifier.width(120.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(typeLabel(info.type), info.year).filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    detail?.let { d ->
                        if (d.item.vote > 0) Text((if (de.marween.filmabend.JW.isJw(id)) "IMDb: " else "Bewertung im Netz: ") + "${fmt1(d.item.vote)}/10", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.runtime > 0) Text(if (info.type == "tv") "ca. ${d.runtime} Min. pro Folge" else "${d.runtime} Min.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.seasons > 0) Text("${d.seasons} Staffel${if (d.seasons == 1) "" else "n"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.status.isNotBlank()) Text("Serie ${d.status}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.genres.isNotEmpty()) Text(d.genres.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = Gold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = watched, onClick = { vm.setWatched(info, !watched) },
                    label = { Text(if (watched) "Gesehen" else "Als gesehen abhaken") },
                    leadingIcon = { if (watched) Icon(Icons.Filled.Check, null) }
                )
                FilterChip(
                    selected = listed, onClick = { vm.setWatchlist(info, !listed) },
                    label = { Text("Merkliste") },
                    leadingIcon = { Icon(if (listed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null) }
                )
            }

            val ctx = androidx.compose.ui.platform.LocalContext.current
            val trailerUrl = detail?.item?.trailer
            if (id > 0) androidx.compose.material3.FilledTonalButton(
                onClick = {
                    if (trailerUrl != null) showTrailer = true
                    else openUrl(ctx, detail?.item?.ytUrl ?: de.marween.filmabend.Trailers.match(vm.trailers.value.items, info.title)?.url
                        ?: Catalog.youtubeTrailer(info.title, info.year))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (trailerUrl != null || detail?.item?.yt != null) "Trailer ansehen" else "Deutschen Trailer suchen (YouTube)")
            }

            // ---- Bewertung ----
            SectionCard {
                Text("Deine Bewertung", style = MaterialTheme.typography.titleMedium)
                Text(
                    myRating?.let { "$it/10 – ${ratingWord(it)}" } ?: "Tippe eine Zahl an (nochmal tippen = löschen)",
                    color = if (myRating != null) ratingColor(myRating.toDouble()) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                RatingPicker(myRating) { r -> vm.setRating(info, if (r == myRating) null else r) }
                Spacer(Modifier.height(10.dp))
                val pText = when {
                    partner == null -> "Sobald dein Partner verbunden ist, siehst du hier seine Wertung."
                    pRating != null -> "$pName: $pRating/10 – ${ratingWord(pRating)}"
                    t?.isWatched(partner) == true -> "$pName hat's gesehen, aber noch nicht bewertet."
                    else -> "$pName hat's noch nicht gesehen."
                }
                Text(pText, color = pRating?.let { ratingColor(it.toDouble()) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                if (myRating != null && pRating != null) {
                    Text("Euer Schnitt: ${fmt1((myRating + pRating) / 2.0)}", fontWeight = FontWeight.Bold)
                }
            }

            // ---- Staffeln ----
            val seasons = detail?.seasons ?: info.seasons
            if (info.type == "tv" && seasons > 0) {
                val mine = t?.seasonsOf(uid) ?: emptyList()
                SectionCard {
                    Text("Welche Staffeln hast du gesehen?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..seasons).forEach { s ->
                            val on = s in mine
                            FilterChip(selected = on, onClick = { vm.setSeason(info.copy(seasons = seasons), s, !on) }, label = { Text("S$s") })
                        }
                    }
                    if (partner != null) {
                        val ps = t?.seasonsOf(partner)?.size ?: 0
                        Text("$pName: $ps von $seasons Staffeln", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            detail?.providers?.takeIf { it.isNotEmpty() }?.let { prov ->
                SectionCard {
                    Text("Läuft im Abo bei", style = MaterialTheme.typography.titleMedium)
                    Text(prov.joinToString(" · "), color = Gold)
                }
            }

            detail?.network?.takeIf { it.isNotBlank() }?.let { net ->
                SectionCard {
                    Text("Sender / Streamingdienst", style = MaterialTheme.typography.titleMedium)
                    Text(net, color = Gold)
                }
            }

            PartnerButtons(vm, info.title, info.key, { cb -> cb(info) })

            val overview = info.overview
            if (overview.isNotBlank()) {
                Text("Worum geht's?", style = MaterialTheme.typography.titleMedium)
                if (translating) Text("Wird ins Deutsche übersetzt …", style = MaterialTheme.typography.labelSmall, color = Gold)
                else if (german != null) Text("Automatisch übersetzt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(overview, style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (de.marween.filmabend.JW.isJw(id)) "Daten: JustWatch" else if (info.type == "tv") "Seriendaten: TVmaze.com" else "Filmdaten: Apple iTunes Store", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showTrailer) {
        detail?.item?.trailer?.let { TrailerDialog(detail?.item?.title ?: "", it) { showTrailer = false } }
    }

    if (showSuggest && info != null) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSuggest = false },
            title = { Text("An $pName schicken") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("„${info.title}“")
                    OutlinedTextField(note, { note = it }, label = { Text("Nachricht (optional)") }, placeholder = { Text("Das musst du sehen!") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.suggest(info, note); showSuggest = false }) { Text("Senden") } },
            dismissButton = { TextButton(onClick = { showSuggest = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** Zehn runde Knöpfe 1–10, eingefärbt von rot (schlecht) bis grün (super). */
@Composable
fun RatingPicker(value: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..10).forEach { r ->
            val c = ratingColor(r.toDouble())
            val sel = value != null && r <= value
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (sel) c else Color.Transparent)
                    .border(1.5.dp, c, CircleShape)
                    .clickable { onPick(r) },
                contentAlignment = Alignment.Center
            ) {
                Text("$r", color = if (sel) Color.Black else c, fontWeight = FontWeight.Bold)
            }
        }
    }
}

