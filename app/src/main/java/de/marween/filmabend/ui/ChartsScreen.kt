package de.marween.filmabend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Title
import de.marween.filmabend.fmt1
import de.marween.filmabend.typeLabel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ChartsScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val all by vm.titles.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    val pName = partner?.let { hh?.nameOf(it) } ?: "Partner"
    val myName = hh?.nameOf(uid) ?: "Ich"

    var basis by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableIntStateOf(0) }

    val titles = all.filter { typeMatches(type, it.type) }
    fun score(t: Title): Double? = when (basis) {
        1 -> t.rating(uid)?.toDouble()
        2 -> t.rating(partner)?.toDouble()
        else -> listOfNotNull(t.rating(uid), t.rating(partner)).takeIf { it.isNotEmpty() }?.average()
    }
    val rated = titles.mapNotNull { t -> score(t)?.let { t to it } }
    val top = rated.sortedWith(compareByDescending<Pair<Title, Double>> { it.second }.thenBy { it.first.title }).take(10)
    val flop = rated.filter { it.second <= 5.0 }.sortedBy { it.second }.take(5)
    val disagree = titles.mapNotNull { t ->
        val a = t.rating(uid); val b = t.rating(partner)
        if (a != null && b != null && abs(a - b) >= 3) Triple(t, a, b) else null
    }.sortedByDescending { abs(it.second - it.third) }.take(5)
    val sameTaste = titles.count { t -> val a = t.rating(uid); val b = t.rating(partner); a != null && b != null && abs(a - b) <= 1 }
    val bothRated = titles.count { it.rating(uid) != null && it.rating(partner) != null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ChipRow(listOf("Unser Schnitt", "Meine Wertung", "$pName"), basis, { basis = it })
            ChipRow(TypeFilters, type, { type = it }, Modifier.padding(top = 2.dp))
        }
        if (rated.isEmpty()) {
            item {
                EmptyHint("Noch keine Charts", "Bewertet eure gesehenen Filme und Serien von 1 bis 10 – dann entsteht hier eure Rangliste.")
            }
            return@LazyColumn
        }
        item {
            Column(Modifier.padding(16.dp)) {
                SectionCard {
                    Text("Statistik", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatLine("Gesehen", "$myName ${titles.count { it.isWatched(uid) }} · $pName ${titles.count { it.isWatched(partner) }} · zusammen ${titles.count { it.isWatched(uid) && it.isWatched(partner) }}")
                    val myAvg = titles.mapNotNull { it.rating(uid) }.takeIf { it.isNotEmpty() }?.average()
                    val pAvg = titles.mapNotNull { it.rating(partner) }.takeIf { it.isNotEmpty() }?.average()
                    StatLine("Ø Wertung", "$myName ${myAvg?.let { fmt1(it) } ?: "–"} · $pName ${pAvg?.let { fmt1(it) } ?: "–"}")
                    if (bothRated > 0) StatLine("Gleicher Geschmack", "bei $sameTaste von $bothRated gemeinsam bewerteten (±1)")
                    favoriteGenre(rated)?.let { StatLine("Lieblingsgenre", it) }
                }
                Spacer(Modifier.height(12.dp))
                SectionCard {
                    Text("Verteilung der Wertungen", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Distribution(rated.map { it.second.roundToInt().coerceIn(1, 10) })
                }
            }
        }
        item { Header("🏆 Top ${top.size}") }
        top.forEachIndexed { i, (t, s) ->
            item(key = "top_${t.key}") { RankRow(i + 1, t, s, uid, partner, myName, pName) { openDetail(t.type, t.mediaId) } }
        }
        if (flop.isNotEmpty()) {
            item { Header("🍅 Flop – lieber nicht nochmal") }
            flop.forEachIndexed { i, (t, s) ->
                item(key = "flop_${t.key}") { RankRow(i + 1, t, s, uid, partner, myName, pName) { openDetail(t.type, t.mediaId) } }
            }
        }
        if (disagree.isNotEmpty()) {
            item { Header("⚡ Hier seid ihr euch uneinig") }
            disagree.forEach { (t, a, b) ->
                item(key = "dis_${t.key}") {
                    Row(
                        Modifier.fillMaxWidth().clickable { openDetail(t.type, t.mediaId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Poster(t.poster, t.type, Modifier.width(44.dp), corner = 5.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(typeLabel(t.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RatingBadge("$a", ratingColor(a.toDouble()), myName.take(3).uppercase())
                        RatingBadge("$b", ratingColor(b.toDouble()), pName.take(3).uppercase())
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun favoriteGenre(rated: List<Pair<Title, Double>>): String? {
    val counts = HashMap<String, Double>()
    rated.filter { it.second >= 7 }.forEach { (t, s) -> t.genres.forEach { g -> counts[g] = (counts[g] ?: 0.0) + s } }
    return counts.maxByOrNull { it.value }?.key
}

@Composable
private fun Distribution(values: List<Int>) {
    val counts = (1..10).map { r -> values.count { it == r } }
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        counts.forEachIndexed { i, c ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (c > 0) Text("$c", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((70f * c / max).dp.coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (c > 0) ratingColor((i + 1).toDouble()) else Color.White.copy(alpha = 0.08f))
                )
                Text("${i + 1}", fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, t: Title, score: Double, uid: String?, partner: String?, myName: String, pName: String, onClick: () -> Unit) {
    val medal = when (rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> null }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (medal != null) Text(medal, fontSize = 22.sp)
            else Text("$rank", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Poster(t.poster, t.type, Modifier.width(44.dp), corner = 5.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            val parts = listOfNotNull(
                typeLabel(t.type),
                t.year.takeIf { it.isNotBlank() },
                t.rating(uid)?.let { "$myName $it" },
                t.rating(partner)?.let { "$pName $it" },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(fmt1(score), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ratingColor(score))
    }
}
