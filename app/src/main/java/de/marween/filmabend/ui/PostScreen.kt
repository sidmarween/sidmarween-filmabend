package de.marween.filmabend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Suggestion
import de.marween.filmabend.typeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PostScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val all by vm.suggestions.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val partner = hh?.partnerOf(uid)
    val pName = partner?.let { hh?.nameOf(it) } ?: "Partner"

    val received = all.filter { it.to == uid }
    val sent = all.filter { it.from == uid }
    LaunchedEffect(received.count { !it.seen }) { vm.markSuggestionsSeen() }

    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Für mich (${received.count { it.status == "offen" }})", "Von mir gesendet"), tab, { tab = it })
        val list = if (tab == 0) received else sent
        if (list.isEmpty()) {
            EmptyHint(
                if (tab == 0) "Noch keine Post" else "Noch nichts verschickt",
                if (partner == null) "Verbinde zuerst deinen Partner (Einstellungen → Code teilen)."
                else if (tab == 0) "Wenn $pName dir einen Film oder eine Serie vorschlägt, landet er hier."
                else "Öffne einen Titel und tippe auf „$pName vorschlagen“."
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { s ->
                    val t = titles.firstOrNull { it.key == s.key }
                    SuggestionCard(
                        s, incoming = tab == 0,
                        otherName = hh?.nameOf(if (tab == 0) s.from else s.to) ?: "",
                        watchedByMe = t?.isWatched(uid) == true,
                        watchedByOther = t?.isWatched(if (tab == 0) s.from else s.to) == true,
                        onOpen = { openDetail(s.type, s.mediaId) },
                        onAccept = { vm.answerSuggestion(s, true) },
                        onDecline = { vm.answerSuggestion(s, false) },
                        onDelete = { vm.deleteSuggestion(s) },
                    )
                }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

@Composable
private fun SuggestionCard(
    s: Suggestion, incoming: Boolean, otherName: String, watchedByMe: Boolean, watchedByOther: Boolean,
    onOpen: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit, onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(s.poster, s.type, Modifier.width(70.dp), corner = 6.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${typeLabel(s.type)}${if (s.year.isNotBlank()) " · ${s.year}" else ""} · " +
                        (if (incoming) "von $otherName" else "an $otherName") + " · ${dateFmt.format(Date(s.createdAt))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (s.note.isNotBlank()) Text("„${s.note}“", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                val status = when {
                    incoming && watchedByMe -> "✅ Schon gesehen"
                    !incoming && watchedByOther -> "✅ $otherName hat's gesehen"
                    s.status == "angenommen" -> "💑 Auf eurer Zusammen-Liste"
                    s.status == "abgelehnt" -> "🙅 Nein danke"
                    else -> if (incoming) null else "⏳ Noch keine Antwort"
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Gold) }
                if (incoming && s.status == "offen" && !watchedByMe) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onAccept) { Text("💑 Zusammen gucken") }
                        OutlinedButton(onClick = onDecline) { Text("Nein danke") }
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
