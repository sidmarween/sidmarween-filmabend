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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marween.filmabend.AppViewModel
import de.marween.filmabend.RecMode

@Composable
fun DiscoverScreen(vm: AppViewModel, openDetail: (String, Int) -> Unit) {
    val uid by vm.uid.collectAsStateWithLifecycle()
    val hh by vm.household.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val state by vm.recs.collectAsStateWithLifecycle()
    val partner = hh?.partnerOf(uid)
    val pName = partner?.let { hh?.nameOf(it) } ?: "Partner"

    var mode by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableIntStateOf(0) }
    val modes = listOf(RecMode.BOTH, RecMode.ME, RecMode.PARTNER)
    val ratedCount = titles.count { it.ratings.isNotEmpty() }

    // Neu laden, wenn Modus wechselt oder neue Bewertungen dazukommen
    LaunchedEffect(mode, ratedCount, partner) { vm.loadRecs(modes[mode]) }

    val shown = state.items.filter { typeMatches(type, it.item.type) }

    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Für uns beide", "Für mich", "Für $pName"), mode, { mode = it })
        ChipRow(TypeFilters, type, { type = it }, Modifier.padding(top = 2.dp))
        if (state.fallback && !state.loading) {
            Text(
                "Bewertet ein paar Titel mit 7 oder mehr – dann werden die Vorschläge auf euren Geschmack zugeschnitten. Bis dahin: was gerade in den Charts ist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        when {
            state.loading && state.items.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> EmptyHint("Keine Vorschläge", state.error ?: "") {
                TextButton(onClick = { vm.loadRecs(modes[mode]) }) { Text("Nochmal versuchen") }
            }
            shown.isEmpty() && !state.loading -> EmptyHint("Nichts Passendes", "Probier einen anderen Filter.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                        IconButton(onClick = { vm.loadRecs(modes[mode]) }) { Icon(Icons.Filled.Refresh, "Neu laden") }
                    }
                }
                items(shown, key = { it.item.key }) { rec ->
                    ItemRow(
                        vm, rec.item, extra = rec.reason,
                        trailing = {
                            if (partner != null) {
                                IconButton(onClick = { vm.withResolved(rec.item) { vm.suggest(it.info, "") } }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "$pName vorschlagen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    ) { vm.withResolved(rec.item) { openDetail(it.type, it.id) } }
                }
            }
        }
    }
}
