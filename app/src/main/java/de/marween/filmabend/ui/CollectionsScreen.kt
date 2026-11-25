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
import androidx.compose.material.icons.filled.Link
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
import kotlinx.coroutines.sync.withPermit

/**
 * Sammlungen: zu einem Stichwort (z. B. „James Bond“, „Disney“) alle Filme
 * chronologisch – jeder hakt für sich ab, was er schon gesehen hat.
 */
@Composable
fun CollectionsScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val colls by vm.collections.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<String?>(null) }
    val shared by vm.sharedImport.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { shared?.let { importing = it; openId = null; vm.sharedImport.value = null } }
    val open = colls.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { openId = null }
        CollectionDetail(vm, open, { openId = null }, openDetail)
        return
    }
    if (creating) CreateCollectionDialog(vm, { creating = false; importing = "" }) { creating = false }
    importing?.let { start -> ImportListDialog(vm, start) { importing = null } }

    Box(Modifier.fillMaxSize()) {
        if (colls.isEmpty()) {
            EmptyHint(
                "Noch keine Sammlungen",
                "Leg eine Sammlung zu einem Stichwort an – z. B. „James Bond“, „Harry Potter“ oder „Walt Disney Animation Studios“. Alle Filme stehen dann chronologisch da und ihr hakt ab, was ihr gesehen habt.",
                pose = TiwiPose.Waving
            ) {
                Button(onClick = { creating = true }) { Icon(Icons.Filled.Add, null); Text(" Sammlung anlegen") }
                TextButton(onClick = { importing = "" }) { Text("Liste von Webseite einlesen") }
            }
        } else {
            val titles by vm.titles.collectAsStateWithLifecycle()
            val uid by vm.uid.collectAsStateWithLifecycle()
            val hh by vm.household.collectAsStateWithLifecycle()
            val partner = hh?.partnerOf(uid)
            LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(colls, key = { it.id }) { c ->
                    val films = remember(c.items) { c.items.filter { it.key != "-" } }
                    val stats = remember(films, titles, uid, partner) {
                        val t = films.map { vm.libraryTitle(it, titles) }
                        Triple(t.count { it?.isWatched(uid) == true }, t.count { it?.isWatched(partner) == true },
                            films.getOrNull(t.indexOfFirst { it?.isWatched(uid) != true }))
                    }
                    val (mine, theirs, next) = stats
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
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = { importing = "" }) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, null); Text(" Liste einlesen")
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Sammlung") },
                )
            }
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
private val posterGate = kotlinx.coroutines.sync.Semaphore(3)
private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

@Composable
private fun CollPoster(vm: AppViewModel, item: CollItem?, modifier: Modifier, known: de.marween.filmabend.Title? = null) {
    val fromLib = known?.poster?.takeIf { it.isNotBlank() }
    var poster by remember(item?.wd) { mutableStateOf(fromLib ?: item?.let { posterCache[it.wd] }?.ifEmpty { null }) }
    LaunchedEffect(item?.wd, fromLib) {
        if (fromLib != null) { poster = fromLib; return@LaunchedEffect }
        if (item == null || posterCache.containsKey(item.wd)) return@LaunchedEffect
        kotlinx.coroutines.delay(150) // beim schnellen Scrollen nicht für jede Zeile suchen
        val p = posterGate.withPermit { runCatching { PosterFinder.find(item.title, "movie") }.getOrNull() }
        posterCache[item.wd] = p ?: ""
        poster = p
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
    val films = remember(c.items) { c.items.filter { it.key != "-" } }
    val leads = remember(films, c.role) { if (c.role.isBlank()) emptyList() else films.map { it.lead }.filter { it.isNotBlank() }.distinct() }
    // Bibliothekseinträge einmal pro Änderung nachschlagen (nicht bei jedem Neuzeichnen)
    val lib = remember(films, titles) { films.associate { it.wd to vm.libraryTitle(it, titles) } }
    val mine = remember(lib, uid) { films.count { lib[it.wd]?.isWatched(uid) == true } }
    val nextWd = remember(lib, uid) { films.firstOrNull { lib[it.wd]?.isWatched(uid) != true }?.wd }

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
                    if (c.source.isNotBlank()) DropdownMenuItem({ Text("Filmliste aktualisieren") }, { vm.refreshCollection(c); menu = false }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                    DropdownMenuItem({ Text("Umbenennen") }, { rename = true; menu = false })
                    DropdownMenuItem({ Text("Sammlung löschen") }, { confirmDelete = true; menu = false }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                }
            }
        }
        if (leads.size >= 2) ChipRow(
            listOf("Alle") + leads.map { l -> "$l (${films.count { it.lead == l }})" }, actor.coerceAtMost(leads.size), { actor = it }
        )
        val shown = remember(films, lib, hideSeen, actor, leads, uid) {
            films.withIndex()
                .filter { !hideSeen || lib[it.value.wd]?.isWatched(uid) != true }
                .filter { actor == 0 || actor > leads.size || it.value.lead == leads[actor - 1] }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(shown, key = { _, it -> it.value.wd }) { pos, (i, f) ->
                // Neuer Abschnitt, sobald ein anderer Darsteller die Hauptfigur spielt
                val prevLead = shown.getOrNull(pos - 1)?.value?.lead
                if (c.role.isNotBlank() && f.lead.isNotBlank() && f.lead != prevLead) {
                    val era = remember(films, f.lead) { films.filter { it.lead == f.lead } }
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
                val t = lib[f.wd]
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
                        CollPoster(vm, f, Modifier.width(48.dp), t)
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
private fun CreateCollectionDialog(vm: AppViewModel, onImport: () -> Unit, onClose: () -> Unit) {
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
        confirmButton = { TextButton(onClick = onImport, enabled = creating == null) { Text("Liste von Webseite") } },
        dismissButton = { TextButton(onClick = onClose, enabled = creating == null) { Text("Schließen") } }
    )
}

/**
 * Liste einlesen: Adresse einer Webseite (z. B. die Disney-Liste von Vodafone) oder
 * kopierten Text einfügen – die App erkennt die Filme, ihr prüft die Vorschau und legt an.
 */
@Composable
private fun ImportListDialog(vm: AppViewModel, start: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Geteilter Text kann Überschrift + Link enthalten → Link herausziehen
    val startUrl = Regex("https?://\\S+").find(start)?.value
    var input by remember { mutableStateOf(startUrl ?: start) }
    var name by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<CollItem>>(emptyList()) }
    var off by remember { mutableStateOf(setOf<String>()) }
    var byYear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun read() {
        if (input.isBlank()) return
        busy = true; error = null
        scope.launch {
            val r = runCatching {
                val t = input.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) de.marween.filmabend.ListImporter.fromUrl(t)
                else de.marween.filmabend.ListImporter.fromText(t)
            }
            r.onSuccess {
                items = it.items; off = emptySet()
                if (name.isBlank()) name = it.name.take(60)
                if (it.items.isEmpty()) error = "Auf der Seite wurde keine Filmliste erkannt. Tipp: Auf der Webseite die Liste markieren, kopieren und hier als Text einfügen."
            }.onFailure { error = "Konnte nicht gelesen werden: ${it.localizedMessage}" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (startUrl != null) read() }

    val chosen = items.filter { it.wd !in off }.let { l -> if (byYear) l.sortedBy { it.year.ifBlank { "9999" } } else l }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Liste einlesen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("Adresse einer Webseite mit einer Filmliste einfügen – oder die Liste selbst (eine Zeile pro Film, gern mit Jahr). Du kannst Webseiten auch direkt aus dem Browser über „Teilen“ → Filmabend schicken.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(input, { input = it }, placeholder = { Text("https://… oder Liste") }, maxLines = 6, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name der Sammlung") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(byYear, { byYear = it })
                        Text("Nach Erscheinungsjahr sortieren", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${chosen.size} von ${items.size} Filmen ausgewählt – Haken entfernen, was nicht dazugehört:",
                        style = MaterialTheme.typography.labelMedium, color = Gold)
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(items, key = { _, it -> it.wd }) { i, it ->
                            Row(Modifier.fillMaxWidth().clickable { off = if (it.wd in off) off - it.wd else off + it.wd }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(it.wd !in off, { v -> off = if (v) off - it.wd else off + it.wd })
                                Text("${i + 1}. ${it.title}" + (if (it.year.isNotBlank()) " (${it.year})" else ""), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (items.isEmpty()) TextButton(onClick = { read() }, enabled = !busy && input.isNotBlank()) { Text("Einlesen") }
            else TextButton(onClick = {
                busy = true
                vm.createImportedCollection(name.ifBlank { "Meine Liste" }, chosen) { ok -> busy = false; if (ok) onClose() }
            }, enabled = !busy && chosen.isNotEmpty()) { Text("Sammlung anlegen") }
        },
        dismissButton = {
            if (items.isNotEmpty()) TextButton(onClick = { items = emptyList(); error = null }, enabled = !busy) { Text("Zurück") }
            else TextButton(onClick = onClose, enabled = !busy) { Text("Abbrechen") }
        }
    )
}
