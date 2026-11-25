package de.marween.filmabend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.Coll
import de.marween.filmabend.CollItem
import de.marween.filmabend.PosterFinder
import de.marween.filmabend.WdHit
import de.marween.filmabend.Wikidata
import kotlinx.coroutines.launch

/**
 * Sammlungen: zu einem Stichwort (z. B. „James Bond“, „Disney“) alle Filme
 * chronologisch – jeder hakt für sich ab, was er schon gesehen hat.
 */
@Composable
fun CollectionsScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val colls by vm.collections.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val open = colls.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { openId = null }
        CollectionDetail(vm, open, { openId = null }, openDetail)
        return
    }
    if (creating) CreateCollectionDialog(vm) { creating = false }

    Box(Modifier.fillMaxSize()) {
        if (colls.isEmpty()) {
            EmptyHint(
                "Noch keine Sammlungen",
                "Leg eine Sammlung zu einem Stichwort an – z. B. „James Bond“, „Harry Potter“ oder „Walt Disney Animation Studios“. Alle Filme stehen dann chronologisch da und ihr hakt ab, was ihr gesehen habt.",
                pose = TiwiPose.Waving
            ) { Button(onClick = { creating = true }) { Icon(Icons.Filled.Add, null); Text(" Sammlung anlegen") } }
        } else {
            val titles by vm.titles.collectAsStateWithLifecycle()
            val uid by vm.uid.collectAsStateWithLifecycle()
            val hh by vm.household.collectAsStateWithLifecycle()
            val partner = hh?.partnerOf(uid)
            LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(colls, key = { it.id }) { c ->
                    val films = c.items.filter { it.key != "-" }
                    val mine = films.count { vm.libraryTitle(it, titles)?.isWatched(uid) == true }
                    val theirs = films.count { vm.libraryTitle(it, titles)?.isWatched(partner) == true }
                    val next = films.firstOrNull { vm.libraryTitle(it, titles)?.isWatched(uid) != true }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        modifier = Modifier.fillMaxWidth().clickable { openId = c.id }
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CollPoster(vm, next ?: films.firstOrNull(), Modifier.width(64.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${films.size} Filme · ${films.firstOrNull()?.year ?: ""}–${films.lastOrNull()?.year ?: ""}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (c.role.isNotBlank()) Text("🎭 ${c.role}: ${films.map { it.lead }.filter { it.isNotBlank() }.distinct().size} Darsteller",
                                    style = MaterialTheme.typography.labelMedium, color = Gold)
                                Progress("Du", mine, films.size)
                                if (partner != null) Progress(hh?.nameOf(partner) ?: "Partner", theirs, films.size)
                                if (next != null) Text("Als Nächstes: ${next.title} (${next.year})", style = MaterialTheme.typography.labelMedium, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Sammlung") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            )
        }
    }
}

@Composable
private fun Progress(who: String, n: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(who, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else n / total.toFloat() },
            modifier = Modifier.weight(1f).height(6.dp), color = Gold
        )
        Text("$n/$total", style = MaterialTheme.typography.labelSmall)
    }
}

/** Poster eines Sammlungsfilms: aus der Bibliothek, sonst nachgesucht. */
@Composable
private fun CollPoster(vm: AppViewModel, item: CollItem?, modifier: Modifier) {
    val titles by vm.titles.collectAsStateWithLifecycle()
    val known = item?.let { vm.libraryTitle(it, titles) }
    var poster by remember(item?.wd) { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.wd, known?.poster) {
        poster = known?.poster?.takeIf { it.isNotBlank() }
            ?: item?.let { runCatching { PosterFinder.find(it.title, "movie") }.getOrNull() }
    }
    Poster(poster, "movie", modifier, corner = 8.dp)
}

@Composable
private fun CollectionDetail(vm: AppViewModel, c: Coll, back: () -> Unit, openDetail: (String, Int) -> Unit) {
    val titles by vm.titles.collectAsStateWithLifecycle()
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var hideSeen by rememberSaveable { mutableStateOf(false) }
    var actor by rememberSaveable(c.id) { mutableStateOf(0) }
    // Ältere Sammlungen einmalig um Darsteller & Regie ergänzen
    LaunchedEffect(c.id) { if (c.version < 2) vm.refreshCollection(c, quiet = true) }
    val films = c.items.filter { it.key != "-" }
    val leads = if (c.role.isBlank()) emptyList() else films.map { it.lead }.filter { it.isNotBlank() }.distinct()
    val mine = films.count { vm.libraryTitle(it, titles)?.isWatched(uid) == true }
    val nextWd = films.firstOrNull { vm.libraryTitle(it, titles)?.isWatched(uid) != true }?.wd

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$mine von ${films.size} gesehen · chronologisch", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.role.isNotBlank()) Text("🎭 ${c.role} – gespielt von ${leads.size} Darstellern", style = MaterialTheme.typography.labelMedium, color = Gold)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (hideSeen) "Gesehene anzeigen" else "Gesehene ausblenden") }, { hideSeen = !hideSeen; menu = false })
                    DropdownMenuItem({ Text("Filmliste aktualisieren") }, { vm.refreshCollection(c); menu = false }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                    DropdownMenuItem({ Text("Umbenennen") }, { rename = true; menu = false })
                    DropdownMenuItem({ Text("Sammlung löschen") }, { confirmDelete = true; menu = false }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                }
            }
        }
        if (leads.size >= 2) ChipRow(
            listOf("Alle") + leads.map { l -> "$l (${films.count { it.lead == l }})" }, actor.coerceAtMost(leads.size), { actor = it }
        )
        val shown = films.withIndex()
            .filter { !hideSeen || vm.libraryTitle(it.value, titles)?.isWatched(uid) != true }
            .filter { actor == 0 || actor > leads.size || it.value.lead == leads[actor - 1] }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(shown, key = { _, it -> it.value.wd }) { pos, (i, f) ->
                // Neuer Abschnitt, sobald ein anderer Darsteller die Hauptfigur spielt
                val prevLead = shown.getOrNull(pos - 1)?.value?.lead
                if (c.role.isNotBlank() && f.lead.isNotBlank() && f.lead != prevLead) {
                    val era = films.filter { it.lead == f.lead }
                    Column(Modifier.fillMaxWidth().padding(top = if (pos == 0) 0.dp else 10.dp, bottom = 4.dp)) {
                        Text("🎭 ${c.role}: ${f.lead}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
                        Text(
                            "${era.size} ${if (era.size == 1) "Film" else "Filme"} · ${era.first().year}" + (if (era.last().year != era.first().year) "–${era.last().year}" else ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (c.role.isNotBlank() && f.lead.isBlank() && prevLead?.isNotBlank() == true) {
                    Text("Außerhalb der Reihe", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                val t = vm.libraryTitle(f, titles)
                val seenMe = t?.isWatched(uid) == true
                val seenPartner = partner != null && t?.isWatched(partner) == true
                var itemMenu by remember { mutableStateOf(false) }
                val isNext = f.wd == nextWd
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isNext) Gold.copy(alpha = 0.14f) else CardBg),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (t != null && t.mediaId > 0) openDetail(t.type, t.mediaId)
                        else vm.withTitle(f.title) { if (it.id > 0) openDetail(it.type, it.id) else vm.message.value = "Zu „${f.title}“ gibt es keine weiteren Infos" }
                    }
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                        CollPoster(vm, f, Modifier.width(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (seenMe) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(
                                f.year.ifBlank { "Jahr unbekannt" } + (if (f.director.isNotBlank()) " · Regie: ${f.director}" else ""),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            if (f.note.isNotBlank()) Text("⚠ ${f.note}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9E80))
                            if (isNext) Text("▶ Als Nächstes", style = MaterialTheme.typography.labelSmall, color = Gold)
                            if (seenPartner) Text("✓ ${hh?.nameOf(partner)} hat ihn gesehen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { itemMenu = true }) { Icon(Icons.Filled.MoreVert, "Mehr", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            DropdownMenu(itemMenu, { itemMenu = false }) {
                                DropdownMenuItem({ Text("Aus Sammlung entfernen") }, { vm.removeFromCollection(c, f); itemMenu = false })
                            }
                        }
                        IconButton(onClick = { vm.toggleCollectionWatched(c, f) }) {
                            Icon(
                                if (seenMe) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                if (seenMe) "Gesehen" else "Als gesehen abhaken",
                                tint = if (seenMe) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("„${c.name}“ löschen?") },
        text = { Text("Die Sammlung verschwindet für euch beide. Was ihr abgehakt habt, bleibt in der Bibliothek erhalten.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCollection(c); back() }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
    )
    if (rename) {
        var name by remember { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Sammlung umbenennen") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { rename = false; if (name.isNotBlank()) vm.renameCollection(c, name) }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun CreateCollectionDialog(vm: AppViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<WdHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<WdHit?>(null) }
    fun search() {
        if (query.isBlank()) return
        searching = true; error = null
        scope.launch {
            hits = runCatching { Wikidata.search(query.trim()) }.onFailure { error = it.localizedMessage }.getOrDefault(emptyList())
            if (hits.isEmpty() && error == null) error = "Nichts gefunden – probier ein anderes Stichwort."
            searching = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (creating == null) onClose() },
        title = { Text("Neue Sammlung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stichwort eingeben, z. B. „James Bond“, „Harry Potter“, „Pixar“ oder „Walt Disney Animation Studios“ – dann den passenden Treffer antippen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Stichwort") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { search() }, enabled = !searching) { Text("Suchen") }
                }
                if (searching || creating != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                creating?.let { Text("„${it.label}“ wird angelegt – alle Filme werden gesucht …", style = MaterialTheme.typography.bodySmall, color = Gold) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(hits, key = { it.id }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = creating == null) {
                                creating = h
                                vm.createCollection(h, h.label) { ok -> creating = null; if (ok) onClose() }
                            }.padding(vertical = 8.dp)
                        ) {
                            Text(h.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (h.description.isNotBlank()) Text(h.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose, enabled = creating == null) { Text("Schließen") } }
    )
}
