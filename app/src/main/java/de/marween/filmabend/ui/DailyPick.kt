package de.marween.filmabend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.foundation.border
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Catalog
import de.marween.filmabend.FunFacts
import de.marween.filmabend.Trailers
import de.marween.filmabend.fmt1

/**
 * „Sendung des Tages“: aus allen Sendern (öffentlich-rechtlich & privat) die,
 * die heute am besten zu euch passt – jeden Tag eine andere, mit einem Fun Fact.
 */
@Composable
fun DailyPickCard(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val tv by vm.tv.collectAsStateWithLifecycle()
    val seen = rememberSeen(vm)
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadTv() }

    val day = java.time.LocalDate.now()
    // Die 12 besten Treffer für euch; jeden Tag ein anderer davon (bevorzugt: läuft heute im TV)
    val candidates = remember(tv.items, day) {
        val fresh = tv.items.filterNot { seen(it.show.item.key, it.show.item.title) }
        val todayEnd = day.plusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toEpochSecond()
        val onToday = fresh.filter { it.show.nextAir in 1 until todayEnd }.take(3)
        // Bevorzugt je Art (Krimi, Doku, Show …) eine Sendung, damit die Tipps abwechslungsreich sind
        val varied = fresh.take(80).distinctBy { it.show.kind }.take(12)
        (onToday + varied + fresh.take(25)).distinctBy { it.show.item.key }.take(25)
    }
    if (candidates.isEmpty()) return
    // Startet jeden Tag mit „dem“ Tipp des Tages; per Knopf oder Antippen unten gibt's Alternativen
    val base = (day.dayOfYear * 7 + day.year) % candidates.size
    var offset by androidx.compose.runtime.saveable.rememberSaveable(day.toString()) { androidx.compose.runtime.mutableIntStateOf(0) }
    val idx = (base + offset).mod(candidates.size)
    val pick = candidates[idx]
    val s = pick.show
    val item = s.item

    var fact by remember(item.key) { mutableStateOf<String?>(null) }
    var factLoaded by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        fact = runCatching { FunFacts.find(item.title, item.type, item.overview) }.getOrNull()
        factLoaded = true
    }
    val fallbackFact = buildString {
        if (item.year.isNotBlank()) {
            val age = day.year - (item.year.toIntOrNull() ?: day.year)
            if (age >= 3) append("Gibt's schon seit ${item.year} – also seit $age Jahren. ")
            else append("Ganz frisch: erst ${item.year} gestartet. ")
        }
        if (item.vote >= 7.5) append("Mit IMDb ${fmt1(item.vote)} gehört sie zu den Top-bewerteten Sendungen. ")
        if (s.network.contains("·")) append("Läuft gleich bei mehreren Anbietern: ${s.network}.")
    }.trim()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { openDetail(item.type, item.id) }
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF3A2A12), Color(0xFF3A1B2A))), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (offset == 0) "⭐ SENDUNG DES TAGES" else "⭐ ALTERNATIVE ${offset.mod(candidates.size)} VON ${candidates.size - 1}",
                    style = MaterialTheme.typography.labelMedium, color = Gold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (candidates.size > 1) {
                    TextButton(
                        onClick = { offset = (offset + 1).mod(candidates.size) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Icon(Icons.Filled.Refresh, null); Text(" Anderer Tipp") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Poster(item.poster, item.type, Modifier.width(96.dp), corner = 8.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val whenTxt = if (s.nextAir > 0) "📺 " + java.text.SimpleDateFormat("EE HH:mm", java.util.Locale.GERMANY).format(java.util.Date(s.nextAir * 1000)) + " · "
                    else "▶ Abrufbar bei "
                    Text(whenTxt + s.network, style = MaterialTheme.typography.labelMedium, color = Gold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${s.kind} · ${pick.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    if (item.overview.isNotBlank()) GermanText(item.overview, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
            val shownFact = fact ?: fallbackFact.ifBlank { null }
            if (shownFact != null || !factLoaded) {
                Column(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(10.dp)).padding(10.dp)
                ) {
                    Text("💡 Wusstest du?", style = MaterialTheme.typography.labelMedium, color = Gold, fontWeight = FontWeight.Bold)
                    Text(shownFact ?: "Wird nachgeschlagen …", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { openUrl(ctx, item.ytUrl ?: Trailers.match(vm.trailers.value.items, item.title)?.url ?: Catalog.youtubeTrailer(item.title)) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Icon(Icons.Filled.PlayArrow, null); Text("Trailer") }
                TextButton(onClick = { openDetail(item.type, item.id) }) { Text("Details") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (item.resolved) vm.setRejected(item.info, true) else vm.withResolved(item) { vm.setRejected(it.info, true) } }) {
                    Icon(Icons.Filled.ThumbDownOffAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" Nicht mein Fall", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PartnerButtons(vm, item.title, item.key, { cb -> cb(item.info) })
        }
    }

    // Weitere Tipps zum Durchblättern – antippen macht sie zur Hauptempfehlung
    if (candidates.size > 1) {
        Text(
            "Weitere Tipps für heute",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp)
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(candidates.size) { i ->
                val c = candidates[i].show
                val selected = i == idx
                Column(
                    Modifier.width(92.dp).clickable { offset = (i - base).mod(candidates.size) },
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Poster(
                        c.item.poster, c.item.type,
                        Modifier.fillMaxWidth().then(
                            if (selected) Modifier.border(2.dp, Gold, RoundedCornerShape(8.dp)) else Modifier
                        ),
                        corner = 8.dp
                    )
                    Text(
                        c.item.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (selected) Gold else MaterialTheme.colorScheme.onSurface
                    )
                    val t = if (c.nextAir > 0) java.text.SimpleDateFormat("EE HH:mm", java.util.Locale.GERMANY).format(java.util.Date(c.nextAir * 1000))
                    else "Abrufbar"
                    Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
